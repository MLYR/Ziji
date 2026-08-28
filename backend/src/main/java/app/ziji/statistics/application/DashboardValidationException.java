package app.ziji.statistics.application;

/** Dashboard 请求或读取语义无效；interfaces 层映射为 400 VALIDATION_ERROR。 */
public class DashboardValidationException extends RuntimeException {

	public DashboardValidationException(String message) {
		super(message);
	}
}
