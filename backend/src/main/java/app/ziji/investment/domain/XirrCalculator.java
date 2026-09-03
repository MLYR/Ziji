package app.ziji.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** XIRR 纯计算器；现金流不足或不收敛时返回失败状态，不伪造 0。 */
public final class XirrCalculator {

	private static final BigDecimal MIN_RATE = new BigDecimal("-0.9999999999");
	private static final BigDecimal ONE = BigDecimal.ONE;

	public Result calculate(List<CashFlow> input) {
		if (input == null || input.size() < 2) {
			return Result.failed(XirrStatus.INSUFFICIENT_CASH_FLOWS);
		}
		List<CashFlow> flows = new ArrayList<>(input);
		flows.sort(Comparator.comparing(CashFlow::occurredAt));
		if (flows.stream().anyMatch(flow -> flow.amount().signum() == 0)
			|| flows.stream().anyMatch(flow -> flow.occurredAt() == null)
			|| flows.getFirst().occurredAt().equals(flows.getLast().occurredAt())) {
			return Result.failed(XirrStatus.INVALID_CASH_FLOWS);
		}
		boolean positive = flows.stream().anyMatch(flow -> flow.amount().signum() > 0);
		boolean negative = flows.stream().anyMatch(flow -> flow.amount().signum() < 0);
		if (!positive || !negative) {
			return Result.failed(XirrStatus.INSUFFICIENT_CASH_FLOWS);
		}

		BigDecimal guess = new BigDecimal("0.1");
		for (int iteration = 0; iteration < 100; iteration++) {
			Evaluation evaluation = evaluate(flows, guess);
			if (evaluation.value().abs().compareTo(new BigDecimal("0.000000000001")) < 0) {
				return Result.available(guess);
			}
			if (evaluation.derivative().abs().compareTo(new BigDecimal("0.000000000001")) < 0) {
				break;
			}
			BigDecimal next = guess.subtract(evaluation.value().divide(evaluation.derivative(), 30, RoundingMode.HALF_UP));
			if (next.compareTo(MIN_RATE) <= 0 || next.abs().compareTo(new BigDecimal("1000000")) > 0) {
				break;
			}
			if (next.subtract(guess).abs().compareTo(new BigDecimal("0.000000000001")) < 0) {
				return Result.available(next);
			}
			guess = next;
		}

		BigDecimal lower = MIN_RATE;
		BigDecimal upper = ONE;
		BigDecimal lowerValue = evaluate(flows, lower).value();
		BigDecimal upperValue = evaluate(flows, upper).value();
		int expansion = 0;
		while (lowerValue.signum() == upperValue.signum() && expansion++ < 80) {
			upper = upper.add(upper.max(ONE));
			upperValue = evaluate(flows, upper).value();
		}
		if (lowerValue.signum() == upperValue.signum()) {
			return Result.failed(XirrStatus.NON_CONVERGENT);
		}
		for (int iteration = 0; iteration < 160; iteration++) {
			BigDecimal middle = lower.add(upper).divide(BigDecimal.valueOf(2), 30, RoundingMode.HALF_UP);
			BigDecimal middleValue = evaluate(flows, middle).value();
			if (middleValue.abs().compareTo(new BigDecimal("0.000000000001")) < 0
				|| upper.subtract(lower).abs().compareTo(new BigDecimal("0.000000000001")) < 0) {
				return Result.available(middle);
			}
			if (middleValue.signum() == lowerValue.signum()) {
				lower = middle;
				lowerValue = middleValue;
			} else {
				upper = middle;
			}
		}
		return Result.failed(XirrStatus.NON_CONVERGENT);
	}

	private Evaluation evaluate(List<CashFlow> flows, BigDecimal rate) {
		CashFlow first = flows.getFirst();
		BigDecimal value = BigDecimal.ZERO;
		BigDecimal derivative = BigDecimal.ZERO;
		for (CashFlow flow : flows) {
			BigDecimal years = BigDecimal.valueOf(Duration.between(first.occurredAt(), flow.occurredAt()).toSeconds())
				.divide(BigDecimal.valueOf(365L * 24L * 60L * 60L), 30, RoundingMode.HALF_UP);
			BigDecimal base = ONE.add(rate);
			try {
				BigDecimal discount = BigDecimal.valueOf(Math.pow(base.doubleValue(), -years.doubleValue()));
				value = value.add(flow.amount().multiply(discount));
				if (years.signum() != 0) {
					BigDecimal derivativeTerm = years.multiply(flow.amount()).divide(base, 30, RoundingMode.HALF_UP)
						.multiply(discount).negate();
					derivative = derivative.add(derivativeTerm);
				}
			} catch (RuntimeException exception) {
				return new Evaluation(new BigDecimal("1E100"), BigDecimal.ZERO);
			}
		}
		return new Evaluation(value, derivative);
	}

	public record CashFlow(Instant occurredAt, BigDecimal amount) {
		public CashFlow {
			if (occurredAt == null || amount == null) {
				throw new InvestmentDomainException("XIRR 现金流不完整。");
			}
		}
	}

	public record Result(XirrStatus status, BigDecimal rate) {
		public static Result available(BigDecimal rate) {
			return new Result(XirrStatus.AVAILABLE, rate);
		}

		public static Result failed(XirrStatus status) {
			return new Result(status, null);
		}
	}

	private record Evaluation(BigDecimal value, BigDecimal derivative) {
	}
}
