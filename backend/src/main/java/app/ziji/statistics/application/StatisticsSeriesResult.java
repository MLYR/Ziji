package app.ziji.statistics.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 统计序列结果；金额为基准币种入账精度十进制字符串，桶标签为桶起始日。 */
public record StatisticsSeriesResult(String baseCurrency, int valuationRevision, List<Point> points) {

	public StatisticsSeriesResult {
		if (baseCurrency == null || valuationRevision < 1 || points == null) {
			throw new IllegalArgumentException("统计序列结果不完整。");
		}
		points = List.copyOf(points);
	}

	public record Point(LocalDate businessDate, Map<String, String> values) {

		public Point {
			if (businessDate == null || values == null || values.isEmpty()) {
				throw new IllegalArgumentException("统计点不完整。");
			}
			values = Map.copyOf(values);
		}
	}
}
