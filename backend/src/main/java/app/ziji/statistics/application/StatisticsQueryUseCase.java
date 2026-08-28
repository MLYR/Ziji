package app.ziji.statistics.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** 基础趋势统计用例端口；series 语义与 OpenAPI statistics 三个 operationId 一一对应。 */
public interface StatisticsQueryUseCase {

	StatisticsSeriesResult getAssetStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity);

	StatisticsSeriesResult getCashFlowStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity);

	StatisticsSeriesResult getAccountStatistics(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity);
}
