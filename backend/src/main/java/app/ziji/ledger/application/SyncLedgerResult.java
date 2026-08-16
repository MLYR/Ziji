package app.ziji.ledger.application;

import java.util.UUID;

/** Sync 写入后可安全返回的资源引用；不包含分录、账户或内部科目信息。 */
@org.springframework.modulith.NamedInterface("sync-command")
public record SyncLedgerResult(UUID transactionId, int entityVersion) {

	public SyncLedgerResult {
		if (transactionId == null || entityVersion <= 0) {
			throw new LedgerCommandValidationException("同步账务结果无效。");
		}
	}
}
