package app.ziji.investment.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import app.ziji.investment.application.InvestmentPersistenceException;
import app.ziji.investment.application.InvestmentValuationRevisionPort;
import app.ziji.investment.domain.ReturnStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 收益日历版本投影的 PostgreSQL 适配器；发布由应用事务包住，旧版本只关闭当前标记。 */
@Repository
public class PostgresInvestmentValuationRevisionStore implements InvestmentValuationRevisionPort {

	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	private final JdbcTemplate jdbc;

	public PostgresInvestmentValuationRevisionStore(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "收益日历数据库入口不能为空。");
	}

	@Override
	public int currentRevision(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month) {
		validateKey(userId, scopeType, instrumentId, baseCurrency, month);
		try {
			Integer revision = jdbc.queryForObject("""
				SELECT COALESCE(MAX(valuation_revision), 0)
				FROM investment_daily_return_snapshots
				WHERE user_id = ? AND scope_type = ? AND instrument_id IS NOT DISTINCT FROM ?
				  AND base_currency = ? AND business_date BETWEEN ? AND ? AND is_current
				""", Integer.class, userId, scopeType, instrumentId, baseCurrency,
				java.sql.Date.valueOf(month.atDay(1)), java.sql.Date.valueOf(month.atEndOfMonth()));
			return revision == null ? 0 : revision;
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Instant recalculatedAt(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month) {
		validateKey(userId, scopeType, instrumentId, baseCurrency, month);
		try {
			Timestamp calculatedAt = jdbc.queryForObject("""
				SELECT MAX(calculated_at)
				FROM investment_daily_return_snapshots
				WHERE user_id = ? AND scope_type = ? AND instrument_id IS NOT DISTINCT FROM ?
				  AND base_currency = ? AND business_date BETWEEN ? AND ? AND is_current
				""", Timestamp.class, userId, scopeType, instrumentId, baseCurrency,
				java.sql.Date.valueOf(month.atDay(1)), java.sql.Date.valueOf(month.atEndOfMonth()));
			return calculatedAt == null ? null : calculatedAt.toInstant();
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Publication publish(
		UUID userId,
		String scopeType,
		UUID instrumentId,
		String baseCurrency,
		YearMonth month,
		List<DailySnapshot> days,
		Instant calculatedAt) {
		Objects.requireNonNull(days, "收益日历日快照不能为空。");
		Objects.requireNonNull(calculatedAt, "收益日历计算时间不能为空。");
		validateKey(userId, scopeType, instrumentId, baseCurrency, month);
		List<DailySnapshot> normalizedDays = normalizeMonth(month, days);
		LocalDate firstDay = month.atDay(1);
		LocalDate lastDay = month.atEndOfMonth();
		try {
			lock(userId, scopeType, instrumentId, baseCurrency, month);
			Map<LocalDate, CurrentSnapshot> current = currentSnapshots(userId, scopeType, instrumentId, baseCurrency, firstDay, lastDay);
			int currentRevision = current.values().stream().mapToInt(CurrentSnapshot::revision).max().orElse(0);
			int historicalRevision = maximumRevision(userId, scopeType, instrumentId, baseCurrency, firstDay, lastDay);
			if (matches(current, currentRevision, normalizedDays)) {
				Instant existingCalculatedAt = current.values().stream().map(CurrentSnapshot::calculatedAt).max(Instant::compareTo).orElse(calculatedAt);
				return new Publication(currentRevision, existingCalculatedAt);
			}

			int nextRevision = Math.max(currentRevision, historicalRevision) + 1;
			for (CurrentSnapshot snapshot : current.values()) {
				jdbc.update("UPDATE investment_daily_return_snapshots SET is_current = false WHERE id = ?", snapshot.id());
			}
			for (DailySnapshot day : normalizedDays) {
				CurrentSnapshot superseded = current.get(day.businessDate());
				int inserted = jdbc.update("""
					INSERT INTO investment_daily_return_snapshots (
						id, user_id, scope_type, instrument_id, business_date, base_currency, status,
						begin_value, end_value, net_cash_flow, daily_profit, daily_return_rate,
						missing_instrument_count, valuation_revision, is_current, supersedes_snapshot_id,
						as_of_change_sequence, calculated_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, 0, ?)
					""", UUID.randomUUID(), userId, scopeType, instrumentId, java.sql.Date.valueOf(day.businessDate()), baseCurrency,
					day.status().name(), day.beginValue(), day.endValue(), day.netCashFlow(), day.dailyProfit(), day.dailyReturnRate(),
					day.missingInstrumentCount(), nextRevision, superseded == null ? null : superseded.id(), Timestamp.from(calculatedAt));
				if (inserted != 1) {
					throw new IllegalStateException("收益日历快照写入未生效。");
				}
			}
			Instant persistedCalculatedAt = recalculatedAt(userId, scopeType, instrumentId, baseCurrency, month);
			return new Publication(nextRevision, persistedCalculatedAt == null ? calculatedAt : persistedCalculatedAt);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private Map<LocalDate, CurrentSnapshot> currentSnapshots(
		UUID userId, String scopeType, UUID instrumentId, String baseCurrency, LocalDate firstDay, LocalDate lastDay) {
		return jdbc.query("""
			SELECT id, business_date, status, begin_value, end_value, net_cash_flow, daily_profit,
				daily_return_rate, missing_instrument_count, valuation_revision, calculated_at
			FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = ? AND instrument_id IS NOT DISTINCT FROM ?
			  AND base_currency = ? AND business_date BETWEEN ? AND ? AND is_current
			ORDER BY business_date
			FOR UPDATE
			""", result -> {
			Map<LocalDate, CurrentSnapshot> snapshots = new LinkedHashMap<>();
			while (result.next()) {
				CurrentSnapshot snapshot = currentSnapshot(result);
				snapshots.put(snapshot.businessDate(), snapshot);
			}
			return snapshots;
		}, userId, scopeType, instrumentId, baseCurrency, java.sql.Date.valueOf(firstDay), java.sql.Date.valueOf(lastDay));
	}

	private int maximumRevision(
		UUID userId, String scopeType, UUID instrumentId, String baseCurrency, LocalDate firstDay, LocalDate lastDay) {
		Integer revision = jdbc.queryForObject("""
			SELECT COALESCE(MAX(valuation_revision), 0)
			FROM investment_daily_return_snapshots
			WHERE user_id = ? AND scope_type = ? AND instrument_id IS NOT DISTINCT FROM ?
			  AND base_currency = ? AND business_date BETWEEN ? AND ?
			""", Integer.class, userId, scopeType, instrumentId, baseCurrency,
			java.sql.Date.valueOf(firstDay), java.sql.Date.valueOf(lastDay));
		return revision == null ? 0 : revision;
	}

	private void lock(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month) {
		String key = String.join("|", userId.toString(), scopeType, String.valueOf(instrumentId), baseCurrency, month.toString());
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> null, key);
	}

	private static boolean matches(Map<LocalDate, CurrentSnapshot> current, int revision, List<DailySnapshot> days) {
		if (revision < 1 || current.size() != days.size()) {
			return false;
		}
		for (DailySnapshot day : days) {
			CurrentSnapshot existing = current.get(day.businessDate());
			if (existing == null || existing.revision() != revision || !same(existing, day)) {
				return false;
			}
		}
		return true;
	}

	private static boolean same(CurrentSnapshot existing, DailySnapshot incoming) {
		return existing.status() == incoming.status()
			&& sameDecimal(existing.beginValue(), incoming.beginValue())
			&& sameDecimal(existing.endValue(), incoming.endValue())
			&& sameDecimal(existing.netCashFlow(), incoming.netCashFlow())
			&& sameDecimal(existing.dailyProfit(), incoming.dailyProfit())
			&& sameDecimal(existing.dailyReturnRate(), incoming.dailyReturnRate())
			&& existing.missingInstrumentCount() == incoming.missingInstrumentCount();
	}

	private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
		return left == null ? right == null : right != null && left.compareTo(right) == 0;
	}

	private static List<DailySnapshot> normalizeMonth(YearMonth month, List<DailySnapshot> days) {
		if (days.isEmpty() || days.size() != month.lengthOfMonth()) {
			throw new IllegalArgumentException("收益日历必须覆盖整个月份。");
		}
		Set<LocalDate> expected = new HashSet<>();
		for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
			expected.add(day);
		}
		Set<LocalDate> actual = new HashSet<>();
		List<DailySnapshot> normalized = days.stream().map(PostgresInvestmentValuationRevisionStore::normalize)
			.sorted(java.util.Comparator.comparing(DailySnapshot::businessDate)).toList();
		for (DailySnapshot snapshot : normalized) {
			if (!actual.add(snapshot.businessDate())) {
				throw new IllegalArgumentException("收益日历不能包含重复日期。");
			}
		}
		if (!expected.equals(actual)) {
			throw new IllegalArgumentException("收益日历日期必须属于传入月份且完整覆盖。");
		}
		return normalized;
	}

	private static DailySnapshot normalize(DailySnapshot snapshot) {
		if (snapshot == null || snapshot.businessDate() == null || snapshot.status() == null
			|| snapshot.missingInstrumentCount() < 0) {
			throw new IllegalArgumentException("收益日历日快照结构无效。");
		}
		if (snapshot.status() == ReturnStatus.CALCULATED
			&& (snapshot.beginValue() == null || snapshot.endValue() == null || snapshot.netCashFlow() == null
				|| snapshot.dailyProfit() == null)) {
			throw new IllegalArgumentException("已计算收益日快照缺少金额。");
		}
		if (snapshot.status() != ReturnStatus.CALCULATED
			&& (snapshot.dailyProfit() != null || snapshot.dailyReturnRate() != null)) {
			throw new IllegalArgumentException("非完整收益状态不能携带收益数值。");
		}
		if ((snapshot.status() == ReturnStatus.PARTIAL || snapshot.status() == ReturnStatus.UNPRICED)
			&& snapshot.missingInstrumentCount() == 0) {
			throw new IllegalArgumentException("缺估值收益状态必须记录缺失标的数量。");
		}
		return new DailySnapshot(snapshot.businessDate(), snapshot.status(), scale(snapshot.beginValue(), 8), scale(snapshot.endValue(), 8),
			scale(snapshot.netCashFlow(), 8), scale(snapshot.dailyProfit(), 8), scale(snapshot.dailyReturnRate(), 10), snapshot.missingInstrumentCount());
	}

	private static void validateKey(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month) {
		Objects.requireNonNull(userId, "收益日历用户不能为空。");
		Objects.requireNonNull(scopeType, "收益日历范围不能为空。");
		Objects.requireNonNull(baseCurrency, "收益日历基准币种不能为空。");
		Objects.requireNonNull(month, "收益日历月份不能为空。");
		if (!SUPPORTED_CURRENCIES.contains(baseCurrency)
			|| ("PORTFOLIO".equals(scopeType) && instrumentId != null)
			|| ("INSTRUMENT".equals(scopeType) && instrumentId == null)
			|| (!"PORTFOLIO".equals(scopeType) && !"INSTRUMENT".equals(scopeType))) {
			throw new IllegalArgumentException("收益日历键无效。");
		}
	}

	private static InvestmentPersistenceException persistence(Throwable exception) {
		return exception instanceof InvestmentPersistenceException failure
			? failure : new InvestmentPersistenceException(exception);
	}

	private static BigDecimal scale(BigDecimal value, int scale) {
		return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP);
	}

	private static CurrentSnapshot currentSnapshot(java.sql.ResultSet result) throws java.sql.SQLException {
		return new CurrentSnapshot(
			result.getObject("id", UUID.class), result.getDate("business_date").toLocalDate(),
			ReturnStatus.valueOf(result.getString("status")), result.getBigDecimal("begin_value"), result.getBigDecimal("end_value"),
			result.getBigDecimal("net_cash_flow"), result.getBigDecimal("daily_profit"), result.getBigDecimal("daily_return_rate"),
			result.getInt("missing_instrument_count"), result.getInt("valuation_revision"), result.getTimestamp("calculated_at").toInstant());
	}

	private record CurrentSnapshot(
		UUID id,
		LocalDate businessDate,
		ReturnStatus status,
		BigDecimal beginValue,
		BigDecimal endValue,
		BigDecimal netCashFlow,
		BigDecimal dailyProfit,
		BigDecimal dailyReturnRate,
		int missingInstrumentCount,
		int revision,
		Instant calculatedAt) {
	}
}
