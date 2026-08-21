package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountStatus;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;

/** 账户归档应用编排：余额确认、当前 OWNER、版本条件写入、审计和历史保留共享同一事务。 */
public final class AccountArchiveService implements AccountArchiveUseCase {

	private final TransactionRunner transactions;
	private final AccountStore accounts;
	private final AccountArchiveStore archives;
	private final AccountMembershipReadPort memberships;
	private final AccountBalanceReadPort balances;
	private final AuditLogWritePort auditLogs;
	private final Clock clock;

	public AccountArchiveService(
		TransactionRunner transactions,
		AccountStore accounts,
		AccountArchiveStore archives,
		AccountMembershipReadPort memberships,
		AccountBalanceReadPort balances,
		AuditLogWritePort auditLogs,
		Clock clock) {
		if (transactions == null || accounts == null || archives == null || memberships == null
			|| balances == null || auditLogs == null || clock == null) {
			throw new AccountArchiveException.Validation();
		}
		this.transactions = transactions;
		this.accounts = accounts;
		this.archives = archives;
		this.memberships = memberships;
		this.balances = balances;
		this.auditLogs = auditLogs;
		this.clock = clock;
	}

	@Override
	public void preflightAccess(UUID userId, UUID accountId) {
		requireIds(userId, accountId);
		transactions.required(() -> {
			accounts.findById(accountId).orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			requireOwner(membership);
		});
	}

	@Override
	public AccountQueryResult archive(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		String reason,
		boolean confirmNonZeroBalance,
		String requestId) {
		requireIds(userId, accountId);
		if (expectedVersion < 1 || reason == null || reason.isBlank()
			|| reason.codePointCount(0, reason.length()) > 500 || requestId == null || requestId.isBlank()) {
			throw new AccountArchiveException.Validation();
		}
		return transactions.nested(() -> {
			// 与账务写入保持账户→membership 锁序，归档提交后普通交易不能沿用旧 ACTIVE 快照。
			Account current = accounts.findByIdForUpdate(accountId)
				.orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			requireOwner(membership);
			if (current.status() == AccountStatus.ARCHIVED) {
				throw new AccountArchiveException.AlreadyArchived();
			}
			if (current.version() != expectedVersion) {
				throw new AccountVersionConflictException(toResult(current, membership));
			}

			AccountBalanceReadPort.PostedPrimaryBalance balance = postedPrimaryBalance(accountId);
			if (balance.currency() != current.currency()) {
				throw new AccountArchiveException.Persistence(new IllegalStateException("账户与 PRIMARY 余额币种不一致。"));
			}
			if (balance.amount().signum() != 0 && !confirmNonZeroBalance) {
				// 不携带余额进入异常或审计，避免风险提示变成账户事实枚举通道。
				throw new AccountArchiveException.NonZeroBalanceConfirmationRequired();
			}

			Instant now = clock.instant();
			// 先通过领域迁移校验，再由 SQL 以 ACTIVE+version 条件原子落盘。
			Account archivedCandidate = current.archive(now);
			Account archived = archives.archiveIfVersion(accountId, expectedVersion, now)
				.orElseThrow(() -> new AccountArchiveException.Persistence(
					new IllegalStateException("账户归档条件更新未命中。")));
			if (archived.status() != archivedCandidate.status()
				|| archived.version() != archivedCandidate.version()
				|| !now.equals(archived.archivedAt())) {
				throw new AccountArchiveException.Persistence(
					new IllegalStateException("账户归档结果与领域迁移不一致。"));
			}
			Map<String, String> metadata = new LinkedHashMap<>();
			metadata.put("accountId", accountId.toString());
			metadata.put("previousVersion", Integer.toString(current.version()));
			metadata.put("version", Integer.toString(archived.version()));
			auditLogs.append(new AuditLogWritePort.AuditLogEntry(
				now,
				userId,
				AuditLogWritePort.ActorType.USER,
				"ACCOUNT_ARCHIVED",
				"ACCOUNT",
				accountId,
				accountId,
				requestId,
				AuditLogWritePort.Result.SUCCESS,
				balance.amount().signum() == 0 ? "ZERO_BALANCE" : "NON_ZERO_BALANCE_CONFIRMED",
				metadata));
			return toResult(archived, membership);
		});
	}

	@Override
	public AccountQueryResult replay(UUID userId, UUID accountId, int expectedVersion) {
		requireIds(userId, accountId);
		if (expectedVersion < 1) {
			throw new AccountArchiveException.Validation();
		}
		return transactions.required(() -> {
			Account current = accounts.findByIdForUpdate(accountId)
				.orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			requireOwner(membership);
			if (current.status() != AccountStatus.ARCHIVED || current.version() != expectedVersion) {
				throw new AccountArchiveException.SafeReplayUnavailable();
			}
			return toResult(current, membership);
		});
	}

	private AccountBalanceReadPort.PostedPrimaryBalance postedPrimaryBalance(UUID accountId) {
		try {
			return balances.findPostedPrimaryBalance(accountId)
				.orElseThrow(() -> new AccountArchiveException.Persistence(
					new IllegalStateException("账户 PRIMARY 余额不存在。")));
		} catch (AccountArchiveException.Persistence exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new AccountArchiveException.Persistence(exception);
		}
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

	private static void requireOwner(ActiveMembership membership) {
		if (!"OWNER".equals(membership.role())) {
			throw new AccountPermissionDeniedException();
		}
	}

	private static void requireIds(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			throw new AccountArchiveException.Validation();
		}
	}
}
