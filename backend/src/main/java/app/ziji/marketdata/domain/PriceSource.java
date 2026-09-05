package app.ziji.marketdata.domain;

/** V005 价格来源；供应商字段只在适配层转换为该枚举。TUSHARE 仅保留读取旧数据。 */
public enum PriceSource {
	THS,
	TUSHARE,
	MANUAL
}
