package app.ziji.marketdata.application.internal;

import java.time.LocalDate;

/**
 * 外部行情供应商的每日调用配额门；由数据库原子计数支撑，
 * 跨实例共享同一上限。保留失败表示当日配额耗尽，调用方不得再发起供应商请求。
 */
public interface MarketDataQuotaPort {

	/** 当日配额内原子保留一次调用；超额返回 false。 */
	boolean reserve(LocalDate usageDate);
}
