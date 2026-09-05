package app.ziji.marketdata.application.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.domain.InstrumentType;

/**
 * 增量同步用例的内部存储端口：同步候选、运行记录、调度锁和每日配额。
 * 调用方只能通过受控方法推进状态，不能直接访问表。
 */
public interface MarketDataSyncStore {

	/** 所有待同步的 ACTIVE 产品及其 THS 映射和各价格类型的最新业务日期。 */
	List<SyncCandidate> listSyncCandidates();

	/** 更新映射的最近成功同步时间；失败调用不更新。 */
	void touchMappingLastSyncedAt(UUID mappingId, Instant syncedAt);

	/** 尝试获取跨实例调度锁；已持有时返回 false，不得重复入锁。 */
	boolean tryAcquireSyncLock();

	/** 释放调度锁；幂等，未持有时不报错。 */
	void releaseSyncLock();

	/** 开新运行记录并返回其 ID；同一时刻只允许一个未结束的运行。 */
	UUID beginSyncRun(Instant startedAt, int instrumentCount);

	/** 结束运行记录；状态、计数和受控摘要只允许从 RUNNING 推进一次。 */
	void completeSyncRun(
		UUID runId, Instant completedAt, String status, int succeededCount, int failedCount,
		String outcome, String errorSummary);

	/** 当日配额内原子保留一次调用；超额返回 false，不产生负值或并发超卖。 */
	boolean reserveQuotaCall(LocalDate usageDate, int callLimit);

	/** 当前已用配额；统计目的，不影响保留语义。 */
	int usedQuotaCalls(LocalDate usageDate);

	record SyncCandidate(
		UUID instrumentId,
		InstrumentType instrumentType,
		String currency,
		UUID mappingId,
		String externalCode,
		Optional<LocalDate> lastCloseDate,
		Optional<LocalDate> lastUnitNavDate,
		Optional<Instant> lastSyncedAt) {
	}
}
