package app.ziji.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LiquidityHold 领域规则：精度、修订链和查询时点生命周期均不依赖 Spring 或数据库。 */
class LiquidityHoldDomainTests {

	private static final Instant NOW = Instant.parse("2026-08-15T03:04:05Z");

	@Test
	void supportsAllThreeTypesAndFixesManualReasonToNote() {
		for (LiquidityHoldType type : LiquidityHoldType.values()) {
			LiquidityHold hold = root(type, new BigDecimal("10.00"), AccountCurrency.CNY, NOW, NOW.plusSeconds(60), "原因");
			assertEquals(type, hold.type());
			assertEquals("MANUAL", hold.source().name());
			assertEquals("原因", hold.note());
			assertEquals(LiquidityHoldStatus.ACTIVE, hold.statusAt(NOW));
		}
	}

	@Test
	void enforcesPositiveMoneyAndCurrencyMinorUnitsWithoutRounding() {
		assertThrows(AccountDomainException.class, () -> root(LiquidityHoldType.FROZEN, BigDecimal.ZERO, AccountCurrency.CNY, NOW, null, "r"));
		assertThrows(AccountDomainException.class, () -> root(LiquidityHoldType.FROZEN, new BigDecimal("1.001"), AccountCurrency.CNY, NOW, null, "r"));
		assertThrows(AccountDomainException.class, () -> root(LiquidityHoldType.FROZEN, new BigDecimal("1.1"), AccountCurrency.JPY, NOW, null, "r"));
		assertThrows(AccountDomainException.class, () -> root(LiquidityHoldType.FROZEN,
			new BigDecimal("10000000000000000000000"), AccountCurrency.CNY, NOW, null, "r"));
		assertEquals(new BigDecimal("1.00"), root(LiquidityHoldType.FROZEN, new BigDecimal("1.00"), AccountCurrency.USD, NOW, null, "r").amount());
	}

	@Test
	void appliesTheSameIntegerAndMinorUnitBoundaryToEverySupportedCurrency() {
		BigDecimal maximumTwoDecimal = new BigDecimal("9999999999999999999999.99");
		BigDecimal maximumInteger = new BigDecimal("9999999999999999999999");
		for (AccountCurrency currency : java.util.List.of(
			AccountCurrency.CNY, AccountCurrency.USD, AccountCurrency.HKD, AccountCurrency.EUR)) {
			assertEquals(maximumTwoDecimal, root(LiquidityHoldType.FROZEN, maximumTwoDecimal, currency, NOW, null, "r").amount());
			assertThrows(AccountDomainException.class,
				() -> root(LiquidityHoldType.FROZEN, new BigDecimal("1.001"), currency, NOW, null, "r"));
		}
		assertEquals(maximumInteger,
			root(LiquidityHoldType.FROZEN, maximumInteger, AccountCurrency.JPY, NOW, null, "r").amount());
		// JPY 禁止非零最小单位；尾随零仍是同一个整数金额，不能错误拒绝。
		assertEquals(new BigDecimal("1.00"),
			root(LiquidityHoldType.FROZEN, new BigDecimal("1.00"), AccountCurrency.JPY, NOW, null, "r").amount());
		assertEquals(new BigDecimal("1E+21"),
			root(LiquidityHoldType.FROZEN, new BigDecimal("1E+21"), AccountCurrency.CNY, NOW, null, "r").amount());
		assertThrows(AccountDomainException.class,
			() -> root(LiquidityHoldType.FROZEN, new BigDecimal("1E+22"), AccountCurrency.CNY, NOW, null, "r"));
	}

	@Test
	void mapsPendingActiveExpiredAndTerminalStatesAtAsOf() {
		Instant future = NOW.plusSeconds(100);
		LiquidityHold pending = root(LiquidityHoldType.RESERVED, new BigDecimal("1"), AccountCurrency.JPY, future, null, "r");
		assertEquals(LiquidityHoldStatus.PENDING, pending.statusAt(NOW));
		assertEquals(LiquidityHoldStatus.ACTIVE, pending.statusAt(future));

		LiquidityHold expiring = root(LiquidityHoldType.RESERVED, new BigDecimal("1"), AccountCurrency.JPY, NOW, future, "r");
		assertEquals(LiquidityHoldStatus.EXPIRED, expiring.statusAt(future));
		assertFalse(expiring.isOperableAt(future));

		LiquidityHold released = LiquidityHold.restore(expiring.id(), expiring.accountId(), expiring.rootHoldId(), null, 1,
			 expiring.type(), expiring.amount(), expiring.currency(), expiring.effectiveAt(), expiring.expiresAt(), future,
			 expiring.source(), expiring.note(), future, LiquidityHoldEndReason.RELEASED, expiring.createdBy(), expiring.createdAt(), future, 2);
		assertEquals(LiquidityHoldStatus.RELEASED, released.statusAt(future));
	}

	@Test
	void revisionCarriesRootAndPreviousAndCanChangeType() {
		LiquidityHold root = root(LiquidityHoldType.FROZEN, new BigDecimal("10.00"), AccountCurrency.CNY, NOW, null, "old");
		LiquidityHold revised = LiquidityHold.createRevision(UUID.randomUUID(), root, LiquidityHoldType.IN_TRANSIT,
			new BigDecimal("12.00"), AccountCurrency.CNY, NOW, null, "new", root.createdBy(), NOW);
		assertEquals(root.rootHoldId(), revised.rootHoldId());
		assertEquals(root.id(), revised.previousRevisionId());
		assertEquals(2, revised.revisionNo());
		assertEquals(LiquidityHoldType.IN_TRANSIT, revised.type());
		assertTrue(revised.isOperableAt(NOW));
	}

	@Test
	void expiresAtMustBeAfterEffectiveAtAndTerminalVersionsAreNotOperable() {
		assertThrows(AccountDomainException.class, () -> root(LiquidityHoldType.FROZEN, new BigDecimal("1"), AccountCurrency.JPY, NOW, NOW, "r"));
		LiquidityHold expired = root(LiquidityHoldType.FROZEN, new BigDecimal("1"), AccountCurrency.JPY, NOW.minusSeconds(10), NOW, "r");
		assertFalse(expired.isOperableAt(NOW));
		assertThrows(AccountDomainException.class, () -> LiquidityHold.restore(
			expired.id(), expired.accountId(), expired.rootHoldId(), null, 1, expired.type(), expired.amount(),
			expired.currency(), expired.effectiveAt(), expired.expiresAt(), null, expired.source(), expired.note(),
			NOW, LiquidityHoldEndReason.RELEASED, expired.createdBy(), expired.createdAt(), NOW, 2));
		assertThrows(AccountDomainException.class, () -> LiquidityHold.restore(
			expired.id(), expired.accountId(), expired.rootHoldId(), null, 1, expired.type(), expired.amount(),
			expired.currency(), expired.effectiveAt(), expired.expiresAt(), NOW.minusSeconds(1), expired.source(), expired.note(),
			NOW, LiquidityHoldEndReason.RELEASED, expired.createdBy(), expired.createdAt(), NOW, 2));
		assertThrows(AccountDomainException.class, () -> LiquidityHold.restore(
			expired.id(), expired.accountId(), expired.rootHoldId(), null, 1, expired.type(), expired.amount(),
			expired.currency(), expired.effectiveAt(), expired.expiresAt(), NOW, expired.source(), expired.note(),
			NOW, LiquidityHoldEndReason.SUPERSEDED, expired.createdBy(), expired.createdAt(), NOW, 2));
		assertThrows(AccountDomainException.class, () -> LiquidityHold.restore(
			expired.id(), expired.accountId(), expired.rootHoldId(), null, 1, expired.type(), expired.amount(),
			expired.currency(), expired.effectiveAt(), expired.expiresAt(), NOW, expired.source(), expired.note(),
			NOW, LiquidityHoldEndReason.EXPIRED, expired.createdBy(), expired.createdAt(), NOW, 2));
	}

	private static LiquidityHold root(
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		Instant expiresAt,
		String reason) {
		return LiquidityHold.createRoot(UUID.randomUUID(), UUID.randomUUID(), type, amount, currency, effectiveAt, expiresAt,
			reason, UUID.randomUUID(), NOW);
	}
}
