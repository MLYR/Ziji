package app.ziji.ledger.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.ziji.ledger.application.AccountBalanceSnapshot;
import app.ziji.ledger.application.BalanceProjectionStore;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL 的余额快照适配器；只从已入账分录重建，不修改 Transaction 或 LedgerEntry 事实。 */
@Repository
public class PostgresBalanceProjectionStore implements BalanceProjectionStore {

	private final JdbcTemplate jdbc;

	public PostgresBalanceProjectionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<AccountBalanceSnapshot> aggregatePostedEntries() {
		try {
			return jdbc.query("""
				WITH daily_movements AS (
					SELECT e.ledger_account_id, e.business_date, e.currency,
						SUM(CASE
							WHEN la.account_nature IN ('LIABILITY', 'INCOME', 'EQUITY')
								THEN CASE WHEN e.direction = 'C' THEN e.amount ELSE -e.amount END
							ELSE CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END
						END) AS movement
					FROM ledger_entries e
					JOIN transactions t ON t.id = e.transaction_id
					JOIN ledger_accounts la ON la.id = e.ledger_account_id
					WHERE t.posted_at IS NOT NULL
					GROUP BY e.ledger_account_id, e.business_date, e.currency
				)
				SELECT ledger_account_id, business_date, currency,
					SUM(movement) OVER (
						PARTITION BY ledger_account_id, currency
						ORDER BY business_date
						ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS balance
				FROM daily_movements
				ORDER BY ledger_account_id, currency, business_date
				""", (result, rowNumber) -> new AccountBalanceSnapshot(
				result.getObject("ledger_account_id", java.util.UUID.class),
				result.getObject("business_date", java.time.LocalDate.class),
				new Money(result.getBigDecimal("balance"), CurrencyCode.fromCode(result.getString("currency")))));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public List<AccountBalanceSnapshot> readSnapshots() {
		try {
			return jdbc.query("""
				SELECT ledger_account_id, business_date, balance, currency
				FROM account_balance_snapshots
				ORDER BY ledger_account_id, currency, business_date
				""", (result, rowNumber) -> new AccountBalanceSnapshot(
				result.getObject("ledger_account_id", java.util.UUID.class),
				result.getObject("business_date", java.time.LocalDate.class),
				new Money(result.getBigDecimal("balance"), CurrencyCode.fromCode(result.getString("currency")))));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public void replaceSnapshots(List<AccountBalanceSnapshot> snapshots, Instant calculatedAt) {
		if (snapshots == null || calculatedAt == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("余额快照写入参数无效。"));
		}
		try {
			jdbc.update("DELETE FROM account_balance_snapshots");
			List<Object[]> values = new ArrayList<>(snapshots.size());
			for (AccountBalanceSnapshot snapshot : snapshots) {
				values.add(new Object[] {
					snapshot.ledgerAccountId(), snapshot.businessDate(), snapshot.balance().amount(),
					snapshot.balance().currency().name(), Timestamp.from(calculatedAt)
				});
			}
			if (!values.isEmpty()) {
				// Ledger 没有全局事实 sequence；全量事实重建以 0 标记，不能错误借用按接收者分片的 change_log sequence。
				jdbc.batchUpdate("""
					INSERT INTO account_balance_snapshots (
						ledger_account_id, business_date, balance, currency, as_of_change_sequence, calculated_at)
					VALUES (?, ?, ?, ?, 0, ?)
					""", values);
			}
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private static LedgerPersistenceException persistence(Throwable exception) {
		if (exception instanceof LedgerPersistenceException persistence) {
			return persistence;
		}
		return new LedgerPersistenceException(exception);
	}
}
