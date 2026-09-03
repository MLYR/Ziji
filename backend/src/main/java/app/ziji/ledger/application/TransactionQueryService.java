package app.ziji.ledger.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.category.application.CategoryReference;
import app.ziji.category.application.CategoryStore;

/** 交易读取编排：先收敛 ACTIVE membership，再把可见账户集合交给 Ledger 查询端口。 */
public class TransactionQueryService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;

	private final TransactionQueryReadPort transactions;
	private final AccountMembershipReadPort memberships;
	private final CategoryStore categories;
	private final TransactionCursorCodec cursors;

	public TransactionQueryService(
		TransactionQueryReadPort transactions,
		AccountMembershipReadPort memberships,
		CategoryStore categories,
		TransactionCursorCodec cursors) {
		if (transactions == null || memberships == null || categories == null || cursors == null) {
			throw new TransactionQueryValidationException();
		}
		this.transactions = transactions;
		this.memberships = memberships;
		this.categories = categories;
		this.cursors = cursors;
	}

	public TransactionPage list(UUID userId, TransactionQuery query, Integer requestedLimit, String cursor) {
		if (userId == null || query == null) {
			throw new TransactionQueryValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new TransactionQueryValidationException();
		}
		Set<UUID> visibleAccounts = visibleAccounts(userId);
		validateQueryScope(userId, query, visibleAccounts);
		TransactionKeysetPosition after = cursor == null ? null : cursors.decode(userId, query, cursor);
		if (after != null && !transactions.hasVisibleBoundary(visibleAccounts, query, after)) {
			throw new TransactionQueryValidationException();
		}
		if (visibleAccounts.isEmpty()) {
			if (after != null) {
				throw new TransactionQueryValidationException();
			}
			return new TransactionPage(List.of(), null, false);
		}
		List<TransactionQueryReadPort.TransactionSnapshot> rows = transactions.listVisible(
			visibleAccounts, query, after, limit + 1);
		boolean hasMore = rows.size() > limit;
		List<TransactionQueryReadPort.TransactionSnapshot> page = hasMore
			? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
		String nextCursor = hasMore
			? cursors.encode(userId, query, new TransactionKeysetPosition(
				page.getLast().transaction().businessDate(), page.getLast().transaction().transactionId()))
			: null;
		return new TransactionPage(page, nextCursor, hasMore);
	}

	public TransactionQueryReadPort.TransactionSnapshot get(UUID userId, UUID transactionId) {
		if (userId == null || transactionId == null) {
			throw new TransactionQueryValidationException();
		}
		return transactions.findVisible(visibleAccounts(userId), transactionId)
			.orElseThrow(TransactionNotVisibleException::new);
	}

	private Set<UUID> visibleAccounts(UUID userId) {
		Set<UUID> accountIds = new HashSet<>();
		for (AccountMembershipReadPort.ActiveMembership membership : memberships.listActiveMemberships(userId)) {
			accountIds.add(membership.accountId());
		}
		return Set.copyOf(accountIds);
	}

	private void validateQueryScope(UUID userId, TransactionQuery query, Set<UUID> visibleAccounts) {
		if (query.accountId() != null && !visibleAccounts.contains(query.accountId())) {
			throw new TransactionNotVisibleException();
		}
		if (query.categoryId() == null) {
			return;
		}
		CategoryReference category = categories.findById(query.categoryId())
			.orElseThrow(TransactionQueryValidationException::new);
		boolean accountVisible = category.accountId() == null || visibleAccounts.contains(category.accountId());
		boolean ownerVisible = category.ownerUserId() == null || userId.equals(category.ownerUserId());
		if (!category.active() || !accountVisible || !ownerVisible) {
			throw new TransactionQueryValidationException();
		}
	}
}
