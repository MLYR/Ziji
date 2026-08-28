package app.ziji.statistics.application;

/** 统计请求或读取语义无效；interfaces 层映射为 400 VALIDATION_ERROR。 */
public class StatisticsValidationException extends RuntimeException {

	public StatisticsValidationException(String message) {
		super(message);
	}
}
