package app.ziji.marketdata.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import app.ziji.shared.application.TransactionRunner;

/**
 * 盘后增量同步用例：按产品上次业务日期增量拉取同花顺日线/净值，
 * 以内容 Hash 幂等写入版本化价格快照，受每日配额、限流和跨实例调度锁约束。
 * 每个产品的拉取在事务外执行、写入在独立事务提交，单产品失败不中断整批。
 */
public class MarketDataSyncService {

	private static final int DEFAULT_LOOKBACK_DAYS = 30;

	private final MarketDataCommandStore store;
	private final MarketDataSyncStore syncStore;
	private final MarketDataSourcePort source;
	private final TransactionRunner transactions;
	private final Clock clock;

	public MarketDataSyncService(
		MarketDataCommandStore store,
		MarketDataSyncStore syncStore,
		MarketDataSourcePort source,
		TransactionRunner transactions,
		Clock clock) {
		this.store = Objects.requireNonNull(store, "市场数据存储不能为空。");
		this.syncStore = Objects.requireNonNull(syncStore, "市场数据同步存储不能为空。");
		this.source = Objects.requireNonNull(source, "市场数据同步来源不能为空。");
		this.transactions = Objects.requireNonNull(transactions, "市场数据同步事务入口不能为空。");
		this.clock = Objects.requireNonNull(clock, "市场数据同步时钟不能为空。");
	}

	public SyncSummary syncIncremental() {
		Instant startedAt = clock.instant();
		if (!syncStore.tryAcquireSyncLock()) {
			return SyncSummary.skipped("SKIPPED_LOCK_HELD");
		}
		try {
			return runLocked(startedAt);
		} finally {
			syncStore.releaseSyncLock();
		}
	}

	private SyncSummary runLocked(Instant startedAt) {
		List<MarketDataSyncStore.SyncCandidate> candidates = syncStore.listSyncCandidates();
		UUID runId = syncStore.beginSyncRun(startedAt, candidates.size());
		int succeeded = 0;
		int failed = 0;
		String outcome = candidates.isEmpty() ? "NO_CANDIDATES" : "SUCCESS";
		LocalDate today = LocalDate.now(clock);
		for (MarketDataSyncStore.SyncCandidate candidate : candidates) {
			PriceType priceType = priceType(candidate.instrumentType());
			if (priceType == null) {
				continue;
			}
			SyncResult result = syncOne(candidate, priceType, today);
			if (result.succeeded()) {
				succeeded++;
				continue;
			}
			failed++;
			outcome = result.outcome();
			// 无 token、无权限或配额耗尽时其余产品同样无法拉取，停止本轮并保留已成功部分。
			if (result.stopBatch()) {
				break;
			}
		}
		String status = failed == 0 ? "SUCCEEDED" : (succeeded == 0 ? "FAILED" : "PARTIAL");
		syncStore.completeSyncRun(runId, clock.instant(), status, succeeded, failed, outcome,
			failed == 0 ? null : "部分产品同步失败，已记录失败数量与受控摘要。");
		return new SyncSummary(status, candidates.size(), succeeded, failed, outcome);
	}

	private SyncResult syncOne(MarketDataSyncStore.SyncCandidate candidate, PriceType priceType, LocalDate today) {
		LocalDate lastDate = lastSyncedDate(candidate, priceType);
		// 该产品当日价格已存在时不重复拉取，避免无意义调用。
		if (lastDate != null && !lastDate.isBefore(today)) {
			return SyncResult.ok();
		}
		LocalDate start = lastDate == null
			? candidate.lastSyncedAt()
				.map(syncedAt -> syncedAt.atZone(java.time.ZoneId.of("UTC")).toLocalDate())
				.orElse(today.minusDays(DEFAULT_LOOKBACK_DAYS))
			: lastDate.plusDays(1);
		if (start.isAfter(today)) {
			return SyncResult.ok();
		}
		var instrument = store.findInstrument(candidate.instrumentId());
		var mapping = store.listMappings(candidate.instrumentId()).stream()
			.filter(item -> item.id().equals(candidate.mappingId())).findFirst();
		if (instrument.isEmpty() || mapping.isEmpty()) {
			return SyncResult.failure("MAPPING_MISSING", false);
		}
		SourceResult fetched;
		try {
			fetched = source.fetchPrices(instrument.get(), mapping.get(), start, today);
		} catch (RuntimeException exception) {
			// 适配器只返回受控 outcome；这里兜底未分类异常，不携带供应商细节。
			return SyncResult.failure("ADAPTER_ERROR", false);
		}
		if (fetched.outcome() == SourceOutcome.SUCCESS || fetched.outcome() == SourceOutcome.NO_DATA) {
			try {
				transactions.required(() -> persist(candidate, fetched.prices()));
				return SyncResult.ok();
			} catch (RuntimeException exception) {
				return SyncResult.failure("PERSISTENCE_FAILED", false);
			}
		}
		return switch (fetched.outcome()) {
			case NO_TOKEN -> SyncResult.failure("NO_TOKEN", true);
			case RATE_LIMITED -> SyncResult.failure("QUOTA_EXHAUSTED", true);
			case UNAUTHORIZED -> SyncResult.failure("UNAUTHORIZED", true);
			case TIMEOUT, UNAVAILABLE, ERROR -> SyncResult.failure("PROVIDER_UNAVAILABLE", false);
			default -> SyncResult.failure("PROVIDER_UNAVAILABLE", false);
		};
	}

	private void persist(MarketDataSyncStore.SyncCandidate candidate, List<SourcePrice> prices) {
		Instant fetchedAt = clock.instant();
		for (SourcePrice price : prices) {
			String contentHash = hash(candidate.instrumentId(), price);
			store.insertPrice(
				UUID.randomUUID(), candidate.instrumentId(), PriceSource.THS, price.priceType(),
				price.businessDate(), price.price(), price.currency(), price.sourceUpdatedAt(), fetchedAt,
				null, null, price.rawPayloadHash(), null, contentHash);
		}
		syncStore.touchMappingLastSyncedAt(candidate.mappingId(), fetchedAt);
	}

	private static PriceType priceType(InstrumentType instrumentType) {
		return switch (instrumentType) {
			case STOCK, ETF -> PriceType.CLOSE;
			case FUND -> PriceType.UNIT_NAV;
			case OTHER -> null;
		};
	}

	private static LocalDate lastSyncedDate(MarketDataSyncStore.SyncCandidate candidate, PriceType priceType) {
		if (priceType == PriceType.CLOSE) {
			return candidate.lastCloseDate().orElse(null);
		}
		return candidate.lastUnitNavDate().orElse(null);
	}

	private static String hash(UUID instrumentId, SourcePrice price) {
		try {
			String value = String.join("|", instrumentId.toString(), PriceSource.THS.name(),
				price.priceType().name(), price.businessDate().toString(), price.price().toPlainString(),
				price.currency());
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 不可用。", exception);
		}
	}

	public record SyncResult(boolean succeeded, String outcome, boolean stopBatch) {

		static SyncResult ok() {
			return new SyncResult(true, "SUCCESS", false);
		}

		static SyncResult failure(String outcome, boolean stopBatch) {
			return new SyncResult(false, outcome, stopBatch);
		}
	}

	public record SyncSummary(String status, int instrumentCount, int succeededCount, int failedCount, String outcome) {

		public SyncSummary {
			if (status == null || status.isBlank() || instrumentCount < 0 || succeededCount < 0
				|| failedCount < 0 || outcome == null || outcome.isBlank()) {
				throw new IllegalArgumentException("同步摘要无效。");
			}
		}

		static SyncSummary skipped(String outcome) {
			return new SyncSummary("SKIPPED", 0, 0, 0, outcome);
		}
	}
}
