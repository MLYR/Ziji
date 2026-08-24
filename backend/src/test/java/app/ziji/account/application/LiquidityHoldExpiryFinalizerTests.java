package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldEndReason;
import app.ziji.account.domain.LiquidityHoldSource;
import app.ziji.account.domain.LiquidityHoldType;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 自动过期应用用例：只物化真实到期事实，并把 SYSTEM 审计放进同一事务边界。 */
class LiquidityHoldExpiryFinalizerTests {

	private static final Instant AS_OF = Instant.parse("2026-08-24T12:00:00Z");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");

	@Test
	void finalizesEligibleHoldOnceAndWritesSystemAuditWithCorrelationId() {
		LiquidityHold expired = hold(AS_OF.minusSeconds(30), AS_OF.minusSeconds(1), 1);
		InMemoryHoldStore holds = new InMemoryHoldStore(expired);
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldExpiryFinalizer finalizer = new LiquidityHoldExpiryFinalizer(
			new InMemoryAccountStore(), holds, audits::add, new DirectTransactionRunner(),
			Clock.fixed(AS_OF, ZoneOffset.UTC));

		LiquidityHoldExpiryFinalizer.Result result = finalizer.finalizeExpired(AS_OF, "expiry-correlation-001", 10);

		assertEquals(1, result.finalizedCount());
		assertEquals(1, holds.updateCount);
		assertEquals(1, audits.size());
		AuditLogWritePort.AuditLogEntry audit = audits.getFirst();
		assertEquals(AuditLogWritePort.ActorType.SYSTEM, audit.actorType());
		assertNull(audit.actorUserId());
		assertEquals("expiry-correlation-001", audit.requestId());
		assertEquals("LIQUIDITY_HOLD_EXPIRED", audit.action());
		assertEquals("EXPIRED", audit.reasonCode());
		assertEquals("2", audit.metadata().get("version"));
		assertEquals(2, audit.metadata().size());
		assertEquals(LiquidityHoldEndReason.EXPIRED, holds.current().endReason());
		assertEquals(2, holds.current().version());

		LiquidityHoldExpiryFinalizer.Result retry = finalizer.finalizeExpired(AS_OF, "expiry-correlation-002", 10);

		assertEquals(0, retry.finalizedCount());
		assertEquals(1, holds.updateCount);
		assertEquals(1, audits.size());
	}

	private static LiquidityHold hold(Instant effectiveAt, Instant expiresAt, int version) {
		UUID holdId = UUID.randomUUID();
		return LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, effectiveAt, expiresAt, null,
			LiquidityHoldSource.MANUAL, "不会进入 SYSTEM 审计", null, null, USER_ID,
			effectiveAt, effectiveAt, version);
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class InMemoryHoldStore implements LiquidityHoldStore {
		private final Map<UUID, LiquidityHold> rows = new HashMap<>();
		private int updateCount;

		private InMemoryHoldStore(LiquidityHold initial) {
			rows.put(initial.id(), initial);
		}

		@Override
		public List<LiquidityHold> listByAccount(UUID accountId, LiquidityHoldKeysetPosition after, int maximumRecords) {
			return List.of();
		}

		@Override
		public Optional<LiquidityHold> findByAccountAndId(UUID accountId, UUID holdId) {
			return Optional.ofNullable(rows.get(holdId));
		}

		@Override
		public Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId) {
			return findByAccountAndId(accountId, holdId);
		}

		@Override
		public void insert(LiquidityHold hold) {
			rows.put(hold.id(), hold);
		}

		@Override
		public Optional<LiquidityHold> supersedeIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
			return Optional.empty();
		}

		@Override
		public Optional<LiquidityHold> releaseIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
			return Optional.empty();
		}

		@Override
		public List<LiquidityHold> findExpiredUnended(Instant asOf, int maximumRecords) {
			return rows.values().stream()
				.filter(row -> row.canFinalizeExpiryAt(asOf))
				.sorted(Comparator.comparing(LiquidityHold::expiresAt).thenComparing(LiquidityHold::id))
				.limit(maximumRecords)
				.toList();
		}

		@Override
		public Optional<LiquidityHold> expireIfVersion(
			UUID accountId, UUID holdId, int expectedVersion, Instant asOf, Instant finalizedAt) {
			LiquidityHold current = rows.get(holdId);
			if (current == null || current.version() != expectedVersion || !current.canFinalizeExpiryAt(asOf)) {
				return Optional.empty();
			}
			LiquidityHold finalized = current.finalizeExpiry(asOf, finalizedAt);
			rows.put(holdId, finalized);
			updateCount++;
			return Optional.of(finalized);
		}

		private LiquidityHold current() {
			return rows.values().iterator().next();
		}
	}

	private static final class InMemoryAccountStore implements AccountStore {
		@Override
		public void insert(app.ziji.account.domain.Account account) {
		}

		@Override
		public Optional<app.ziji.account.domain.Account> findById(UUID accountId) {
			return Optional.empty();
		}

		@Override
		public Optional<app.ziji.account.domain.Account> findByIdForUpdate(UUID accountId) {
			// 单元测试只验证最终化编排；真实 PostgreSQL 锁序由集成测试覆盖。
			return Optional.empty();
		}
	}
}
