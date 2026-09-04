package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B3 投资持仓属性测试（QA-INV-002）：固定随机种子生成交易序列，验证数量/成本基础/移动加权平均成本守恒、
 * 重建确定性、输入顺序无关（投影可重建）以及超卖被拒绝（不生成负持仓）。
 */
class InvestmentPositionPropertyTests {

	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-4000-8000-000000000304");
	private static final List<UUID> INSTRUMENTS = List.of(
		UUID.fromString("00000000-0000-4000-8000-000000000311"),
		UUID.fromString("00000000-0000-4000-8000-000000000312"),
		UUID.fromString("00000000-0000-4000-8000-000000000313"));
	private static final Instant BASE = Instant.parse("2026-08-01T01:00:00Z");
	private static final long SEED = 20260801L;

	@Test
	void fixedSeedRandomTradeSequenceConservesPositionInvariants() {
		Random random = new Random(SEED);
		GeneratedSequence sequence = generate(random, 120);
		PositionCalculator calculator = new PositionCalculator();
		Map<UUID, Position> rebuilt = calculator.rebuild(sequence.trades());

		// 确定性：同输入重建两次结果一致。
		Map<UUID, Position> rebuiltAgain = calculator.rebuild(sequence.trades());
		for (UUID instrument : INSTRUMENTS) {
			assertPositionEquals(rebuilt.getOrDefault(instrument, Position.empty(instrument)),
				rebuiltAgain.getOrDefault(instrument, Position.empty(instrument)));
		}

		// 不变量：每个标的的数量、成本基础、移动加权平均成本与顺序模拟一致。
		for (UUID instrument : INSTRUMENTS) {
			Sim sim = sequence.simulation().get(instrument);
			Position actual = rebuilt.getOrDefault(instrument, Position.empty(instrument));
			assertEquals(0, sim.quantity().compareTo(actual.quantity()), "数量必须守恒。");
			assertEquals(0, sim.costBasis().compareTo(actual.costBasis()), "成本基础必须守恒。");
			assertEquals(0, sim.averageCost().compareTo(actual.averageCost()), "移动加权平均成本必须一致。");
		}
	}

	@Test
	void rebuildIsOrderIndependentOfInputListOrder() {
		Random random = new Random(SEED);
		GeneratedSequence sequence = generate(random, 120);
		PositionCalculator calculator = new PositionCalculator();
		Map<UUID, Position> byTradeTime = calculator.rebuild(sequence.trades());

		// 故意打乱输入顺序，rebuild 内部按 tradeAt 然后 id 重排，结果必须与有序输入一致。
		List<InvestmentTrade> shuffled = new ArrayList<>(sequence.trades());
		shuffled.sort(Comparator.comparing(InvestmentTrade::quantity));
		Map<UUID, Position> byShuffled = calculator.rebuild(shuffled);

		for (UUID instrument : INSTRUMENTS) {
			assertPositionEquals(byTradeTime.getOrDefault(instrument, Position.empty(instrument)),
				byShuffled.getOrDefault(instrument, Position.empty(instrument)));
		}
	}

	@Test
	void overSellIsRejectedByRebuild() {
		Random random = new Random(SEED);
		GeneratedSequence sequence = generate(random, 120);
		UUID instrument = INSTRUMENTS.get(0);
		// 追加一笔远超持仓的卖出，必须被拒绝（不生成负持仓）。
		InvestmentTrade overSell = new InvestmentTrade(
			UUID.randomUUID(), UUID.randomUUID(), ACCOUNT_ID, instrument, InvestmentSide.SELL,
			new BigDecimal("1000000"), new BigDecimal("10"), "CNY", new BigDecimal("10000000"),
			BigDecimal.ZERO, BigDecimal.ZERO, BASE.plusSeconds(1_000_000));
		List<InvestmentTrade> withOverSell = new ArrayList<>(sequence.trades());
		withOverSell.add(overSell);
		assertThrows(InvestmentDomainException.class, () -> new PositionCalculator().rebuild(withOverSell));
	}

	private static void assertPositionEquals(Position a, Position b) {
		assertEquals(0, a.quantity().compareTo(b.quantity()));
		assertEquals(0, a.costBasis().compareTo(b.costBasis()));
		assertEquals(0, a.averageCost().compareTo(b.averageCost()));
	}

	private record Sim(BigDecimal quantity, BigDecimal costBasis, BigDecimal averageCost) {
	}

	private static GeneratedSequence generate(Random random, int count) {
		List<InvestmentTrade> trades = new ArrayList<>();
		Map<UUID, Sim> sim = new LinkedHashMap<>();
		for (UUID instrument : INSTRUMENTS) {
			sim.put(instrument, new Sim(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		}
		for (int i = 0; i < count; i++) {
			UUID instrument = INSTRUMENTS.get(random.nextInt(INSTRUMENTS.size()));
			Sim current = sim.get(instrument);
			boolean buy = random.nextDouble() < 0.6 || current.quantity().signum() == 0;
			BigDecimal requested = new BigDecimal(String.valueOf(1 + random.nextInt(100)));
			BigDecimal price = new BigDecimal(String.valueOf(5 + random.nextInt(46)));
			BigDecimal gross = price.multiply(requested);
			InvestmentSide side;
			BigDecimal tradeQty;
			if (buy) {
				side = InvestmentSide.BUY;
				tradeQty = requested;
			} else {
				tradeQty = requested.min(current.quantity());
				if (tradeQty.signum() == 0) {
					side = InvestmentSide.BUY;
					tradeQty = requested;
				} else {
					side = InvestmentSide.SELL;
				}
			}
			Sim updated = side == InvestmentSide.BUY ? applyBuy(current, tradeQty, gross) : applySell(current, tradeQty);
			sim.put(instrument, updated);
			trades.add(trade(i, instrument, side, tradeQty, price, gross));
		}
		return new GeneratedSequence(trades, sim);
	}

	private static Sim applyBuy(Sim current, BigDecimal qty, BigDecimal gross) {
		BigDecimal nextQty = current.quantity().add(qty);
		BigDecimal nextCost = current.costBasis().add(gross);
		BigDecimal nextAvg = nextCost.divide(nextQty, 24, RoundingMode.HALF_UP);
		return new Sim(nextQty, nextCost, nextAvg);
	}

	private static Sim applySell(Sim current, BigDecimal qty) {
		BigDecimal releasedCost = qty.compareTo(current.quantity()) == 0
			? current.costBasis() : qty.multiply(current.averageCost());
		BigDecimal nextQty = current.quantity().subtract(qty);
		BigDecimal nextCost = nextQty.signum() == 0 ? BigDecimal.ZERO : current.costBasis().subtract(releasedCost);
		BigDecimal nextAvg = nextQty.signum() == 0
			? BigDecimal.ZERO : nextCost.divide(nextQty, 24, RoundingMode.HALF_UP);
		return new Sim(nextQty, nextCost, nextAvg);
	}

	private static InvestmentTrade trade(
		int index, UUID instrument, InvestmentSide side, BigDecimal qty, BigDecimal price, BigDecimal gross) {
		UUID id = UUID.randomUUID();
		return new InvestmentTrade(id, id, ACCOUNT_ID, instrument, side, qty, price, "CNY", gross,
			BigDecimal.ZERO, BigDecimal.ZERO, BASE.plusSeconds((long) index * 7 + 1));
	}

	private record GeneratedSequence(List<InvestmentTrade> trades, Map<UUID, Sim> simulation) {
	}
}
