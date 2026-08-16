package app.ziji.ledger.domain;

/** V1 账务允许使用的币种代码，与数据库 CHECK 保持一致。 */
@org.springframework.modulith.NamedInterface("sync-command")
public enum CurrencyCode {
	CNY,
	USD,
	HKD,
	JPY,
	EUR;

	/** V007 currency_minor_units 的领域镜像：JPY 为 0 位，其余 V1 币种为 2 位。 */
	public int minorUnits() {
		return switch (this) {
			case CNY, USD, HKD, EUR -> 2;
			case JPY -> 0;
		};
	}

	/** 将外部代码严格转换为 V1 允许的币种，不做静默降级。 */
	public static CurrencyCode fromCode(String code) {
		if (code == null || code.isBlank()) {
			throw new LedgerDomainException("币种代码不能为空。");
		}
		try {
			return valueOf(code);
		} catch (IllegalArgumentException exception) {
			throw new LedgerDomainException("不支持的币种代码。");
		}
	}
}
