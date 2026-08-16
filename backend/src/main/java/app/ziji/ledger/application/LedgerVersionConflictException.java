package app.ziji.ledger.application;

import java.util.UUID;

/** 仅在完成资源可见性校验后抛出，供 Sync 返回安全的乐观锁摘要。 */
@org.springframework.modulith.NamedInterface("sync-command")
public final class LedgerVersionConflictException extends LedgerCommandValidationException {

	private final UUID transactionId;
	private final int currentVersion;

	public LedgerVersionConflictException(UUID transactionId, int currentVersion) {
		super("交易版本已变化。");
		this.transactionId = transactionId;
		this.currentVersion = currentVersion;
	}

	public UUID transactionId() {
		return transactionId;
	}

	public int currentVersion() {
		return currentVersion;
	}
}
