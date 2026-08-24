package app.ziji.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.LiquidityHold;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;

/**
 * LiquidityHold 自动过期用例：SYSTEM 任务不依赖 membership，只按事实版本条件完成终止转换。
 */
public class LiquidityHoldExpiryFinalizer {

	private static final int MAXIMUM_BATCH_SIZE = 1_000;

	private final LiquidityHoldStore holds;
	private final AccountStore accounts;
	private final AuditLogWritePort auditLogs;
	private final TransactionRunner transactions;
	private final Clock clock;

	public LiquidityHoldExpiryFinalizer(
		AccountStore accounts,
		LiquidityHoldStore holds,
		AuditLogWritePort auditLogs,
		TransactionRunner transactions,
		Clock clock) {
		this.accounts = Objects.requireNonNull(accounts);
		this.holds = Objects.requireNonNull(holds);
		this.auditLogs = Objects.requireNonNull(auditLogs);
		this.transactions = Objects.requireNonNull(transactions);
		this.clock = Objects.requireNonNull(clock);
	}

	public Result finalizeExpired(Instant asOf, String correlationId, int batchSize) {
		if (asOf == null || correlationId == null || correlationId.isBlank()
			|| batchSize < 1 || batchSize > MAXIMUM_BATCH_SIZE) {
			throw new IllegalArgumentException("流动性占用过期最终化参数无效。");
		}
		return transactions.required(() -> {
			List<LiquidityHold> candidates = holds.findExpiredUnended(asOf, batchSize);
			// 批次可能跨多个账户；先按全局 UUID 顺序锁完账户，再按到期顺序处理 Hold，避免与多账户账务锁序相反。
			List<UUID> accountIds = candidates.stream()
				.map(LiquidityHold::accountId)
				.distinct()
				.sorted()
				.toList();
			for (UUID accountId : accountIds) {
				accounts.findByIdForUpdate(accountId);
			}
			int finalizedCount = 0;
			for (LiquidityHold candidate : candidates) {
				if (!candidate.canFinalizeExpiryAt(asOf)) {
					continue;
				}
				Instant finalizedAt = clock.instant();
				Optional<LiquidityHold> finalized = holds.expireIfVersion(
					candidate.accountId(), candidate.id(), candidate.version(), asOf, finalizedAt);
				if (finalized.isEmpty()) {
					continue;
				}
				appendSystemAudit(finalized.get(), correlationId);
				finalizedCount++;
			}
			return new Result(candidates.size(), finalizedCount);
		});
	}

	private void appendSystemAudit(LiquidityHold finalized, String correlationId) {
		// SYSTEM 任务无需当前用户 membership；历史账户或创建者撤权不能阻止既有事实过期。
		auditLogs.append(new AuditLogWritePort.AuditLogEntry(
			finalized.updatedAt(), null, AuditLogWritePort.ActorType.SYSTEM,
			"LIQUIDITY_HOLD_EXPIRED", "LIQUIDITY_HOLD", finalized.id(), finalized.accountId(), correlationId,
			AuditLogWritePort.Result.SUCCESS, "EXPIRED",
			Map.of("holdId", finalized.id().toString(), "version", Integer.toString(finalized.version()))));
	}

	public record Result(int candidateCount, int finalizedCount) {
	}
}
