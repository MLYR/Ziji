package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;

/**
 * 当前用户可见账户的查询投影；承载 membership 的 currentUserRole 与当前计入比例，
 * 不把共享视角的字段塞回 Account 聚合。
 */
public record AccountQueryResult(
	UUID id,
	AccountClass accountClass,
	AccountType accountType,
	String name,
	String institution,
	AccountCurrency currency,
	AccountStatus status,
	Instant createdAt,
	int version,
	String currentUserRole,
	BigDecimal inclusionRatio) {

	private static final Set<String> ROLES = Set.of("OWNER", "EDITOR", "VIEWER");

	public AccountQueryResult {
		if (id == null || accountClass == null || accountType == null || name == null
			|| currency == null || status == null || createdAt == null
			|| currentUserRole == null || inclusionRatio == null || version < 1) {
			throw new AccountQueryValidationException("账户查询结果不完整。");
		}
		if (!ROLES.contains(currentUserRole)) {
			throw new AccountQueryValidationException("账户成员角色无效。");
		}
		if (inclusionRatio.compareTo(BigDecimal.ZERO) < 0 || inclusionRatio.compareTo(BigDecimal.ONE) > 0) {
			throw new AccountQueryValidationException("账户计入比例无效。");
		}
	}

	public String etag() {
		return "\"" + version + "\"";
	}
}
