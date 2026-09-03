package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 投资领域金标准：持仓、现金流收益计算和失败状态不依赖 Spring 或数据库。 */
class InvestmentDomainModelTests {

	private static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
	private static final Instant FIRST_TRADE_AT = Instant.parse("2026-08-01T01:00:00Z");

	@Test
	void rebuildsMovingWeightedAverageCostAcrossBuys() {
		PositionCalculator calculator = new PositionCalculator();

		Position result = calculator.rebuild(List.of(
			trade("00000000-0000-4000-8000-000000000302", InvestmentSide.BUY, "100", "10", "1000"),
			trade("00000000-0000-4000-8000-000000000303", InvestmentSide.BUY, "100", "20", "2000")))
			.get(INSTRUMENT_ID);

		assertEquals(0, new BigDecimal("200").compareTo(result.quantity()));
		assertEquals(0, new BigDecimal("3000").compareTo(result.costBasis()));
		assertEquals(0, new BigDecimal("15").compareTo(result.averageCost()));
	}

	@Test
	void allocatesPartialSellAtMovingAverageAndClearsCostAfterFullSell() {
		PositionCalculator calculator = new PositionCalculator();
		Position afterBuys = calculator.rebuild(List.of(
			trade("00000000-0000-4000-8000-000000000302", InvestmentSide.BUY, "100", "10", "1000"),
			trade("00000000-0000-4000-8000-000000000303", InvestmentSide.BUY, "100", "20", "2000")))
			.get(INSTRUMENT_ID);

		Position.SellAllocation partial = afterBuys.sell(new BigDecimal("40"));
		assertEquals(0, new BigDecimal("600").compareTo(partial.releasedCost()));
		assertEquals(0, new BigDecimal("160").compareTo(partial.remaining().quantity()));
		assertEquals(0, new BigDecimal("2400").compareTo(partial.remaining().costBasis()));
		assertEquals(0, new BigDecimal("15").compareTo(partial.remaining().averageCost()));

		Position.SellAllocation cleared = partial.remaining().sell(new BigDecimal("160"));
		assertEquals(0, new BigDecimal("2400").compareTo(cleared.releasedCost()));
		assertEquals(0, BigDecimal.ZERO.compareTo(cleared.remaining().quantity()));
		assertEquals(0, BigDecimal.ZERO.compareTo(cleared.remaining().costBasis()));
		assertEquals(0, BigDecimal.ZERO.compareTo(cleared.remaining().averageCost()));
	}

	@Test
	void rejectsSellThatWouldCreateNegativePosition() {
		Position empty = Position.empty(INSTRUMENT_ID);

		assertThrows(InvestmentDomainException.class, () -> empty.sell(new BigDecimal("1")));
	}

	@Test
	void sortsSameTimestampTradesByTradeIdBeforeApplyingPosition() {
		PositionCalculator calculator = new PositionCalculator();
		UUID sellId = UUID.fromString("00000000-0000-4000-8000-000000000302");
		UUID buyId = UUID.fromString("00000000-0000-4000-8000-000000000303");

		assertThrows(InvestmentDomainException.class, () -> calculator.rebuild(List.of(
			trade(sellId.toString(), InvestmentSide.SELL, "1", "10", "10"),
			trade(buyId.toString(), InvestmentSide.BUY, "1", "10", "10"))));
	}

	@Test
	void returnsNullModifiedDietzRateWhenDenominatorIsNotPositive() {
		ModifiedDietzCalculator calculator = new ModifiedDietzCalculator();

		ModifiedDietzCalculator.Result result = calculator.fromComponents(
			new BigDecimal("0"), new BigDecimal("10"), new BigDecimal("0"), new BigDecimal("0"));

		assertEquals(0, new BigDecimal("10").compareTo(result.profit()));
		assertEquals(0, BigDecimal.ZERO.compareTo(result.denominator()));
		assertNull(result.returnRate());
	}

	@Test
	void returnsInvalidCashFlowsForZeroOrSameDateFlows() {
		XirrCalculator calculator = new XirrCalculator();

		assertEquals(XirrStatus.INVALID_CASH_FLOWS, calculator.calculate(List.of(
			new XirrCalculator.CashFlow(FIRST_TRADE_AT, new BigDecimal("-100")),
			new XirrCalculator.CashFlow(FIRST_TRADE_AT.plusSeconds(1), BigDecimal.ZERO))).status());
		assertEquals(XirrStatus.INVALID_CASH_FLOWS, calculator.calculate(List.of(
			new XirrCalculator.CashFlow(FIRST_TRADE_AT, new BigDecimal("-100")),
			new XirrCalculator.CashFlow(FIRST_TRADE_AT, new BigDecimal("150")))).status());
	}

	@Test
	void returnsNonConvergentWhenCashFlowsHaveNoRootInAllowedRateDomain() {
		XirrCalculator calculator = new XirrCalculator();

		XirrCalculator.Result result = calculator.calculate(List.of(
			new XirrCalculator.CashFlow(FIRST_TRADE_AT, new BigDecimal("100")),
			new XirrCalculator.CashFlow(FIRST_TRADE_AT.plusSeconds(365L * 24 * 60 * 60), new BigDecimal("-1")),
			new XirrCalculator.CashFlow(FIRST_TRADE_AT.plusSeconds(2L * 365 * 24 * 60 * 60), new BigDecimal("100"))));

		assertEquals(XirrStatus.NON_CONVERGENT, result.status());
	}

	private static InvestmentTrade trade(
		String id, InvestmentSide side, String quantity, String unitPrice, String grossAmount) {
		UUID tradeId = UUID.fromString(id);
		return new InvestmentTrade(
			tradeId, tradeId, UUID.fromString("00000000-0000-4000-8000-000000000304"), INSTRUMENT_ID, side,
			new BigDecimal(quantity), new BigDecimal(unitPrice), "CNY", new BigDecimal(grossAmount),
			BigDecimal.ZERO, BigDecimal.ZERO, FIRST_TRADE_AT.plusSeconds(Long.parseLong(id.substring(id.length() - 3), 16)));
	}
}
