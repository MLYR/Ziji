package app.ziji.statistics.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.statistics.application.StatisticsFactReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 在真实 Ledger 事实表上聚合统计值；只读，不修改任何事实。
 * 桶边界由 application 计算：natureBuckets 用 date_trunc 对齐桶起始，
 * accountEndBalances 用 VALUES 桶表累计至桶末日。
 */
@Repository
public class PostgresStatisticsFactReadPort implements StatisticsFactReadPort {

	private final JdbcTemplate jdbc;

	public PostgresStatisticsFactReadPort(JdbcTemplate jdbc) {
		if (jdbc == null) {
			throw new IllegalArgumentException("统计事实读取依赖不能为空。");
		}
		this.jdbc = jdbc;
	}

	private static final String NATURE_BUCKET_SQL = """
		SELECT date_trunc('%s', t.business_date)::date AS bucket_start,
			COALESCE(SUM(e.amount) FILTER (WHERE la.account_nature = 'INCOME' AND e.direction = 'C'), 0) AS income,
			COALESCE(SUM(e.amount) FILTER (WHERE la.account_nature = 'EXPENSE' AND e.direction = 'D'), 0) AS expense
		FROM ledger_entries e
		JOIN ledger_accounts la ON la.id = e.ledger_account_id
		JOIN transactions t ON t.id = e.transaction_id
		WHERE t.created_by = ? AND t.status = 'POSTED'
			AND la.ledger_role = 'SYSTEM' AND la.account_nature IN ('INCOME', 'EXPENSE')
			AND t.business_date >= ? AND t.business_date <= ?
		GROUP BY 1 ORDER BY 1
		""";

	private static final String ACCOUNT_END_SQL = """
		SELECT b.bucket_end, la.visible_account_id AS account_id,
			COALESCE(SUM(
				CASE
					WHEN t.posted_at IS NULL OR t.business_date > b.bucket_end THEN 0
					WHEN (e.direction = 'D' AND la.account_nature <> 'LIABILITY')
						OR (e.direction = 'C' AND la.account_nature = 'LIABILITY') THEN e.amount
					ELSE -e.amount
				END), 0) AS balance
		FROM (VALUES %s) AS b(bucket_end)
		CROSS JOIN (SELECT DISTINCT la.id, la.visible_account_id, la.account_nature
			FROM ledger_accounts la
			WHERE la.ledger_role = 'PRIMARY' AND la.visible_account_id IN (%s)) la
		LEFT JOIN ledger_entries e ON e.ledger_account_id = la.id
		LEFT JOIN transactions t ON t.id = e.transaction_id
		GROUP BY b.bucket_end, la.visible_account_id, la.account_nature
		ORDER BY b.bucket_end
		""";

	@Override
	public List<NatureBucket> natureBuckets(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity) {
		String unit = bucketUnit(granularity);
		return jdbc.query(
			NATURE_BUCKET_SQL.formatted(unit),
			(rs, rowNum) -> new NatureBucket(
				rs.getDate("bucket_start").toLocalDate(),
				rs.getBigDecimal("income"),
				rs.getBigDecimal("expense")),
			userId, java.sql.Date.valueOf(dateFrom), java.sql.Date.valueOf(dateTo));
	}

	@Override
	public List<AccountEndBalance> accountEndBalances(List<UUID> accountIds, List<LocalDate> bucketEnds) {
		if (accountIds == null || accountIds.isEmpty() || bucketEnds == null || bucketEnds.isEmpty()) {
			return List.of();
		}
		String bucketValues = String.join(", ", java.util.Collections.nCopies(bucketEnds.size(), "(CAST(? AS date))"));
		String accountPlaceholders = String.join(", ", java.util.Collections.nCopies(accountIds.size(), "?"));
		String sql = ACCOUNT_END_SQL.formatted(bucketValues, accountPlaceholders);
		List<Object> bindings = new ArrayList<>();
		for (LocalDate bucketEnd : bucketEnds) {
			bindings.add(java.sql.Date.valueOf(bucketEnd));
		}
		bindings.addAll(accountIds);
		return jdbc.query(
			sql,
			(rs, rowNum) -> new AccountEndBalance(
				rs.getDate("bucket_end").toLocalDate(),
				rs.getObject("account_id", UUID.class),
				rs.getBigDecimal("balance")),
			bindings.toArray());
	}

	/** date_trunc 字段与粒度一一对应；非法粒度在 application 层已被拒绝。 */
	private static String bucketUnit(String granularity) {
		return switch (granularity) {
			case "DAY" -> "day";
			case "WEEK" -> "week";
			case "MONTH" -> "month";
			case "YEAR" -> "year";
			default -> throw new IllegalArgumentException("不支持的统计粒度。");
		};
	}
}
