package app.ziji.marketdata.infrastructure;

/** Tushare HTTP 原始响应，仅在 adapter 内部解析，不向 application 或 API 返回。 */
public record TushareTransportResponse(int httpStatus, String body) {

	public TushareTransportResponse {
		if (httpStatus < 100 || httpStatus > 599 || body == null) {
			throw new IllegalArgumentException("Tushare 响应无效。");
		}
	}
}
