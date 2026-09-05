package app.ziji.investment.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.investment.domain.InstrumentType;
import app.ziji.investment.domain.InvestmentTrade;

/** 投资事实查询端口；实现可读取 trades/instruments，但不向其他模块暴露表或 jOOQ。 */
public interface InvestmentFactReadPort {

	List<InvestmentTrade> listTrades(UUID investmentAccountId, Instant asOf, LocalDate from, LocalDate to);

	Optional<InvestmentTrade> findTrade(UUID tradeId);

	Optional<InstrumentSnapshot> findInstrument(UUID instrumentId);

	/** 对投资账户行加排他锁直到当前事务结束；并发卖出串行化，防止超卖。 */
	void lockAccountForTrade(UUID accountId);

	record InstrumentSnapshot(
		UUID id,
		InstrumentType type,
		String name,
		String market,
		String currency,
		String status) {
	}
}
