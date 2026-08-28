package app.ziji.statistics.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Dashboard 读取结果；金额为基准币种入账精度的十进制值，字段与 OpenAPI Dashboard 一一对应。 */
public record DashboardResult(
	String baseCurrency,
	Instant asOf,
	long asOfSequence,
	int valuationRevision,
	Instant recalculatedAt,
	String projectionStatus,
	Summary summary,
	Attribution changeAttribution,
	List<DistributionItem> distribution,
	InvestmentOverview investmentOverview,
	List<QualityWarning> dataQualityWarnings) {

	public DashboardResult {
		if (baseCurrency == null || asOf == null || valuationRevision < 1 || projectionStatus == null
			|| summary == null || changeAttribution == null || distribution == null
			|| investmentOverview == null || dataQualityWarnings == null) {
			throw new IllegalArgumentException("Dashboard 结果不完整。");
		}
		distribution = List.copyOf(distribution);
		dataQualityWarnings = List.copyOf(dataQualityWarnings);
	}

	/** 五个核心指标；availableFunds 只统计普通 ASSET 账户可用余额。 */
	public record Summary(
		BigDecimal totalAssets,
		BigDecimal availableFunds,
		BigDecimal investmentAssets,
		BigDecimal totalLiabilities,
		BigDecimal netAssets) {
	}

	/** 净资产变化归因；B1 无市场与汇率事实，market/fx 恒为 0。 */
	public record Attribution(
		BigDecimal income,
		BigDecimal expense,
		BigDecimal market,
		BigDecimal fx,
		BigDecimal adjustment,
		BigDecimal inclusion) {
	}

	public record DistributionItem(String key, String label, BigDecimal amount, BigDecimal ratio) {
	}

	/** 投资概览；B1 只有券商现金（投资账户 PRIMARY），持仓市值为 0 是事实而非缺省。 */
	public record InvestmentOverview(
		String baseCurrency,
		BigDecimal brokerCash,
		BigDecimal positionMarketValue,
		BigDecimal totalInvestmentAssets,
		int unpricedInstrumentCount) {
	}

	/** 数据质量告警；存在未估值资产或缺汇率账户时不得按 0 静默计入。 */
	public record QualityWarning(String code, int affectedCount) {
	}
}
