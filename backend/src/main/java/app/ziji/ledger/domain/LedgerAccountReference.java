package app.ziji.ledger.domain;

import java.util.UUID;

/** 应用层使用的科目事实快照，不暴露 jOOQ 或数据库记录。 */
public record LedgerAccountReference(
	UUID id,
	UUID visibleAccountId,
	UUID ownerUserId,
	String code,
	LedgerAccountRole role,
	LedgerAccountNature nature,
	CurrencyCode currency,
	boolean active) {

	public LedgerAccountReference {
		if (id == null || role == null || nature == null || currency == null) {
			throw new LedgerDomainException("账务科目事实不完整。");
		}
		if (code == null || code.isBlank()) {
			throw new LedgerDomainException("账务科目代码不能为空。");
		}
		if (role == LedgerAccountRole.SYSTEM && (visibleAccountId != null || ownerUserId == null)) {
			throw new LedgerDomainException("系统科目归属不合法。");
		}
		if (role != LedgerAccountRole.SYSTEM && visibleAccountId == null) {
			throw new LedgerDomainException("可见账户科目必须关联账户。");
		}
	}

	public boolean isVisibleAccountLedger() {
		return visibleAccountId != null;
	}
}
