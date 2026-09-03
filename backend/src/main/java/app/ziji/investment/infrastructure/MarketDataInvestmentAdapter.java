package app.ziji.investment.infrastructure;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import app.ziji.investment.application.InvestmentMarketDataPort;
import app.ziji.marketdata.application.MarketDataReadPort;
import app.ziji.marketdata.application.MarketPrice;
import org.springframework.stereotype.Component;

/** 将 marketdata.application 的价格结果转换为投资模块自己的最小价格语义。 */
@Component
public final class MarketDataInvestmentAdapter implements InvestmentMarketDataPort {

	private final MarketDataReadPort prices;

	public MarketDataInvestmentAdapter(MarketDataReadPort prices) {
		this.prices = java.util.Objects.requireNonNull(prices, "投资行情入口不能为空。");
	}

	@Override
	public Optional<PriceSnapshot> latestPrice(UUID instrumentId, LocalDate businessDate, String expectedCurrency) {
		return prices.findLatestValuation(instrumentId, businessDate).filter(price -> expectedCurrency.equals(price.currency()))
			.map(MarketDataInvestmentAdapter::snapshot);
	}

	private static PriceSnapshot snapshot(MarketPrice price) {
		return new PriceSnapshot(
			price.price(), price.businessDate(), price.currency(), price.source().name(), price.fetchedAt(),
			price.priceType().name(), switch (price.freshness()) {
				case FRESH -> Freshness.FRESH;
				case STALE -> Freshness.STALE;
				case UNAVAILABLE -> Freshness.UNAVAILABLE;
			});
	}
}
