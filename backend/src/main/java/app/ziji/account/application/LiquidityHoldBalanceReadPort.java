package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 余额读取所需的 LiquidityHold SQL 聚合端口，不把完整历史加载到 application 内存。 */
public interface LiquidityHoldBalanceReadPort {

	EffectiveHoldAmounts sumEffectiveAt(UUID accountId, Instant asOf);

	record EffectiveHoldAmounts(
		int currencyCount,
		String currencyCode,
		BigDecimal frozen,
		BigDecimal inTransit,
		BigDecimal reserved,
		int precisionErrorCount) {

		/** 保留既有测试替身和其他读取调用的五字段构造；真实 SQL 聚合额外携带逐行精度错误计数。 */
		public EffectiveHoldAmounts(
			int currencyCount,
			String currencyCode,
			BigDecimal frozen,
			BigDecimal inTransit,
			BigDecimal reserved) {
			this(currencyCount, currencyCode, frozen, inTransit, reserved, 0);
		}

		public EffectiveHoldAmounts {
			if (currencyCount < 0 || precisionErrorCount < 0 || frozen == null || inTransit == null || reserved == null) {
				throw new IllegalArgumentException("流动性占用聚合快照不完整。");
			}
		}
	}
}
