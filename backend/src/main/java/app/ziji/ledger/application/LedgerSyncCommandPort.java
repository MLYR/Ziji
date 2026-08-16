package app.ziji.ledger.application;

/** Sync 模块唯一可写的 Ledger 边界；不暴露仓储、分录或内部科目。 */
@org.springframework.modulith.NamedInterface("sync-command")
public interface LedgerSyncCommandPort {

	SyncLedgerResult applySync(SyncLedgerCommand command);
}
