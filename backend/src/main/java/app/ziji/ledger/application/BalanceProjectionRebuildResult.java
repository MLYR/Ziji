package app.ziji.ledger.application;

/** 全量余额重建结果；最终 differenceCount 必须为零，否则服务会回滚。 */
public record BalanceProjectionRebuildResult(int snapshotCount, int previousDifferenceCount, int differenceCount) {
}
