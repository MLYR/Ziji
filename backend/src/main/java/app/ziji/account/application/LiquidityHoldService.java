package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;

/**
 * LiquidityHold 编排：先按 ACTIVE membership 授权，再在单一事务内写事实、审计和统一幂等终态。
 */
public class LiquidityHoldService implements LiquidityHoldUseCase {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;

	private final AccountStore accounts;
	private final AccountMembershipReadPort memberships;
	private final LiquidityHoldStore holds;
	private final LiquidityHoldCursorCodec cursors;
	private final AuditLogWritePort auditLogs;
	private final TransactionRunner transactions;
	private final Clock clock;
	private final java.util.function.Supplier<UUID> ids;

	public LiquidityHoldService(
		AccountStore accounts,
		AccountMembershipReadPort memberships,
		LiquidityHoldStore holds,
		LiquidityHoldCursorCodec cursors,
		AuditLogWritePort auditLogs,
		TransactionRunner transactions,
		Clock clock,
		java.util.function.Supplier<UUID> ids) {
		if (accounts == null || memberships == null || holds == null || cursors == null || auditLogs == null
			|| transactions == null || clock == null || ids == null) {
			throw new LiquidityHoldException.Validation();
		}
		this.accounts = accounts;
		this.memberships = memberships;
		this.holds = holds;
		this.cursors = cursors;
		this.auditLogs = auditLogs;
		this.transactions = transactions;
		this.clock = clock;
		this.ids = ids;
	}

	@Override
	public LiquidityHoldPage list(UUID userId, UUID accountId, Integer requestedLimit, String cursor) {
		requireIds(userId, accountId);
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new LiquidityHoldException.Validation();
		}
		return withCurrentAccountMembership(userId, accountId, (account, membership) -> {
			LiquidityHoldKeysetPosition after = cursor == null ? null : cursors.decode(accountId, cursor);
			if (after != null) {
				LiquidityHold boundary = holds.findByAccountAndId(accountId, after.holdId())
					.orElseThrow(LiquidityHoldException.Validation::new);
				if (!boundary.createdAt().equals(after.createdAt())) {
					throw new LiquidityHoldException.Validation();
				}
			}
			List<LiquidityHold> rows = holds.listByAccount(accountId, after, limit + 1);
			boolean hasMore = rows.size() > limit;
			List<LiquidityHold> page = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;
			String nextCursor = hasMore ? cursors.encode(accountId,
				new LiquidityHoldKeysetPosition(page.getLast().createdAt(), page.getLast().id())) : null;
			return new LiquidityHoldPage(page, nextCursor, hasMore);
		});
	}

	@Override
	public void preflightCreateAccess(UUID userId, UUID accountId) {
		withCurrentAccountMembership(userId, accountId, (account, membership) -> {
			requireWritable(membership);
			return null;
		});
	}

	@Override
	public void preflightCreate(UUID userId, UUID accountId) {
		// 账户生命周期业务规则必须留到幂等工作内，避免稳定 422 绕过 FAILED_FINAL。
		preflightCreateAccess(userId, accountId);
	}

	@Override
	public void preflightMutationAccess(
		UUID userId,
		UUID accountId,
		UUID holdId,
		int expectedVersion) {
		requireIds(userId, accountId);
		if (holdId == null || expectedVersion < 1) {
			throw new LiquidityHoldException.Validation();
		}
		withCurrentAccountMembership(userId, accountId, (account, membership) -> {
			holds.findByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
			requireWritable(membership);
			return null;
		});
	}

	@Override
	public void preflightMutation(
		UUID userId,
		UUID accountId,
		UUID holdId,
		int expectedVersion,
		boolean allowArchivedAccount) {
		// allowArchivedAccount 由真实写事务消费；新请求预检不能先消费可变账户资格。
		preflightMutationAccess(userId, accountId, holdId, expectedVersion);
	}

	@Override
	public LiquidityHold create(UUID userId, UUID accountId, LiquidityHoldCommand command, String requestId) {
		if (command == null) {
			throw new LiquidityHoldException.Validation();
		}
		// 创建的稳定业务失败也要回滚到幂等外层的 savepoint，才能提交 FAILED_FINAL 终态。
		return transactions.nested(() -> {
			// 统一按账户→membership→hold 加锁，避免归档/撤权与事实写入在校验后交错。
			Account account = accounts.findByIdForUpdate(accountId).orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			requireWritable(membership);
			requireEligibleAccount(account, false);
			if (command.currency() != account.currency()) {
				throw new LiquidityHoldException.BusinessRule();
			}
			Instant now = clock.instant();
			LiquidityHold created = LiquidityHold.createRoot(
				ids.get(), accountId, command.type(), command.amount(), command.currency(), command.effectiveAt(),
				command.expiresAt(), command.reason(), userId, now);
			holds.insert(created);
			auditLogs.append(audit(created, userId, requestId, "LIQUIDITY_HOLD_CREATED", null, null, null));
			return created;
		});
	}

	@Override
	public LiquidityHold revise(
		UUID userId,
		UUID accountId,
		UUID holdId,
		int expectedVersion,
		LiquidityHoldCommand command,
		String requestId) {
		if (holdId == null || expectedVersion < 1 || command == null) {
			throw new LiquidityHoldException.Validation();
		}
		// VERSION_CONFLICT 由外层统一幂等事务固化；savepoint 回滚不能污染该 FAILED_FINAL 终态。
		return transactions.nested(() -> {
			// 与创建保持账户→membership→hold 的锁顺序，撤权/归档先提交则本次写入重新发现不可见或不可写。
			Account account = accounts.findByIdForUpdate(accountId).orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			LiquidityHold current = holds.lockByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
			requireWritable(membership);
			requireEligibleAccount(account, false);
			if (current.version() != expectedVersion) {
				throw new LiquidityHoldException.VersionConflict(current);
			}
			Instant now = clock.instant();
			if (!current.isOperableAt(now)) {
				throw new LiquidityHoldException.BusinessRule();
			}
			if (command.currency() != account.currency()) {
				throw new LiquidityHoldException.BusinessRule();
			}
			LiquidityHold closed = holds.supersedeIfVersion(accountId, holdId, expectedVersion, now)
				.orElseThrow(() -> concurrentConflict(accountId, holdId));
			LiquidityHold revised = LiquidityHold.createRevision(
				ids.get(), closed, command.type(), command.amount(), command.currency(), command.effectiveAt(),
				command.expiresAt(), command.reason(), userId, now);
			holds.insert(revised);
			// 审计引用修订前的稳定版本；数据库关闭后的 version 递增值由 expectedVersion/新事实共同表达。
			auditLogs.append(audit(revised, userId, requestId, "LIQUIDITY_HOLD_REVISED", current, "SUPERSEDED", expectedVersion));
			return revised;
		});
	}

	@Override
	public LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId) {
		if (holdId == null || expectedVersion < 1) {
			throw new LiquidityHoldException.Validation();
		}
		// release 与修订共享冲突重放语义，预期冲突只回滚生命周期子事务。
		return transactions.nested(() -> {
			// 释放也必须先锁账户和当前 membership，不能因归档放行而绕过撤权串行化。
			Account account = accounts.findByIdForUpdate(accountId).orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			LiquidityHold current = holds.lockByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
			requireWritable(membership);
			requireEligibleAccount(account, true);
			if (current.version() != expectedVersion) {
				throw new LiquidityHoldException.VersionConflict(current);
			}
			Instant now = clock.instant();
			if (!current.isOperableAt(now)) {
				throw new LiquidityHoldException.BusinessRule();
			}
			LiquidityHold released = holds.releaseIfVersion(accountId, holdId, expectedVersion, now)
				.orElseThrow(() -> concurrentConflict(accountId, holdId));
			auditLogs.append(audit(released, userId, requestId, "LIQUIDITY_HOLD_RELEASED", current, "RELEASED", expectedVersion));
			return released;
		});
	}

	@Override
	public LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
		return withCurrentAccountMembership(userId, accountId, (account, membership) -> {
			LiquidityHold hold = holds.findByAccountAndId(accountId, holdId)
				.orElseThrow(AccountNotVisibleException::new);
			if (hold.version() != expectedVersion) {
				// 幂等重放不是新的写入；历史版本无法精确重建时必须 fail closed。
				throw new LiquidityHoldException.SafeReplayUnavailable();
			}
			if (logicalStatusMayHaveChanged(hold, clock.instant())) {
				// statusAt(asOf) 不是持久化字段；跨过 effectiveAt/expiresAt 后不能重建首次响应。
				throw new LiquidityHoldException.SafeReplayUnavailable();
			}
			return hold;
		});
	}

	private static boolean logicalStatusMayHaveChanged(LiquidityHold hold, Instant asOf) {
		if (hold.endReason() != null) {
			return false;
		}
		boolean effectiveTransitionPassed = hold.effectiveAt().isAfter(hold.createdAt())
			&& !hold.effectiveAt().isAfter(asOf);
		boolean expiryTransitionPassed = hold.expiresAt() != null
			&& hold.expiresAt().isAfter(hold.createdAt())
			&& !hold.expiresAt().isAfter(asOf);
		return effectiveTransitionPassed || expiryTransitionPassed;
	}

	private LiquidityHoldException.VersionConflict concurrentConflict(UUID accountId, UUID holdId) {
		LiquidityHold current = holds.findByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
		return new LiquidityHoldException.VersionConflict(current);
	}

	private Account requireEligibleAccount(Account account, boolean allowArchived) {
		if (account.accountClass() != AccountClass.ASSET && account.accountClass() != AccountClass.INVESTMENT) {
			throw new LiquidityHoldException.BusinessRule();
		}
		if (!allowArchived && account.status() != AccountStatus.ACTIVE) {
			// 归档账户不再接受新增/修订事实；释放既有事实由调用方显式允许。
			throw new LiquidityHoldException.BusinessRule();
		}
		return account;
	}

	private <T> T withCurrentAccountMembership(
		UUID userId,
		UUID accountId,
		BiFunction<Account, ActiveMembership, T> action) {
		requireIds(userId, accountId);
		if (action == null) {
			throw new LiquidityHoldException.Validation();
		}
		return transactions.required(() -> {
			// 读取和历史幂等重放沿用账户→membership 锁序，撤权提交后不能继续读取旧主体的数据。
			Account account = accounts.findByIdForUpdate(accountId).orElseThrow(AccountNotVisibleException::new);
			ActiveMembership membership = memberships.findActiveMembershipForUpdate(userId, accountId)
				.orElseThrow(AccountNotVisibleException::new);
			return action.apply(account, membership);
		});
	}

	private void requireWritable(ActiveMembership membership) {
		if (!"OWNER".equals(membership.role()) && !"EDITOR".equals(membership.role())) {
			throw new AccountPermissionDeniedException();
		}
	}

	private static AuditLogWritePort.AuditLogEntry audit(
		LiquidityHold hold,
		UUID actorUserId,
		String requestId,
		String action,
		LiquidityHold previous,
		String reasonCode,
		Integer expectedVersion) {
		java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
		metadata.put("holdId", hold.id().toString());
		if (previous != null) {
			metadata.put("previousHoldId", previous.id().toString());
			metadata.put("previousVersion", Integer.toString(previous.version()));
			metadata.put("fromType", previous.type().name());
		}
		metadata.put("version", Integer.toString(hold.version()));
		if (expectedVersion != null) {
			// 审计保留乐观锁前置版本，便于区分并发胜者与后续事实版本。
			metadata.put("expectedVersion", Integer.toString(expectedVersion));
		}
		metadata.put(previous == null ? "type" : "toType", hold.type().name());
		return new AuditLogWritePort.AuditLogEntry(
			hold.updatedAt(), actorUserId, AuditLogWritePort.ActorType.USER, action, "LIQUIDITY_HOLD", hold.id(),
			hold.accountId(), requestId, AuditLogWritePort.Result.SUCCESS, reasonCode,
			metadata);
	}

	private static void requireIds(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			throw new LiquidityHoldException.Validation();
		}
	}
}
