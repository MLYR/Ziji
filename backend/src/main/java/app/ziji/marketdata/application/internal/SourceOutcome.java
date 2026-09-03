package app.ziji.marketdata.application.internal;

/** 外部来源失败分类；不携带供应商原始消息、请求体或 token。 */
public enum SourceOutcome {
	SUCCESS,
	NO_TOKEN,
	TIMEOUT,
	RATE_LIMITED,
	UNAUTHORIZED,
	NO_DATA,
	UNAVAILABLE,
	ERROR
}
