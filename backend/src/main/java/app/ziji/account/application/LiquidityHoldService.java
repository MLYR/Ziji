package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
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
		requireReadable(userId, accountId);
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
	}

	@Override
	public void preflightCreate(UUID userId, UUID accountId) {
		requireIds(userId, accountId);
		requireWritable(userId, accountId);
		requireEligibleAccount(accountId);
	}

	@Override
	public void preflightMutation(UUID userId, UUID accountId, UUID holdId) {
		requireIds(userId, accountId);
		if (holdId == null) {
			throw new LiquidityHoldException.Validation();
		}
		requireWritable(userId, accountId);
		holds.findByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
	}

	@Override
	public LiquidityHold create(UUID userId, UUID accountId, LiquidityHoldCommand command, String requestId) {
		if (command == null) {
			throw new LiquidityHoldException.Validation();
		}
		return transactions.required(() -> {
			requireWritable(userId, accountId);
			Account account = requireEligibleAccount(accountId);
			if (command.currency() != account.currency()) {
				throw new LiquidityHoldException.BusinessRule();
			}
			Instant now = clock.instant();
			LiquidityHold created = LiquidityHold.createRoot(
				ids.get(), accountId, command.type(), command.amount(), command.currency(), command.effectiveAt(),
				command.expiresAt(), command.reason(), userId, now);
			holds.insert(created);
			auditLogs.append(audit(created, userId, requestId, "LIQUIDITY_HOLD_CREATED", null, null));
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
		return transactions.required(() -> {
			requireWritable(userId, accountId);
			Account account = requireEligibleAccount(accountId);
			LiquidityHold current = holds.lockByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
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
			auditLogs.append(audit(revised, userId, requestId, "LIQUIDITY_HOLD_REVISED", closed, "SUPERSEDED"));
			return revised;
		});
	}

	@Override
	public LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId) {
		if (holdId == null || expectedVersion < 1) {
			throw new LiquidityHoldException.Validation();
		}
		return transactions.required(() -> {
			requireWritable(userId, accountId);
			requireEligibleAccount(accountId);
			LiquidityHold current = holds.lockByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
			if (current.version() != expectedVersion) {
				throw new LiquidityHoldException.VersionConflict(current);
			}
			Instant now = clock.instant();
			if (!current.isOperableAt(now)) {
				throw new LiquidityHoldException.BusinessRule();
			}
			LiquidityHold released = holds.releaseIfVersion(accountId, holdId, expectedVersion, now)
				.orElseThrow(() -> concurrentConflict(accountId, holdId));
			auditLogs.append(audit(released, userId, requestId, "LIQUIDITY_HOLD_RELEASED", current, "RELEASED"));
			return released;
		});
	}

	@Override
	public LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
		requireReadable(userId, accountId);
		LiquidityHold hold = holds.findByAccountAndId(accountId, holdId).orElseThrow(AccountNotVisibleException::new);
		if (hold.version() != expectedVersion) {
			// 幂等重放不是新的写入；历史版本无法精确重建时必须 fail closed。
			throw new LiquidityHoldException.SafeReplayUnavailable();
		}
		if (logicalStatusMayHaveChanged(hold, clock.instant())) {
			// statusAt(asOf) 不是持久化字段；跨过 effectiveAt/expiresAt 后不能重建首次响应。
			throw new LiquidityHoldException.SafeReplayUnavailable();
		}
		return hold;
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

	private Account requireEligibleAccount(UUID accountId) {
		Account account = accounts.findById(accountId).orElseThrow(AccountNotVisibleException::new);
		if (account.accountClass() != AccountClass.ASSET && account.accountClass() != AccountClass.INVESTMENT) {
			throw new LiquidityHoldException.BusinessRule();
		}
		return account;
	}

	private void requireReadable(UUID userId, UUID accountId) {
		memberships.findActiveMembership(userId, accountId).orElseThrow(AccountNotVisibleException::new);
	}

	private void requireWritable(UUID userId, UUID accountId) {
		ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
			.orElseThrow(AccountNotVisibleException::new);
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
		String reasonCode) {
		return new AuditLogWritePort.AuditLogEntry(
			hold.updatedAt(), actorUserId, AuditLogWritePort.ActorType.USER, action, "LIQUIDITY_HOLD", hold.id(),
			hold.accountId(), requestId, AuditLogWritePort.Result.SUCCESS, reasonCode,
			previous == null
				? java.util.Map.of("holdId", hold.id().toString(), "version", Integer.toString(hold.version()), "type", hold.type().name())
				: java.util.Map.of("holdId", hold.id().toString(), "previousHoldId", previous.id().toString(),
					"version", Integer.toString(hold.version()), "fromType", previous.type().name(), "toType", hold.type().name()));
	}

	private static void requireIds(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			throw new LiquidityHoldException.Validation();
		}
	}
}
