package app.ziji.investment.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.investment.application.InvestmentFactReadPort;
import app.ziji.investment.domain.InstrumentType;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.investment.domain.InvestmentTrade;
import app.ziji.ledger.domain.CurrencyCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 投资事实读取适配器；只返回已入账且未被后续状态排除的 Trade。 */
@Repository
public class PostgresInvestmentFactStore implements InvestmentFactReadPort {

	private final JdbcTemplate jdbc;

	public PostgresInvestmentFactStore(JdbcTemplate jdbc) {
		this.jdbc = java.util.Objects.requireNonNull(jdbc, "投资数据库入口不能为空。");
	}

	@Override
	public List<InvestmentTrade> listTrades(UUID investmentAccountId, Instant asOf, LocalDate from, LocalDate to) {
		if (investmentAccountId == null) {
			return List.of();
		}
		StringBuilder sql = new StringBuilder("""
			SELECT tr.id, tr.transaction_id, tr.investment_account_id, tr.instrument_id, tr.side,
				tr.quantity, tr.unit_price, tr.currency, tr.gross_amount, tr.fee_amount, tr.tax_amount, tr.trade_at
			FROM trades tr
			JOIN transactions t ON t.id = tr.transaction_id
			WHERE tr.investment_account_id = ? AND t.status = 'POSTED'
			""");
		List<Object> arguments = new ArrayList<>();
		arguments.add(investmentAccountId);
		if (asOf != null) {
			sql.append(" AND tr.trade_at <= ?");
			arguments.add(java.sql.Timestamp.from(asOf));
		}
		if (from != null) {
			sql.append(" AND t.business_date >= ?");
			arguments.add(java.sql.Date.valueOf(from));
		}
		if (to != null) {
			sql.append(" AND t.business_date <= ?");
			arguments.add(java.sql.Date.valueOf(to));
		}
		sql.append(" ORDER BY tr.trade_at, tr.id");
		try {
			return jdbc.query(sql.toString(), (result, ignored) -> trade(result), arguments.toArray());
		} catch (RuntimeException exception) {
			throw new IllegalStateException("投资成交事实读取失败。", exception);
		}
	}

	@Override
	public Optional<InvestmentTrade> findTrade(UUID tradeId) {
		if (tradeId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT tr.id, tr.transaction_id, tr.investment_account_id, tr.instrument_id, tr.side,
					tr.quantity, tr.unit_price, tr.currency, tr.gross_amount, tr.fee_amount, tr.tax_amount, tr.trade_at
				FROM trades tr JOIN transactions t ON t.id = tr.transaction_id
				WHERE tr.id = ? AND t.status = 'POSTED'
				""", result -> result.next() ? Optional.of(trade(result)) : Optional.empty(), tradeId);
		} catch (RuntimeException exception) {
			throw new IllegalStateException("投资成交读取失败。", exception);
		}
	}

	@Override
	public Optional<InstrumentSnapshot> findInstrument(UUID instrumentId) {
		if (instrumentId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_type, name, market, currency, status
				FROM instruments WHERE id = ?
				""", result -> result.next() ? Optional.of(new InstrumentSnapshot(
				result.getObject("id", UUID.class), InstrumentType.valueOf(result.getString("instrument_type")),
				result.getString("name"), result.getString("market"), result.getString("currency"),
				result.getString("status"))) : Optional.empty(), instrumentId);
		} catch (RuntimeException exception) {
			throw new IllegalStateException("投资产品读取失败。", exception);
		}
	}

	private static InvestmentTrade trade(java.sql.ResultSet result) throws java.sql.SQLException {
		return new InvestmentTrade(
			result.getObject("id", UUID.class), result.getObject("transaction_id", UUID.class),
			result.getObject("investment_account_id", UUID.class), result.getObject("instrument_id", UUID.class),
			InvestmentSide.valueOf(result.getString("side")), result.getBigDecimal("quantity"),
			result.getBigDecimal("unit_price"), CurrencyCode.fromCode(result.getString("currency")).name(),
			result.getBigDecimal("gross_amount"), result.getBigDecimal("fee_amount"),
			result.getBigDecimal("tax_amount"), result.getTimestamp("trade_at").toInstant());
	}
}
