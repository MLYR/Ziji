package app.ziji.statistics.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;

/**
 * 基础趋势统计：资产/净资产趋势、真实收支现金流和账户余额趋势。
 * 桶边界按 facts 固化的 business_date 计算（入账时已按用户时区派生），
 * 用户修改时区不改变历史归属；缺失汇率的非基准币种账户显式排除，不按 0/1 折算。
 */
public class StatisticsApplicationService implements StatisticsQueryUseCase {

	/** OpenAPI values 地图最多 30 个键；超出部分聚合进 others。 */
	private static final int MAX_ACCOUNT_SERIES = 30;
	private static final int MAX_BUCKETS = 800;
	private static final Set<String> GRANULARITIES = Set.of("DAY", "WEEK", "MONTH", "YEAR");

	private final AccountMembershipReadPort memberships;
	private final AccountQueryReadPort accounts;
	private final StatisticsFactReadPort facts;
	private final CurrentUserBaseCurrencyPort baseCurrencies;

	public StatisticsApplicationService(
		AccountMembershipReadPort memberships,
		AccountQueryReadPort accounts,
		StatisticsFactReadPort facts,
		CurrentUserBaseCurrencyPort baseCurrencies) {
		if (memberships == null || accounts == null || facts == null || baseCurrencies == null) {
			throw new IllegalArgumentException("统计读取依赖不能为空。");
		}
		this.memberships = memberships;
		this.accounts = accounts;
		this.facts = facts;
		this.baseCurrencies = baseCurrencies;
	}

	@Override
	public StatisticsSeriesResult getAssetStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity) {
		Range range = normalize(userId, dateFrom, dateTo, granularity);
		List<UUID> includedAccountIds = includedAccountIds(userId);
		List<AccountQueryReadPort.ClassSummary> summaries = includedAccountIds.isEmpty()
			? List.of() : accounts.listClassSummariesByIds(includedAccountIds, null);
		List<UUID> baseCurrencyIds = summaries.stream()
			.filter(summary -> range.baseCurrency().equals(summary.currency()))
			.map(AccountQueryReadPort.ClassSummary::accountId)
			.toList();
		Map<UUID, String> accountClassById = new LinkedHashMap<>();
		for (AccountQueryReadPort.ClassSummary summary : summaries) {
			accountClassById.put(summary.accountId(), summary.accountClass());
		}
		Map<LocalDate, Map<UUID, BigDecimal>> balances = balanceIndex(
			facts.accountEndBalances(baseCurrencyIds, range.bucketEnds()));

		List<StatisticsSeriesResult.Point> points = new ArrayList<>();
		for (Range.Bucket bucket : range.buckets()) {
			BigDecimal totalAssets = BigDecimal.ZERO;
			BigDecimal totalLiabilities = BigDecimal.ZERO;
			Map<UUID, BigDecimal> perAccount = balances.getOrDefault(bucket.end(), Map.of());
			for (Map.Entry<UUID, BigDecimal> entry : perAccount.entrySet()) {
				if ("LIABILITY".equals(accountClassById.get(entry.getKey()))) {
					totalLiabilities = totalLiabilities.add(entry.getValue());
				} else {
					totalAssets = totalAssets.add(entry.getValue());
				}
			}
			int scale = range.scale();
			Map<String, String> values = new TreeMap<>();
			values.put("totalAssets", money(totalAssets, scale));
			values.put("netAssets", money(totalAssets.subtract(totalLiabilities), scale));
			points.add(new StatisticsSeriesResult.Point(bucket.start(), values));
		}
		return new StatisticsSeriesResult(range.baseCurrency(), 1, points);
	}

	@Override
	public StatisticsSeriesResult getCashFlowStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity) {
		Range range = normalize(userId, dateFrom, dateTo, granularity);
		Map<LocalDate, StatisticsFactReadPort.NatureBucket> byStart = new LinkedHashMap<>();
		for (StatisticsFactReadPort.NatureBucket bucket : facts.natureBuckets(userId, range.from(), range.to(), range.granularity())) {
			byStart.put(bucket.bucketStart(), bucket);
		}
		int scale = range.scale();
		List<StatisticsSeriesResult.Point> points = new ArrayList<>();
		for (Range.Bucket bucket : range.buckets()) {
			StatisticsFactReadPort.NatureBucket nature = byStart.get(bucket.start());
			BigDecimal income = nature == null ? BigDecimal.ZERO : nature.income();
			BigDecimal expense = nature == null ? BigDecimal.ZERO : nature.expense();
			Map<String, String> values = new TreeMap<>();
			values.put("income", money(income, scale));
			values.put("expense", money(expense, scale));
			values.put("netCashFlow", money(income.subtract(expense), scale));
			points.add(new StatisticsSeriesResult.Point(bucket.start(), values));
		}
		return new StatisticsSeriesResult(range.baseCurrency(), 1, points);
	}

	@Override
	public StatisticsSeriesResult getAccountStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity) {
		Range range = normalize(userId, dateFrom, dateTo, granularity);
		List<UUID> includedAccountIds = includedAccountIds(userId);
		List<AccountQueryReadPort.ClassSummary> summaries = includedAccountIds.isEmpty()
			? List.of() : accounts.listClassSummariesByIds(includedAccountIds, null);
		List<UUID> baseCurrencyIds = summaries.stream()
			.filter(summary -> range.baseCurrency().equals(summary.currency()))
			.map(AccountQueryReadPort.ClassSummary::accountId)
			.toList();
		if (baseCurrencyIds.isEmpty()) {
			// OpenAPI values.minProperties=1：无基准币种账户时不能产出空 values 点，返回空序列。
			return new StatisticsSeriesResult(range.baseCurrency(), 1, List.of());
		}
		Map<LocalDate, Map<UUID, BigDecimal>> balances = balanceIndex(
			facts.accountEndBalances(baseCurrencyIds, range.bucketEnds()));

		// 超过 30 个账户时按最后一个桶的余额降序保留前 29 个，其余聚合进 others，保证契约键数上限。
		List<UUID> ordered = baseCurrencyIds.stream()
			.sorted(Comparator.comparing((UUID id) -> latestBalance(balances, range, id)).reversed())
			.toList();
		List<UUID> kept = ordered.size() > MAX_ACCOUNT_SERIES ? ordered.subList(0, MAX_ACCOUNT_SERIES - 1) : ordered;
		Set<UUID> keptIds = new java.util.HashSet<>(kept);

		int scale = range.scale();
		List<StatisticsSeriesResult.Point> points = new ArrayList<>();
		for (Range.Bucket bucket : range.buckets()) {
			Map<UUID, BigDecimal> perAccount = balances.getOrDefault(bucket.end(), Map.of());
			Map<String, String> values = new TreeMap<>();
			BigDecimal others = BigDecimal.ZERO;
			for (UUID accountId : baseCurrencyIds) {
				BigDecimal balance = perAccount.getOrDefault(accountId, BigDecimal.ZERO);
				if (keptIds.contains(accountId)) {
					values.put(accountId.toString(), money(balance, scale));
				} else {
					others = others.add(balance);
				}
			}
			if (ordered.size() > MAX_ACCOUNT_SERIES) {
				values.put("others", money(others, scale));
			}
			points.add(new StatisticsSeriesResult.Point(bucket.start(), values));
		}
		return new StatisticsSeriesResult(range.baseCurrency(), 1, points);
	}

	private static String money(BigDecimal value, int scale) {
		return value.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString();
	}

	private BigDecimal latestBalance(Map<LocalDate, Map<UUID, BigDecimal>> balances, Range range, UUID accountId) {
		return range.bucketEnds().isEmpty() ? BigDecimal.ZERO
			: balances.getOrDefault(range.bucketEnds().getLast(), Map.of()).getOrDefault(accountId, BigDecimal.ZERO);
	}

	private Map<LocalDate, Map<UUID, BigDecimal>> balanceIndex(List<StatisticsFactReadPort.AccountEndBalance> rows) {
		Map<LocalDate, Map<UUID, BigDecimal>> index = new LinkedHashMap<>();
		for (StatisticsFactReadPort.AccountEndBalance row : rows) {
			index.computeIfAbsent(row.bucketEnd(), key -> new LinkedHashMap<>()).put(row.accountId(), row.balance());
		}
		return index;
	}

	private List<UUID> includedAccountIds(UUID userId) {
		List<UUID> ids = new ArrayList<>();
		for (ActiveMembership membership : memberships.listActiveMemberships(userId)) {
			if (membership.inclusionRatio().signum() > 0) {
				ids.add(membership.accountId());
			}
		}
		return ids;
	}

	private Range normalize(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity) {
		if (dateFrom == null || dateTo == null) {
			throw new StatisticsValidationException("dateFrom 与 dateTo 均为必填。");
		}
		if (granularity == null || !GRANULARITIES.contains(granularity)) {
			throw new StatisticsValidationException("granularity 必须是 DAY/WEEK/MONTH/YEAR。");
		}
		if (dateFrom.isAfter(dateTo)) {
			throw new StatisticsValidationException("dateFrom 不能晚于 dateTo。");
		}
		String baseCurrency = baseCurrencies.currentBaseCurrency(userId);
		int scale = CurrencyCode.fromCode(baseCurrency).minorUnits();
		List<Range.Bucket> buckets = buckets(dateFrom, dateTo, granularity);
		if (buckets.size() > MAX_BUCKETS) {
			throw new StatisticsValidationException("统计范围过大。");
		}
		return new Range(dateFrom, dateTo, granularity, baseCurrency, scale, buckets);
	}

	/** 桶起始对齐 date_trunc 语义：周一起始、月初、年初；首个桶可早于 dateFrom 以覆盖不完整桶。 */
	private List<Range.Bucket> buckets(LocalDate dateFrom, LocalDate dateTo, String granularity) {
		List<Range.Bucket> buckets = new ArrayList<>();
		LocalDate start = switch (granularity) {
			case "WEEK" -> dateFrom.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
			case "MONTH" -> dateFrom.withDayOfMonth(1);
			case "YEAR" -> dateFrom.withDayOfYear(1);
			default -> dateFrom;
		};
		while (!start.isAfter(dateTo)) {
			LocalDate end = switch (granularity) {
				case "WEEK" -> start.plusDays(6);
				case "MONTH" -> start.plusMonths(1).minusDays(1);
				case "YEAR" -> start.plusYears(1).minusDays(1);
				default -> start;
			};
			buckets.add(new Range.Bucket(start, end));
			start = end.plusDays(1);
		}
		return buckets;
	}

	private record Range(
		LocalDate from, LocalDate to, String granularity, String baseCurrency, int scale,
		List<Bucket> buckets) {

		record Bucket(LocalDate start, LocalDate end) {
		}

		private List<LocalDate> bucketEnds() {
			return buckets.stream().map(Bucket::end).toList();
		}
	}
}
