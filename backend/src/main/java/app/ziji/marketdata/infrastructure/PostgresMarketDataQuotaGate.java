package app.ziji.marketdata.infrastructure;

import java.time.LocalDate;
import java.util.Objects;

import app.ziji.marketdata.application.internal.MarketDataQuotaPort;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;

/** 将配置的每日配额上限与数据库原子计数绑定；limit 变化在次日生效。 */
public final class PostgresMarketDataQuotaGate implements MarketDataQuotaPort {

	private final MarketDataSyncStore store;
	private final int callLimit;

	public PostgresMarketDataQuotaGate(MarketDataSyncStore store, int callLimit) {
		this.store = Objects.requireNonNull(store, "市场数据配额存储不能为空。");
		if (callLimit < 1) {
			throw new IllegalArgumentException("市场数据每日配额必须为正数。");
		}
		this.callLimit = callLimit;
	}

	@Override
	public boolean reserve(LocalDate usageDate) {
		if (usageDate == null) {
			return false;
		}
		return store.reserveQuotaCall(usageDate, callLimit);
	}
}
