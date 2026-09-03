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

	record InstrumentSnapshot(
		UUID id,
		InstrumentType type,
		String name,
		String market,
		String currency,
		String status) {
	}
}
