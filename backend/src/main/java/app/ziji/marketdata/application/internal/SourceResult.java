package app.ziji.marketdata.application.internal;

import java.time.Instant;
import java.util.List;

/** 外部来源的可观测结果；失败时 prices 为空，绝不生成零价格。 */
public record SourceResult(
	SourceOutcome outcome,
	List<SourcePrice> prices,
	int attempts,
	Instant completedAt) {

	public SourceResult {
		if (outcome == null || prices == null || attempts < 0 || completedAt == null) {
			throw new IllegalArgumentException("外部来源结果无效。");
		}
		prices = List.copyOf(prices);
	}

	public static SourceResult failure(SourceOutcome outcome, int attempts, Instant completedAt) {
		if (outcome == SourceOutcome.SUCCESS) {
			throw new IllegalArgumentException("成功结果不能使用 failure 工厂。");
		}
		return new SourceResult(outcome, List.of(), attempts, completedAt);
	}
}
