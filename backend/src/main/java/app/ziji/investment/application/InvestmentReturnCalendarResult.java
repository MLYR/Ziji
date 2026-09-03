package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import app.ziji.investment.domain.ReturnStatus;

/** 月度收益日历结果；只有完整 CALCULATED 日才参与月度金额和收益率汇总。 */
public record InvestmentReturnCalendarResult(
	String scopeType,
	UUID instrumentId,
	String baseCurrency,
	YearMonth month,
	int valuationRevision,
	Instant asOf,
	Instant recalculatedAt,
	String summaryStatus,
	BigDecimal monthlyProfit,
	BigDecimal monthlyReturnRate,
	int profitDayCount,
	int lossDayCount,
	int zeroDayCount,
	List<InvestmentReturnDayResult> days,
	List<String> dataQualityWarnings) {

	public InvestmentReturnCalendarResult {
		days = List.copyOf(days == null ? List.of() : days);
		dataQualityWarnings = List.copyOf(dataQualityWarnings == null ? List.of() : dataQualityWarnings);
	}

	public record InvestmentReturnDayResult(
		LocalDate businessDate,
		ReturnStatus status,
		BigDecimal dailyProfit,
		BigDecimal dailyReturnRate,
		int missingInstrumentCount) {
	}
}
