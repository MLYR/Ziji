package app.ziji.account.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** 账户模块读取 Ledger 已入账 PRIMARY 余额的最小端口，不暴露 Ledger 表或 jOOQ 类型。 */
public interface AccountBalanceReadPort {

	Optional<PostedPrimaryBalance> findPostedPrimaryBalance(UUID accountId);

	record PostedPrimaryBalance(BigDecimal amount, String currencyCode) {

		public PostedPrimaryBalance {
			if (amount == null || currencyCode == null || currencyCode.isBlank()) {
				throw new IllegalArgumentException("账户账面余额快照不完整。");
			}
		}
	}
}
