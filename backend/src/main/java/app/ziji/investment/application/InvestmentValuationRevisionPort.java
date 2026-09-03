package app.ziji.investment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import app.ziji.investment.domain.ReturnStatus;

/** 收益日历当前修订的读取与发布边界；旧投影只追加保留，不原地覆盖。 */
public interface InvestmentValuationRevisionPort {

	int currentRevision(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month);

	Instant recalculatedAt(UUID userId, String scopeType, UUID instrumentId, String baseCurrency, YearMonth month);

	Publication publish(
		UUID userId,
		String scopeType,
		UUID instrumentId,
		String baseCurrency,
		YearMonth month,
		List<DailySnapshot> days,
		Instant calculatedAt);

	record DailySnapshot(
		LocalDate businessDate,
		ReturnStatus status,
		BigDecimal beginValue,
		BigDecimal endValue,
		BigDecimal netCashFlow,
		BigDecimal dailyProfit,
		BigDecimal dailyReturnRate,
		int missingInstrumentCount) {
	}

	record Publication(int revision, Instant recalculatedAt) {
		public Publication {
			if (revision < 1 || recalculatedAt == null) {
				throw new IllegalArgumentException("收益日历发布结果不完整。");
			}
		}
	}
}
