package app.ziji.marketdata.infrastructure;

/** 同花顺 HTTP 原始响应，仅在 adapter 内部解析，不向 application 或 API 返回。 */
public record ThsTransportResponse(int httpStatus, String body) {

	public ThsTransportResponse {
		if (httpStatus < 100 || httpStatus > 599 || body == null) {
			throw new IllegalArgumentException("同花顺响应无效。");
		}
	}
}
