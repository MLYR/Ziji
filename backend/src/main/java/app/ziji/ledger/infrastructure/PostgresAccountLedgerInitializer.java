package app.ziji.ledger.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.ledger.application.LedgerPersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 为可见账户创建 PRIMARY 和 POSITION_COST 账务科目。
 * V007 validate_ledger_account_mapping 触发器在写入时校验 nature/class/currency 一致性。
 */
@Repository
public class PostgresAccountLedgerInitializer implements AccountLedgerInitializationPort {

	private final JdbcTemplate jdbc;

	public PostgresAccountLedgerInitializer(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void initializePrimary(UUID accountId, String accountClass, String currency, Instant now) {
		if (accountId == null || accountClass == null || currency == null || now == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("账务科目初始化参数不完整。"));
		}
		// PRIMARY 科目性质由账户大类决定；与 V007 validate_ledger_account_mapping 保持一致。
		String primaryNature = switch (accountClass) {
			case "ASSET", "INVESTMENT" -> "ASSET";
			case "LIABILITY" -> "LIABILITY";
			default -> throw new LedgerPersistenceException(
				new IllegalArgumentException("不支持的账户大类。"));
		};

		try {
			insertLedgerAccount(UUID.randomUUID(), accountId, "PRIMARY", primaryNature, currency, Timestamp.from(now));
		} catch (DataAccessException exception) {
			throw new LedgerPersistenceException(exception);
		}
	}

	@Override
	public void initializePositionCost(UUID accountId, String currency, Instant now) {
		if (accountId == null || currency == null || now == null) {
			throw new LedgerPersistenceException(new IllegalArgumentException("POSITION_COST 只适用于投资账户。"));
		}
		try {
			// POSITION_COST 仅作为投资成本重建事实，不重复计入资产总额。
			insertLedgerAccount(UUID.randomUUID(), accountId, "POSITION_COST", "ASSET",
				currency, Timestamp.from(now));
		} catch (DataAccessException exception) {
			throw new LedgerPersistenceException(exception);
		}
	}

	private void insertLedgerAccount(
		UUID id, UUID accountId, String role, String nature, String currency, Timestamp ts) {
		// code 使用 ACCOUNT_ 前缀加账户 ID，满足 varchar(80) 且不与系统科目索引冲突。
		int inserted = jdbc.update("""
			INSERT INTO ledger_accounts
				(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
			""", id, accountId, "ACCOUNT_" + accountId, role, nature, currency, ts);
		if (inserted != 1) {
			throw new LedgerPersistenceException(new IllegalStateException("账务科目写入未生效。"));
		}
	}
}
