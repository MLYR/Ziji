package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.application.AccountBalanceSnapshot;
import app.ziji.ledger.application.BalanceProjectionRebuildResult;
import app.ziji.ledger.application.BalanceProjectionService;
import app.ziji.ledger.application.BalanceProjectionStore;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.Money;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** PostgreSQL 17.6 验证余额快照全量重建、正常余额方向及失败回滚。 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerBalanceProjectionPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-17T04:00:00Z");
	private static final LocalDate FIRST_DATE = LocalDate.of(2026, 8, 10);
	private static final LocalDate SECOND_DATE = LocalDate.of(2026, 8, 11);
	private static final LocalDate THIRD_DATE = LocalDate.of(2026, 8, 12);

	@Autowired
	private BalanceProjectionService projections;

	@Autowired
	private BalanceProjectionStore projectionStore;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void rebuildsDailyBalancesForNormalDirectionsCurrenciesAndRepeatedRuns() {
		Fixture fixture = fixture();
		post(fixture.userId, FIRST_DATE, List.of(
			entry(fixture.cnyAssetId, LedgerDirection.DEBIT, "100.00", CurrencyCode.CNY),
			entry(fixture.cnyEquityId, LedgerDirection.CREDIT, "100.00", CurrencyCode.CNY)));
		post(fixture.userId, SECOND_DATE, List.of(
			entry(fixture.secondCnyAssetId, LedgerDirection.DEBIT, "300.00", CurrencyCode.CNY),
			entry(fixture.cnyLiabilityId, LedgerDirection.CREDIT, "300.00", CurrencyCode.CNY)));
		post(fixture.userId, THIRD_DATE, List.of(
			entry(fixture.cnyAssetId, LedgerDirection.CREDIT, "30.00", CurrencyCode.CNY),
			entry(fixture.cnyEquityId, LedgerDirection.DEBIT, "30.00", CurrencyCode.CNY)));
		post(fixture.userId, SECOND_DATE, List.of(
			entry(fixture.usdAssetId, LedgerDirection.DEBIT, "40.00", CurrencyCode.USD),
			entry(fixture.usdEquityId, LedgerDirection.CREDIT, "40.00", CurrencyCode.USD)));
		post(fixture.userId, THIRD_DATE, List.of(
			entry(fixture.cnyExpenseId, LedgerDirection.DEBIT, "5.00", CurrencyCode.CNY),
			entry(fixture.secondCnyAssetId, LedgerDirection.CREDIT, "5.00", CurrencyCode.CNY)));

		BalanceProjectionRebuildResult first = projections.rebuildAll();

		assertEquals(0, first.differenceCount());
		assertBalance(fixture.cnyAssetId, FIRST_DATE, "100.00");
		assertBalance(fixture.cnyAssetId, THIRD_DATE, "70.00");
		assertBalance(fixture.cnyLiabilityId, SECOND_DATE, "300.00");
		assertBalance(fixture.usdAssetId, SECOND_DATE, "40.00");
		assertBalance(fixture.usdEquityId, SECOND_DATE, "40.00");
		assertBalance(fixture.cnyExpenseId, THIRD_DATE, "5.00");

		BalanceProjectionRebuildResult repeated = projections.rebuildAll();
		assertEquals(0, repeated.previousDifferenceCount());
		assertEquals(0, repeated.differenceCount());

		jdbc.update("DELETE FROM account_balance_snapshots");
		BalanceProjectionRebuildResult afterDelete = projections.rebuildAll();
		assertEquals(first.snapshotCount(), afterDelete.previousDifferenceCount());
		assertEquals(0, afterDelete.differenceCount());
		assertBalance(fixture.cnyAssetId, THIRD_DATE, "70.00");
	}

	@Test
	void failedReplacementRollsBackInsteadOfLeavingPartialSnapshots() {
		Fixture fixture = fixture();
		post(fixture.userId, FIRST_DATE, List.of(
			entry(fixture.cnyAssetId, LedgerDirection.DEBIT, "10.00", CurrencyCode.CNY),
			entry(fixture.cnyEquityId, LedgerDirection.CREDIT, "10.00", CurrencyCode.CNY)));
		projections.rebuildAll();
		jdbc.update("UPDATE account_balance_snapshots SET balance = 9.00 WHERE ledger_account_id = ?", fixture.cnyAssetId);

		BalanceProjectionStore failingAfterWrite = new BalanceProjectionStore() {
			@Override
			public List<AccountBalanceSnapshot> aggregatePostedEntries() {
				return projectionStore.aggregatePostedEntries();
			}

			@Override
			public List<AccountBalanceSnapshot> readSnapshots() {
				return projectionStore.readSnapshots();
			}

			@Override
			public void replaceSnapshots(List<AccountBalanceSnapshot> snapshots, Instant calculatedAt) {
				projectionStore.replaceSnapshots(snapshots, calculatedAt);
				// 故障发生在删除并批量写入之后，验证最外层事务仍会恢复旧投影。
				throw new LedgerPersistenceException(new IllegalStateException("测试投影写入失败。"));
			}
		};
		BalanceProjectionService failing = new BalanceProjectionService(transactions, failingAfterWrite,
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThrows(LedgerPersistenceException.class, failing::rebuildAll);
		assertBalance(fixture.cnyAssetId, FIRST_DATE, "9.00");
		assertBalance(fixture.cnyEquityId, FIRST_DATE, "10.00");
	}

	private Fixture fixture() {
		Fixture fixture = new Fixture(UUID.randomUUID());
		transactions.required(() -> {
			insertUser(fixture.userId);
			insertLedger(fixture.cnyAssetId, fixture.userId, "ASSET_CNY", "ASSET", "CNY");
			insertLedger(fixture.secondCnyAssetId, fixture.userId, "ASSET_CNY_2", "ASSET", "CNY");
			insertLedger(fixture.cnyLiabilityId, fixture.userId, "LIABILITY_CNY", "LIABILITY", "CNY");
			insertLedger(fixture.cnyEquityId, fixture.userId, "EQUITY_CNY", "EQUITY", "CNY");
			insertLedger(fixture.cnyExpenseId, fixture.userId, "EXPENSE_CNY", "EXPENSE", "CNY");
			insertLedger(fixture.usdAssetId, fixture.userId, "ASSET_USD", "ASSET", "USD");
			insertLedger(fixture.usdEquityId, fixture.userId, "EQUITY_USD", "EQUITY", "USD");
		});
		return fixture;
	}

	private void insertUser(UUID userId) {
		jdbc.update("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '余额投影测试用户', 'Asia/Shanghai', 'CNY',
				'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@example.test", userId + "@example.test", Timestamp.from(NOW),
			Timestamp.from(NOW), Timestamp.from(NOW));
	}

	private void insertLedger(UUID ledgerId, UUID userId, String code, String nature, String currency) {
		jdbc.update("""
			INSERT INTO ledger_accounts (
				id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', ?, ?, 'ACTIVE', ?)
			""", ledgerId, userId, code, nature, currency, Timestamp.from(NOW));
	}

	private void post(UUID userId, LocalDate businessDate, List<Entry> entries) {
		UUID transactionId = UUID.randomUUID();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO transactions (
					id, transaction_type, status, business_at, business_date, timezone, source,
					root_transaction_id, version_no, posted_at, created_by, updated_by, created_at, updated_at, entity_version)
				VALUES (?, 'OPENING', 'DRAFT', ?, ?, 'Asia/Shanghai', 'ADJUSTMENT', ?, 1, NULL, ?, ?, ?, ?, 1)
				""", transactionId, Timestamp.from(NOW), businessDate, transactionId, userId,
				userId, Timestamp.from(NOW), Timestamp.from(NOW));
			int sequence = 1;
			for (Entry entry : entries) {
				jdbc.update("""
					INSERT INTO ledger_entries (
						id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), transactionId, entry.ledgerAccountId, sequence++, entry.direction == LedgerDirection.DEBIT ? "D" : "C",
					entry.amount.amount(), entry.amount.currency().name(), businessDate, Timestamp.from(NOW));
			}
			jdbc.update("""
				UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?
				""", Timestamp.from(NOW), Timestamp.from(NOW), transactionId);
		});
	}

	private void assertBalance(UUID ledgerAccountId, LocalDate businessDate, String expected) {
		BigDecimal actual = jdbc.queryForObject("""
			SELECT balance FROM account_balance_snapshots
			WHERE ledger_account_id = ? AND business_date = ?
			""", BigDecimal.class, ledgerAccountId, businessDate);
		assertEquals(0, new BigDecimal(expected).compareTo(actual));
	}

	private static Entry entry(UUID ledgerAccountId, LedgerDirection direction, String amount, CurrencyCode currency) {
		return new Entry(ledgerAccountId, direction, new Money(new BigDecimal(amount), currency));
	}

	private record Entry(UUID ledgerAccountId, LedgerDirection direction, Money amount) {
	}

	private static final class Fixture {
		private final UUID userId;
		private final UUID cnyAssetId = UUID.randomUUID();
		private final UUID secondCnyAssetId = UUID.randomUUID();
		private final UUID cnyLiabilityId = UUID.randomUUID();
		private final UUID cnyEquityId = UUID.randomUUID();
		private final UUID cnyExpenseId = UUID.randomUUID();
		private final UUID usdAssetId = UUID.randomUUID();
		private final UUID usdEquityId = UUID.randomUUID();

		private Fixture(UUID userId) {
			this.userId = userId;
		}
	}
}
