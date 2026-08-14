package app.ziji.ledger.domain;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 账务领域模型的结构、状态和逐币种平衡基线测试。 */
class LedgerDomainModelTests {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 14);
	private static final Instant BUSINESS_AT = Instant.parse("2026-08-14T00:30:00Z");
	private static final Instant POSTED_AT = Instant.parse("2026-08-14T01:00:00Z");
	private static final UUID LEDGER_ACCOUNT_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000100");

	@Test
	void moneyRejectsNullAndUnsupportedCurrencyAndHasNoPrimitiveConstructor() {
		assertThrows(LedgerDomainException.class,
			() -> new Money(null, CurrencyCode.CNY));
		assertThrows(LedgerDomainException.class,
			() -> new Money(BigDecimal.ONE, null));
		assertThrows(LedgerDomainException.class, () -> CurrencyCode.fromCode("GBP"));
		assertFalse(Arrays.stream(Money.class.getDeclaredConstructors())
			.anyMatch(LedgerDomainModelTests::hasPrimitiveFloatingPointParameter));
	}

	@Test
	void ledgerEntryRejectsNonPositiveAmountNullIdsAndInvalidSequence() {
		UUID transactionId = UUID.randomUUID();
		Money zero = money("0", CurrencyCode.CNY);
		Money negative = money("-1", CurrencyCode.CNY);

		assertThrows(LedgerDomainException.class,
			() -> entry(null, transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE));
		assertThrows(LedgerDomainException.class,
			() -> entry(UUID.randomUUID(), null, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE));
		assertThrows(LedgerDomainException.class,
			() -> entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 0,
				LedgerDirection.DEBIT, money("1", CurrencyCode.CNY), BUSINESS_DATE));
		assertThrows(LedgerDomainException.class,
			() -> entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1,
				LedgerDirection.DEBIT, zero, BUSINESS_DATE));
		assertThrows(LedgerDomainException.class,
			() -> entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1,
				LedgerDirection.DEBIT, negative, BUSINESS_DATE));
	}

	@Test
	void ledgerEntryRejectsNullLedgerAccountId() {
		UUID transactionId = UUID.randomUUID();

		assertThrows(LedgerDomainException.class,
			() -> entry(UUID.randomUUID(), transactionId, null, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE));
	}

	@Test
	void transactionRejectsEntryWithDifferentTransactionId() {
		UUID transactionId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID entryTransactionId = UUID.fromString("00000000-0000-0000-0000-000000000202");
		UUID entryId = UUID.fromString("00000000-0000-0000-0000-000000000203");
		assertNotEquals(transactionId, entryTransactionId);
		LedgerEntry entry = entry(entryId, entryTransactionId, LEDGER_ACCOUNT_ID, 1,
			LedgerDirection.DEBIT, money("1", CurrencyCode.CNY), BUSINESS_DATE);

		assertThrows(LedgerDomainException.class, () -> draft(transactionId, List.of(entry)));
	}

	@Test
	void transactionRejectsEntryWithDifferentBusinessDate() {
		UUID transactionId = UUID.randomUUID();
		LedgerEntry entry = entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1,
			LedgerDirection.DEBIT, money("1", CurrencyCode.CNY), BUSINESS_DATE.plusDays(1));

		assertThrows(LedgerDomainException.class, () -> draft(transactionId, List.of(entry)));
	}

	@Test
	void transactionRejectsDuplicateSequenceNumbers() {
		UUID transactionId = UUID.randomUUID();
		List<LedgerEntry> entries = List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.CREDIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE));

		assertThrows(LedgerDomainException.class, () -> draft(transactionId, entries));
	}

	@Test
	void transactionValidatesPostedAtStatusMatrix() {
		assertDoesNotThrow(() -> transaction(UUID.randomUUID(), TransactionStatus.DRAFT,
			null, null, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionStatus.DRAFT,
				POSTED_AT, null, null, 1));
		assertDoesNotThrow(() -> transaction(UUID.randomUUID(), TransactionStatus.DISCARDED,
			null, null, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionStatus.DISCARDED,
				POSTED_AT, null, null, 1));
		assertDoesNotThrow(() -> transaction(UUID.randomUUID(), TransactionStatus.POSTED,
			POSTED_AT, null, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionStatus.POSTED,
				null, null, null, 1));
		assertDoesNotThrow(() -> transaction(UUID.randomUUID(), TransactionStatus.REVERSED,
			POSTED_AT, null, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionStatus.REVERSED,
				null, null, null, 1));
		assertDoesNotThrow(() -> transaction(UUID.randomUUID(), TransactionStatus.SUPERSEDED,
			POSTED_AT, null, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionStatus.SUPERSEDED,
				null, null, null, 1));
	}

	@Test
	void transactionAcceptsAsiaShanghaiAndRejectsInvalidTimezone() {
		UUID validTransactionId = UUID.randomUUID();
		Transaction transaction = assertDoesNotThrow(() -> transaction(validTransactionId,
			TransactionType.EXPENSE, TransactionStatus.DRAFT, null, null, null, 1,
			"Asia/Shanghai"));

		assertEquals(ZoneId.of("Asia/Shanghai"), transaction.timezone());
		LedgerDomainException exception = assertThrows(LedgerDomainException.class,
			() -> transaction(UUID.randomUUID(), TransactionType.EXPENSE,
				TransactionStatus.DRAFT, null, null, null, 1, "Not/A/Timezone"));
		assertEquals("交易时区格式无效。", exception.getMessage());
	}

	@Test
	void transactionValidatesReversalAndVersionRelations() {
		UUID transactionId = UUID.randomUUID();
		UUID relatedId = UUID.randomUUID();
		assertThrows(LedgerDomainException.class,
			() -> transaction(transactionId, TransactionType.EXPENSE, TransactionStatus.DRAFT,
				null, relatedId, null, 1));
		assertDoesNotThrow(() -> transaction(transactionId, TransactionType.REVERSAL,
			TransactionStatus.DRAFT, null, relatedId, null, 1));
		assertThrows(LedgerDomainException.class,
			() -> transaction(transactionId, TransactionType.EXPENSE, TransactionStatus.DRAFT,
				null, null, UUID.randomUUID(), 1));
		assertDoesNotThrow(() -> transaction(transactionId, TransactionType.EXPENSE,
			TransactionStatus.DRAFT, null, null, UUID.randomUUID(), 2));
	}

	@Test
	void postingServiceAcceptsBalancedSingleCurrencyTransaction() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE)));

		assertDoesNotThrow(() -> new PostingService().validate(transaction));
	}

	@Test
	void postingServiceRejectsTransactionWithFewerThanTwoEntries() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE)));

		assertThrows(LedgerDomainException.class,
			() -> new PostingService().validate(transaction));
	}

	@Test
	void postingServiceRejectsUnbalancedSingleCurrencyTransaction() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("10.01", CurrencyCode.CNY), BUSINESS_DATE)));

		assertThrows(LedgerDomainException.class,
			() -> new PostingService().validate(transaction));
	}

	@Test
	void postingServiceAcceptsEachBalancedCurrencyIndependently() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 3, LedgerDirection.DEBIT,
				money("5.00", CurrencyCode.USD), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 4, LedgerDirection.CREDIT,
				money("5.00", CurrencyCode.USD), BUSINESS_DATE)));

		assertDoesNotThrow(() -> new PostingService().validate(transaction));
	}

	@Test
	void postingServiceRejectsEqualCrossCurrencyTotalsWhenEachCurrencyIsUnbalanced() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("10.00", CurrencyCode.USD), BUSINESS_DATE)));

		assertThrows(LedgerDomainException.class,
			() -> new PostingService().validate(transaction));
	}

	@Test
	void postingServiceUsesNumericComparisonInsteadOfScaleComparison() {
		UUID transactionId = UUID.randomUUID();
		Transaction transaction = draft(transactionId, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("10.0", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("10.00", CurrencyCode.CNY), BUSINESS_DATE)));

		assertDoesNotThrow(() -> new PostingService().validate(transaction));
	}

	@Test
	void transactionEntriesAreCopiedAndExternallyImmutable() {
		UUID transactionId = UUID.randomUUID();
		List<LedgerEntry> sourceEntries = new ArrayList<>(List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE),
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 2, LedgerDirection.CREDIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE)));
		Transaction transaction = draft(transactionId, sourceEntries);
		sourceEntries.clear();

		assertEquals(2, transaction.entries().size());
		assertThrows(UnsupportedOperationException.class,
			() -> transaction.entries().add(entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 3,
				LedgerDirection.DEBIT, money("1", CurrencyCode.CNY), BUSINESS_DATE)));
	}

	private static boolean hasPrimitiveFloatingPointParameter(Constructor<?> constructor) {
		return Arrays.stream(constructor.getParameterTypes())
			.anyMatch(type -> type == double.class || type == float.class);
	}

	private static Money money(String value, CurrencyCode currency) {
		return new Money(new BigDecimal(value), currency);
	}

	private static LedgerEntry entry(
		UUID entryId,
		UUID transactionId,
		UUID ledgerAccountId,
		int sequenceNo,
		LedgerDirection direction,
		Money amount,
		LocalDate businessDate) {
		return new LedgerEntry(entryId, transactionId, ledgerAccountId, sequenceNo,
			direction, amount, businessDate);
	}

	private static Transaction draft(UUID transactionId, List<LedgerEntry> entries) {
		return new Transaction(transactionId, TransactionType.EXPENSE, TransactionStatus.DRAFT,
			BUSINESS_AT, BUSINESS_DATE, "Asia/Shanghai", TransactionSource.MANUAL,
			transactionId, null, null, 1, null, entries);
	}

	private static Transaction transaction(
		UUID transactionId,
		TransactionStatus status,
		Instant postedAt,
		UUID reversalOfId,
		UUID previousVersionId,
		int versionNo) {
		return transaction(transactionId, TransactionType.EXPENSE, status, postedAt,
			reversalOfId, previousVersionId, versionNo);
	}

	private static Transaction transaction(
		UUID transactionId,
		TransactionType type,
		TransactionStatus status,
		Instant postedAt,
		UUID reversalOfId,
		UUID previousVersionId,
		int versionNo) {
		return transaction(transactionId, type, status, postedAt, reversalOfId,
			previousVersionId, versionNo, "Asia/Shanghai");
	}

	private static Transaction transaction(
		UUID transactionId,
		TransactionType type,
		TransactionStatus status,
		Instant postedAt,
		UUID reversalOfId,
		UUID previousVersionId,
		int versionNo,
		String timezone) {
		return new Transaction(transactionId, type, status, BUSINESS_AT, BUSINESS_DATE,
			timezone, TransactionSource.MANUAL, transactionId, previousVersionId,
			reversalOfId, versionNo, postedAt, List.of(
			entry(UUID.randomUUID(), transactionId, LEDGER_ACCOUNT_ID, 1, LedgerDirection.DEBIT,
				money("1", CurrencyCode.CNY), BUSINESS_DATE)));
	}
}
