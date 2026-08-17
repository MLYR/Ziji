package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Instant;

/** 创建账户时可选的期初余额语义；不包含分录、科目或客户端可控业务日期。 */
public record AccountOpeningBalance(BigDecimal amount, Instant businessAt, String note) {

	public AccountOpeningBalance {
		if (amount == null || amount.signum() <= 0 || businessAt == null) {
			throw new AccountCreationException("期初余额参数无效。");
		}
		if (note != null && note.codePointCount(0, note.length()) > 500) {
			throw new AccountCreationException("期初余额备注格式无效。");
		}
	}
}
