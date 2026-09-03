package app.ziji.ledger.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.application.InvestmentCashReadPort;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ledger 对投资模块的现金读取适配器；外部边界现金流只识别普通账户转账。 */
@Repository
public class PostgresLedgerInvestmentReadPort implements InvestmentCashReadPort {

	private final LedgerAccountStore accounts;
	private final JdbcTemplate jdbc;

	public PostgresLedgerInvestmentReadPort(LedgerAccountStore accounts, JdbcTemplate jdbc) {
		this.accounts = java.util.Objects.requireNonNull(accounts, "账务科目读取入口不能为空。");
		this.jdbc = java.util.Objects.requireNonNull(jdbc, "账务数据库入口不能为空。");
	}

	@Override
	public CashBalance findCashBalance(UUID investmentAccountId, Instant asOf) {
		LedgerAccountReference primary = accounts.findPrimaryForVisibleAccount(investmentAccountId)
			.orElseThrow(() -> new IllegalStateException("投资账户缺少 PRIMARY 科目。"));
		Money balance = accounts.balanceAt(primary.id(), asOf);
		return new CashBalance(balance.currency().name(), balance.amount());
	}

	@Override
	public List<ExternalCashFlow> listExternalCashFlows(UUID investmentAccountId, Instant from, Instant to) {
		if (investmentAccountId == null || from == null || to == null || !to.isAfter(from)) {
			return List.of();
		}
		try {
			return jdbc.query("""
				SELECT t.business_at, d.from_account_id, d.to_account_id, d.from_amount, d.to_amount,
					from_account.account_class AS from_class, to_account.account_class AS to_class
				FROM transfer_details d
				JOIN transactions t ON t.id = d.transaction_id AND t.status = 'POSTED'
				JOIN accounts from_account ON from_account.id = d.from_account_id
				JOIN accounts to_account ON to_account.id = d.to_account_id
				WHERE t.business_at >= ? AND t.business_at < ?
				  AND (d.from_account_id = ? OR d.to_account_id = ?)
				ORDER BY t.business_at, t.id
				""", (result, ignored) -> {
				UUID fromAccount = result.getObject("from_account_id", UUID.class);
				UUID toAccount = result.getObject("to_account_id", UUID.class);
				boolean investmentFrom = investmentAccountId.equals(fromAccount) && !"INVESTMENT".equals(result.getString("to_class"));
				boolean investmentTo = investmentAccountId.equals(toAccount) && !"INVESTMENT".equals(result.getString("from_class"));
				if (!investmentFrom && !investmentTo) {
					return null;
				}
				BigDecimal amount = investmentTo
					? result.getBigDecimal("to_amount")
					: result.getBigDecimal("from_amount").negate();
				return new ExternalCashFlow(result.getTimestamp("business_at").toInstant(), amount);
			}, Timestamp.from(from), Timestamp.from(to), investmentAccountId, investmentAccountId).stream()
				.filter(java.util.Objects::nonNull).toList();
		} catch (RuntimeException exception) {
			throw new IllegalStateException("投资边界现金流读取失败。", exception);
		}
	}
}
