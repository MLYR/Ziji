package app.ziji.ledger.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.Transaction;

/** HTTP 幂等取得前的对象级账务写权限检查；不写入任何业务或幂等事实。 */
public final class LedgerCommandPreflightService {

	private final AccountPostingReferencePort accounts;
	private final AccountMembershipReadPort memberships;
	private final AccountPostingAccessPort postingAccess;
	private final LedgerAccountStore ledgerAccounts;

	public LedgerCommandPreflightService(
		AccountPostingReferencePort accounts,
		AccountMembershipReadPort memberships,
		AccountPostingAccessPort postingAccess,
		LedgerAccountStore ledgerAccounts) {
		if (accounts == null || memberships == null || postingAccess == null || ledgerAccounts == null) {
			throw new LedgerCommandValidationException("账务写入前置依赖不能为空。");
		}
		this.accounts = accounts;
		this.memberships = memberships;
		this.postingAccess = postingAccess;
		this.ledgerAccounts = ledgerAccounts;
	}

	public Map<UUID, AccountPostingReference> requireWritable(
		UUID userId,
		List<Transaction> relatedTransactions,
		List<UUID> requestedAccountIds) {
		if (userId == null || relatedTransactions == null || requestedAccountIds == null) {
			throw new LedgerCommandValidationException("账务写入前置参数无效。");
		}
		Set<UUID> accountIds = new LinkedHashSet<>();
		for (Transaction transaction : relatedTransactions) {
			if (transaction == null) {
				throw new LedgerCommandValidationException("关联交易不能为空。");
			}
			for (var entry : transaction.entries()) {
				LedgerAccountReference ledgerAccount = ledgerAccounts.findById(entry.ledgerAccountId())
					.orElseThrow(() -> new LedgerCommandValidationException("关联交易账务科目不存在。"));
				if (!ledgerAccount.active()) {
					throw new LedgerCommandValidationException("关联交易账务科目不可用。");
				}
				if (ledgerAccount.visibleAccountId() != null) {
					accountIds.add(ledgerAccount.visibleAccountId());
				} else if (ledgerAccount.role() != LedgerAccountRole.SYSTEM) {
					throw new LedgerCommandValidationException("关联交易内部科目角色无效。");
				}
			}
		}
		for (UUID accountId : requestedAccountIds) {
			if (accountId == null) {
				throw new LedgerCommandValidationException("账务账户不能为空。");
			}
			accountIds.add(accountId);
		}
		if (accountIds.isEmpty()) {
			throw new TransactionNotVisibleException();
		}

		Map<UUID, AccountPostingReference> references = new LinkedHashMap<>();
		Map<UUID, ActiveMembership> activeMemberships = new LinkedHashMap<>();
		// 先检查全部对象可见性，再判定角色，避免双账户请求泄漏另一侧是否存在。
		for (UUID accountId : accountIds) {
			AccountPostingReference account = accounts.findById(accountId)
				.orElseThrow(TransactionNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
				.orElseThrow(TransactionNotVisibleException::new);
			references.put(accountId, account);
			activeMemberships.put(accountId, membership);
		}
		for (UUID accountId : accountIds) {
			String role = activeMemberships.get(accountId).role();
			if (!"OWNER".equals(role) && !"EDITOR".equals(role)) {
				throw new LedgerPermissionDeniedException();
			}
		}
		for (UUID accountId : accountIds) {
			if (!references.get(accountId).active()) {
				throw new LedgerCommandValidationException("归档账户不能新增或修改账务交易。");
			}
			switch (postingAccess.postingDecision(userId, accountId)) {
				case ALLOWED -> {
				}
				case NOT_VISIBLE -> throw new TransactionNotVisibleException();
				case READ_ONLY -> throw new LedgerPermissionDeniedException();
			}
		}
		return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(references));
	}

	public Map<UUID, AccountPostingReference> requireWritable(
		UUID userId, UUID... requestedAccountIds) {
		List<UUID> ids = new ArrayList<>();
		if (requestedAccountIds != null) {
			java.util.Collections.addAll(ids, requestedAccountIds);
		}
		return requireWritable(userId, List.of(), ids);
	}
}
