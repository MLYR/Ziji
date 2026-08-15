package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountPatch;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.shared.application.TransactionRunner;

/**
 * 账户查询与资料更新编排：列表/详情只依据 ACTIVE membership，更新严格 If-Match 乐观锁。
 * 游标由 infrastructure 加密并绑定当前用户，数据库按 createdAt/ID keyset 继续查询，不依赖 OFFSET。
 */
public class AccountQueryService implements AccountQueryUseCase {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;

	private final AccountQueryReadPort accounts;
	private final AccountUpdatePort updates;
	private final AccountMembershipReadPort memberships;
	private final AccountCursorCodec cursors;
	private final TransactionRunner transactions;
	private final Clock clock;

	public AccountQueryService(
		AccountQueryReadPort accounts,
		AccountUpdatePort updates,
		AccountMembershipReadPort memberships,
		AccountCursorCodec cursors,
		TransactionRunner transactions,
		Clock clock) {
		if (accounts == null || updates == null || memberships == null || cursors == null
			|| transactions == null || clock == null) {
			throw new AccountQueryValidationException("账户查询服务依赖不能为空。");
		}
		this.accounts = accounts;
		this.updates = updates;
		this.memberships = memberships;
		this.cursors = cursors;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Override
	public AccountPage listVisibleAccounts(UUID userId, Integer requestedLimit, String cursor) {
		if (userId == null) {
			throw new AccountQueryValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new AccountQueryValidationException();
		}

		AccountKeysetPosition after = cursor == null ? null : cursors.decode(userId, cursor);
		List<ActiveMembership> activeMemberships = memberships.listActiveMemberships(userId);
		if (activeMemberships.isEmpty()) {
			if (after != null) {
				throw new AccountQueryValidationException();
			}
			return new AccountPage(List.of(), null, false);
		}

		Map<UUID, ActiveMembership> membershipByAccount = new HashMap<>();
		for (ActiveMembership membership : activeMemberships) {
			membershipByAccount.put(membership.accountId(), membership);
		}

		if (after != null) {
			// 游标边界必须仍属于当前 ACTIVE membership 过滤结果，且排序键与数据库事实一致。
			Account boundary = accounts.findById(after.accountId())
				.orElseThrow(AccountQueryValidationException::new);
			if (!membershipByAccount.containsKey(after.accountId())
				|| !boundary.createdAt().equals(after.createdAt())) {
				throw new AccountQueryValidationException();
			}
		}
		List<Account> accountRows = accounts.listByIds(membershipByAccount.keySet(), after, limit + 1);
		List<AccountQueryResult> visible = new ArrayList<>(accountRows.size());
		for (Account account : accountRows) {
			ActiveMembership membership = membershipByAccount.get(account.id());
			if (membership != null) {
				visible.add(toResult(account, membership));
			}
		}
		boolean hasMore = visible.size() > limit;
		List<AccountQueryResult> page = hasMore ? List.copyOf(visible.subList(0, limit)) : List.copyOf(visible);
		String nextCursor = hasMore
			? cursors.encode(userId, new AccountKeysetPosition(page.getLast().createdAt(), page.getLast().id()))
			: null;
		return new AccountPage(page, nextCursor, hasMore);
	}

	@Override
	public AccountQueryResult getVisibleAccount(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			throw new AccountQueryValidationException();
		}
		return findVisible(userId, accountId).orElseThrow(AccountNotVisibleException::new);
	}

	@Override
	public AccountQueryResult updateAccount(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		AccountPatch patch) {
		if (userId == null || accountId == null || patch == null || patch.isEmpty() || expectedVersion < 1) {
			throw new AccountQueryValidationException();
		}
		return transactions.required(() -> {
			ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			if (!"OWNER".equals(membership.role())) {
				throw new AccountPermissionDeniedException();
			}

			Account current = accounts.findById(accountId).orElseThrow(AccountNotVisibleException::new);
			Instant now = clock.instant();
			// 复用 Account 领域边界校验；返回的新快照仅用于校验，不伪造持久化事实。
			current.apply(patch, now);

			Optional<Account> updated = updates.updateIfVersion(accountId, expectedVersion, patch, now);
			if (updated.isEmpty()) {
				AccountQueryResult currentVisible = findVisible(userId, accountId)
					.orElseThrow(AccountNotVisibleException::new);
				throw new AccountVersionConflictException(currentVisible);
			}

			return toResult(updated.get(), membership);
		});
	}

	private Optional<AccountQueryResult> findVisible(UUID userId, UUID accountId) {
		Optional<ActiveMembership> membership = memberships.findActiveMembership(userId, accountId);
		if (membership.isEmpty()) {
			return Optional.empty();
		}
		return accounts.findById(accountId).map(account -> toResult(account, membership.get()));
	}

	private static AccountQueryResult toResult(Account account, ActiveMembership membership) {
		return new AccountQueryResult(
			account.id(),
			account.accountClass(),
			account.accountType(),
			account.name(),
			account.institution(),
			account.currency(),
			account.status(),
			account.createdAt(),
			account.version(),
			membership.role(),
			membership.inclusionRatio());
	}

}
