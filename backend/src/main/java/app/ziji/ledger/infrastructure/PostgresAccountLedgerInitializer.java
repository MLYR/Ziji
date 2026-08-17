package app.ziji.ledger.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandValidationException;
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
	private final LedgerCommandApplicationService ledgerCommands;

	public PostgresAccountLedgerInitializer(JdbcTemplate jdbc, LedgerCommandApplicationService ledgerCommands) {
		this.jdbc = jdbc;
		this.ledgerCommands = ledgerCommands;
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

	@Override
	public UUID postOpening(
		UUID accountId,
		String accountClass,
		String currency,
		UUID createdBy,
		AccountOpeningBalance openingBalance,
		ZoneId timezone) {
		try {
			return ledgerCommands.postOpening(accountId, accountClass, currency, createdBy, openingBalance, timezone);
		} catch (LedgerCommandValidationException exception) {
			throw new app.ziji.account.application.AccountCreationException(exception.getMessage());
		}
	}

	@Override
	public Optional<UUID> findOpeningTransactionId(UUID accountId) {
		if (accountId == null) {
			return Optional.empty();
		}
		try {
			List<UUID> transactionIds = jdbc.query("""
				SELECT t.id
				FROM transactions t
				JOIN ledger_entries e ON e.transaction_id = t.id
				JOIN ledger_accounts la ON la.id = e.ledger_account_id
				WHERE t.transaction_type = 'OPENING' AND t.status = 'POSTED'
				  AND la.visible_account_id = ? AND la.ledger_role = 'PRIMARY'
				""", (result, rowNum) -> result.getObject("id", UUID.class), accountId);
			// 账户创建至多一笔内部 OPENING；歧义时禁止任选一笔伪造首次响应。
			if (transactionIds.size() > 1) {
				throw new LedgerPersistenceException(new IllegalStateException("账户存在多笔期初交易。"));
			}
			return transactionIds.isEmpty() ? Optional.empty() : Optional.of(transactionIds.getFirst());
		} catch (RuntimeException exception) {
			if (exception instanceof LedgerPersistenceException persistenceException) {
				throw persistenceException;
			}
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
