package app.ziji.ledger.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ledger_accounts 与事实余额查询适配器；不写余额快照或其他投影事实。 */
@Repository
public class PostgresLedgerAccountStore implements LedgerAccountStore {

	private final JdbcTemplate jdbc;

	// 指定时点按已入账事实的业务日及交易固化时区截止，posted_at 仅用于确认事实已入账。
	private static final String BALANCE_AT_SQL = """
		WITH evaluation AS (SELECT CAST(? AS timestamptz) AS as_of)
		SELECT la.currency, la.account_nature,
			COALESCE(SUM(
				CASE
					WHEN t.posted_at IS NULL THEN 0
					WHEN t.business_date > CAST(evaluation.as_of AT TIME ZONE t.timezone AS date) THEN 0
					WHEN e.direction = 'D' AND la.account_nature <> 'LIABILITY' THEN e.amount
					WHEN e.direction = 'C' AND la.account_nature = 'LIABILITY' THEN e.amount
					WHEN e.direction = 'C' AND la.account_nature <> 'LIABILITY' THEN -e.amount
					WHEN e.direction = 'D' AND la.account_nature = 'LIABILITY' THEN -e.amount
					ELSE 0
				END), 0) AS balance,
			COUNT(*) FILTER (
				WHERE t.posted_at IS NOT NULL
					AND t.business_date <= CAST(evaluation.as_of AT TIME ZONE t.timezone AS date)
					AND e.currency <> la.currency) AS currency_mismatch_count,
			COUNT(*) FILTER (
				WHERE t.posted_at IS NOT NULL
					AND t.business_date <= CAST(evaluation.as_of AT TIME ZONE t.timezone AS date)
					AND (
					(la.currency = 'JPY' AND e.amount <> trunc(e.amount))
					OR (la.currency <> 'JPY' AND e.amount <> round(e.amount, 2)))) AS precision_error_count
		FROM ledger_accounts la
		CROSS JOIN evaluation
		LEFT JOIN ledger_entries e ON e.ledger_account_id = la.id
		LEFT JOIN transactions t ON t.id = e.transaction_id
		WHERE la.id = ?
		GROUP BY la.currency, la.account_nature
		""";

	public PostgresLedgerAccountStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<LedgerAccountReference> findById(UUID ledgerAccountId) {
		if (ledgerAccountId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, visible_account_id, owner_user_id, code, ledger_role,
					account_nature, currency, status
				FROM ledger_accounts
				WHERE id = ?
				""",
				result -> result.next() ? Optional.of(toReference(result)) : Optional.empty(),
				ledgerAccountId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<LedgerAccountReference> findPrimaryForVisibleAccount(UUID accountId) {
		if (accountId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, visible_account_id, owner_user_id, code, ledger_role,
					account_nature, currency, status
				FROM ledger_accounts
				WHERE visible_account_id = ? AND ledger_role = 'PRIMARY'
				""",
				result -> result.next() ? Optional.of(toReference(result)) : Optional.empty(),
				accountId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public LedgerAccountReference ensureCategorySystemAccount(
		UUID ownerUserId, UUID categoryId, LedgerAccountNature nature, CurrencyCode currency) {
		if (ownerUserId == null || categoryId == null || currency == null
			|| nature != LedgerAccountNature.INCOME && nature != LedgerAccountNature.EXPENSE) {
			throw new LedgerPersistenceException(new IllegalArgumentException("分类系统科目参数无效。"));
		}
		String code = (nature == LedgerAccountNature.INCOME ? "INCOME_CATEGORY_" : "EXPENSE_CATEGORY_") + categoryId;
		try {
			// 同一账务事务以内置唯一键收敛并发创建；调用者只得到受控语义，不接触 SQL 或科目 ID。
			jdbc.update("""
				INSERT INTO ledger_accounts (
					id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, NULL, ?, ?, 'SYSTEM', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
				ON CONFLICT (owner_user_id, code, currency) WHERE visible_account_id IS NULL DO NOTHING
				""", UUID.randomUUID(), ownerUserId, code, nature.name(), currency.name());
			return jdbc.query("""
				SELECT id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status
				FROM ledger_accounts
				WHERE owner_user_id = ? AND code = ? AND currency = ? AND ledger_role = 'SYSTEM'
				""", result -> {
				if (!result.next()) throw new LedgerPersistenceException(new IllegalStateException("分类系统科目未创建。"));
				return toReference(result);
			}, ownerUserId, code, currency.name());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public LedgerAccountReference ensureOpeningEquityAccount(UUID ownerUserId, CurrencyCode currency) {
		if (ownerUserId == null || currency == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("期初权益科目参数无效。"));
		}
		try {
			// 用户级唯一键收敛并发创建，避免账户模块接触内部科目 ID 或 SQL。
			jdbc.update("""
				INSERT INTO ledger_accounts (
					id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, NULL, ?, 'EQUITY_OPENING_BALANCE', 'SYSTEM', 'EQUITY', ?, 'ACTIVE', CURRENT_TIMESTAMP)
				ON CONFLICT (owner_user_id, code, currency) WHERE visible_account_id IS NULL DO NOTHING
				""", UUID.randomUUID(), ownerUserId, currency.name());
			return jdbc.query("""
				SELECT id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status
				FROM ledger_accounts
				WHERE owner_user_id = ? AND code = 'EQUITY_OPENING_BALANCE' AND currency = ? AND ledger_role = 'SYSTEM'
				""", result -> {
				if (!result.next()) throw new LedgerPersistenceException(new IllegalStateException("期初权益科目未创建。"));
				return toReference(result);
			}, ownerUserId, currency.name());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public LedgerAccountReference ensureBalanceAdjustmentEquityAccount(UUID ownerUserId, CurrencyCode currency) {
		if (ownerUserId == null || currency == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("余额调整权益科目参数无效。"));
		}
		try {
			// 复用既有用户级系统科目唯一键，HTTP/application 不接触内部科目 ID。
			jdbc.update("""
				INSERT INTO ledger_accounts (
					id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, NULL, ?, 'EQUITY_BALANCE_ADJUSTMENT', 'SYSTEM', 'EQUITY', ?, 'ACTIVE', CURRENT_TIMESTAMP)
				ON CONFLICT (owner_user_id, code, currency) WHERE visible_account_id IS NULL DO NOTHING
				""", UUID.randomUUID(), ownerUserId, currency.name());
			return jdbc.query("""
				SELECT id, visible_account_id, owner_user_id, code, ledger_role, account_nature, currency, status
				FROM ledger_accounts
				WHERE owner_user_id = ? AND code = 'EQUITY_BALANCE_ADJUSTMENT'
				  AND currency = ? AND ledger_role = 'SYSTEM'
				""", result -> {
				if (!result.next()) {
					throw new LedgerPersistenceException(new IllegalStateException("余额调整权益科目未创建。"));
				}
				return toReference(result);
			}, ownerUserId, currency.name());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Money currentBalance(UUID ledgerAccountId) {
		if (ledgerAccountId == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("账务科目不能为空。"));
		}
		try {
			// 余额调整先锁定主科目行，串行化 before/actual/difference 的计算。
			Integer locked = jdbc.queryForObject(
				"SELECT 1 FROM ledger_accounts WHERE id = ? FOR UPDATE",
				Integer.class,
				ledgerAccountId);
			if (locked == null) {
				throw new LedgerPersistenceException(new IllegalStateException("账务科目不存在。"));
			}
			return jdbc.query("""
				SELECT la.currency, la.account_nature,
					COALESCE(SUM(
						CASE
							WHEN t.posted_at IS NULL THEN 0
							WHEN e.direction = 'D' AND la.account_nature <> 'LIABILITY' THEN e.amount
							WHEN e.direction = 'C' AND la.account_nature = 'LIABILITY' THEN e.amount
							WHEN e.direction = 'C' AND la.account_nature <> 'LIABILITY' THEN -e.amount
							WHEN e.direction = 'D' AND la.account_nature = 'LIABILITY' THEN -e.amount
							ELSE 0
						END), 0) AS balance
				FROM ledger_accounts la
				LEFT JOIN ledger_entries e ON e.ledger_account_id = la.id
				LEFT JOIN transactions t ON t.id = e.transaction_id AND t.posted_at IS NOT NULL
				WHERE la.id = ?
				GROUP BY la.currency, la.account_nature
				""",
				result -> {
					if (!result.next()) {
						throw new LedgerPersistenceException(new IllegalStateException("账务科目不存在。"));
					}
					CurrencyCode currency = CurrencyCode.fromCode(result.getString("currency"));
					BigDecimal balance = result.getBigDecimal("balance");
					return new Money(balance, currency);
				},
				ledgerAccountId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Money balanceAt(UUID ledgerAccountId, Instant asOf) {
		if (ledgerAccountId == null || asOf == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("指定时点余额查询参数无效。"));
		}
		try {
			return jdbc.query(BALANCE_AT_SQL, result -> {
				if (!result.next()) {
					throw new LedgerPersistenceException(new IllegalStateException("账务科目不存在。"));
				}
				if (result.getLong("currency_mismatch_count") != 0
					|| result.getLong("precision_error_count") != 0) {
					throw new LedgerPersistenceException(new IllegalStateException("账务分录事实不一致。"));
				}
				CurrencyCode currency = CurrencyCode.fromCode(result.getString("currency"));
				return new Money(result.getBigDecimal("balance"), currency);
			}, utc(asOf), ledgerAccountId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private static LedgerAccountReference toReference(java.sql.ResultSet result)
		throws java.sql.SQLException {
		return new LedgerAccountReference(
			result.getObject("id", UUID.class),
			result.getObject("visible_account_id", UUID.class),
			result.getObject("owner_user_id", UUID.class),
			result.getString("code"),
			LedgerAccountRole.valueOf(result.getString("ledger_role")),
			LedgerAccountNature.valueOf(result.getString("account_nature")),
			CurrencyCode.fromCode(result.getString("currency")),
			"ACTIVE".equals(result.getString("status")));
	}

	private static LedgerPersistenceException persistence(Throwable exception) {
		if (exception instanceof LedgerPersistenceException persistence) {
			return persistence;
		}
		return new LedgerPersistenceException(exception);
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("账务时间不能为空。"));
		}
		return value.atOffset(ZoneOffset.UTC);
	}
}
