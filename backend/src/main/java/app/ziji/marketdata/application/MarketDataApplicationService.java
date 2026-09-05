package app.ziji.marketdata.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.RemoteInstrument;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSnapshot;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import app.ziji.shared.application.TransactionRunner;

/** 产品和手工行情用例；所有 revision 写入与审计在同一事务中完成。 */
public class MarketDataApplicationService {

	private final MarketDataCommandStore store;
	private final TransactionRunner transactions;
	private final AuditLogWritePort auditLogs;
	private final MarketDataSourcePort source;
	private final boolean remoteSearchEnabled;
	private final Clock clock;

	public MarketDataApplicationService(
		MarketDataCommandStore store,
		TransactionRunner transactions,
		AuditLogWritePort auditLogs,
		MarketDataSourcePort source,
		boolean remoteSearchEnabled,
		Clock clock) {
		this.store = Objects.requireNonNull(store, "市场数据存储不能为空。");
		this.transactions = Objects.requireNonNull(transactions, "市场数据事务入口不能为空。");
		this.auditLogs = Objects.requireNonNull(auditLogs, "市场数据审计入口不能为空。");
		this.source = source;
		this.remoteSearchEnabled = remoteSearchEnabled;
		this.clock = Objects.requireNonNull(clock, "市场数据时钟不能为空。");
	}

	public List<InstrumentView> search(UUID userId, String query, int limit, String requestId) {
		String normalized = requiredText(query, 100, "搜索关键词");
		List<InstrumentView> local = store.search(normalized, boundedLimit(limit)).stream()
			.map(this::instrumentView).toList();
		// 本地缓存未命中且已开启远程搜索时，服务端按限流策略触发供应商基础信息查询并缓存命中产品；同花顺无搜索接口时返回空。
		if (!local.isEmpty() || !remoteSearchEnabled || source == null) {
			return local;
		}
		return searchRemote(userId, normalized, boundedLimit(limit), requestId);
	}

	private List<InstrumentView> searchRemote(UUID userId, String query, int limit, String requestId) {
		List<RemoteInstrument> candidates;
		try {
			candidates = source.searchBasics(query);
		} catch (RuntimeException exception) {
			// 远程搜索失败降级为空结果，由客户端提示手工创建，不阻断搜索本身。
			return List.of();
		}
		List<InstrumentView> result = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (RemoteInstrument candidate : candidates) {
			if (result.size() >= limit) {
				break;
			}
			if (!seen.add(candidate.externalCode())) {
				continue;
			}
			result.add(findOrCreateRemoteInstrument(userId, candidate, requestId));
		}
		return List.copyOf(result);
	}

	/** 远程候选按外部代码复用既有产品，否则原子创建产品与 THS 映射；并发冲突回退为读取既有产品。 */
	private InstrumentView findOrCreateRemoteInstrument(UUID userId, RemoteInstrument candidate, String requestId) {
		var existing = store.findByExternalCode(PriceSource.THS, candidate.externalCode());
		if (existing.isPresent()) {
			return instrumentView(existing.get());
		}
		InstrumentType type = enumValue(candidate.instrumentType(), InstrumentType.class, "产品类型");
		String name = candidate.name().trim();
		if (name.length() > 200 || candidate.externalCode().length() > 80) {
			throw new MarketDataValidationException("远程产品信息无效。");
		}
		Instant now = clock.instant();
		Instrument instrument = new Instrument(
			UUID.randomUUID(), type, name, candidate.sourceMarket() == null || candidate.sourceMarket().isBlank()
				? "THS" : candidate.sourceMarket(), "CNY", InstrumentStatus.ACTIVE, now, now, 1);
		try {
			return transactions.required(() -> {
				Instrument created = store.insertInstrumentWithMapping(
					instrument, PriceSource.THS, candidate.externalCode(), candidate.sourceMarket());
				appendAudit(userId, requestId, "INSTRUMENT_CREATED", created.id(), null,
					java.util.Map.of("instrumentType", created.instrumentType().name(), "source", "THS"));
				return instrumentView(created);
			});
		} catch (MarketDataConflictException exception) {
			// 并发创建时唯一约束生效；回退读取已落库产品。
			return store.findByExternalCode(PriceSource.THS, candidate.externalCode())
				.map(this::instrumentView)
				.orElseThrow(MarketDataNotFoundException::new);
		}
	}

	public InstrumentView createInstrument(
		UUID userId, String instrumentType, String name, String market, String currency, String sourceCode, String requestId) {
		requireUser(userId);
		InstrumentType type = enumValue(instrumentType, InstrumentType.class, "产品类型");
		String normalizedName = requiredText(name, 200, "产品名称");
		String normalizedMarket = market == null || market.isBlank() ? "MANUAL" : requiredText(market, 40, "市场");
		String normalizedCurrency = requiredCurrency(currency);
		// 可选同花顺代码：提供后创建 THS 映射，盘后同步按该代码拉取；不提供则保持纯手工产品。
		String normalizedCode = normalizeSourceCode(sourceCode);
		Instant now = clock.instant();
		Instrument instrument = new Instrument(
			UUID.randomUUID(), type, normalizedName, normalizedMarket, normalizedCurrency,
			InstrumentStatus.ACTIVE, now, now, 1);
		Instrument created = transactions.required(() -> {
			Instrument result = normalizedCode == null
				? store.insertInstrument(instrument)
				: store.insertInstrumentWithMapping(instrument, PriceSource.THS, normalizedCode, null);
			appendAudit(userId, requestId, "INSTRUMENT_CREATED", result.id(), null,
				java.util.Map.of("instrumentType", result.instrumentType().name(), "currency", result.currency(),
					"source", normalizedCode == null ? "MANUAL" : "THS"));
			return result;
		});
		return instrumentView(created);
	}

	public InstrumentView getInstrument(UUID instrumentId) {
		Instrument instrument = store.findInstrument(instrumentId).orElseThrow(MarketDataNotFoundException::new);
		return instrumentView(instrument);
	}

	public List<PriceView> listPrices(UUID instrumentId, LocalDate from, LocalDate to, int limit) {
		if (instrumentId == null) {
			throw new MarketDataValidationException("产品 ID 不能为空。");
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw new MarketDataValidationException("价格日期范围无效。");
		}
		if (store.findInstrument(instrumentId).isEmpty()) {
			throw new MarketDataNotFoundException();
		}
		return store.listCurrentPrices(instrumentId, from, to, boundedLimit(limit)).stream()
			.map(this::priceView).toList();
	}

	public PriceView getPrice(UUID priceId) {
		return priceView(store.findPriceById(priceId).orElseThrow(MarketDataNotFoundException::new));
	}

	public PriceView createManualPrice(
		UUID userId,
		UUID instrumentId,
		String priceType,
		LocalDate businessDate,
		BigDecimal price,
		String currency,
		String reason,
		String requestId) {
		requireUser(userId);
		Instrument instrument = store.findInstrument(instrumentId).orElseThrow(MarketDataNotFoundException::new);
		PriceType type = enumValue(priceType, PriceType.class, "价格类型");
		if (businessDate == null || price == null || price.signum() <= 0 || price.scale() > 12 || price.precision() > 28) {
			throw new MarketDataValidationException("手工价格无效。");
		}
		if (!manualPriceTypeAllowed(instrument.instrumentType(), type)) {
			throw new MarketDataValidationException("手工价格类型与产品类型不匹配。");
		}
		String normalizedCurrency = requiredCurrency(currency);
		if (!instrument.currency().equals(normalizedCurrency)) {
			throw new MarketDataValidationException("价格币种与产品不一致。");
		}
		String normalizedReason = requiredText(reason, 500, "修正原因");
		return saveManualPrice(userId, instrument, type, businessDate, price, normalizedCurrency,
			normalizedReason, null, requestId);
	}

	public PriceView correctPrice(
		UUID userId, UUID supersedesPriceId, BigDecimal price, String reason, String requestId) {
		requireUser(userId);
		if (supersedesPriceId == null || price == null || price.signum() <= 0 || price.scale() > 12 || price.precision() > 28) {
			throw new MarketDataValidationException("价格修正参数无效。");
		}
		PriceSnapshot previous = store.findPriceById(supersedesPriceId).orElseThrow(MarketDataNotFoundException::new);
		return saveManualPrice(userId, store.findInstrument(previous.instrumentId()).orElseThrow(MarketDataNotFoundException::new),
			previous.priceType(), previous.businessDate(), price, previous.currency(), requiredText(reason, 500, "修正原因"),
			supersedesPriceId, requestId);
	}

	public MarketDataStatusView status() {
		MarketDataCommandStore.MarketDataStatus status = store.status(clock.instant());
		return new MarketDataStatusView("THS", status.status(), status.lastSuccessfulSyncAt(), status.freshness());
	}

	private PriceView saveManualPrice(
		UUID userId,
		Instrument instrument,
		PriceType priceType,
		LocalDate businessDate,
		BigDecimal price,
		String currency,
		String reason,
		UUID supersedesId,
		String requestId) {
		Instant now = clock.instant();
		String contentHash = hash(instrument.id(), PriceSource.MANUAL, priceType, businessDate, price, currency, supersedesId);
		PriceSnapshot saved = transactions.required(() -> {
			PriceSnapshot result = store.insertPrice(
				UUID.randomUUID(), instrument.id(), PriceSource.MANUAL, priceType, businessDate, price, currency,
				now, now, userId, reason, null, supersedesId, contentHash);
			appendAudit(userId, requestId, supersedesId == null ? "PRICE_CREATED" : "PRICE_CORRECTED",
				result.id(), null, java.util.Map.of("source", "MANUAL", "priceType", priceType.name()));
			return result;
		});
		return priceView(saved);
	}

	private InstrumentView instrumentView(Instrument instrument) {
		List<MappingView> mappings = store.listMappings(instrument.id()).stream()
			.map(mapping -> new MappingView(mapping.source().name(), mapping.externalCode(), mapping.sourceMarket()))
			.toList();
		return new InstrumentView(instrument.id(), instrument.instrumentType().name(), instrument.name(), instrument.market(),
			instrument.currency(), instrument.status().name(), instrument.version(), mappings);
	}

	private PriceView priceView(PriceSnapshot snapshot) {
		Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(3));
		String freshness = snapshot.fetchedAt().isBefore(cutoff) ? "STALE" : "FRESH";
		return new PriceView(snapshot.id(), snapshot.instrumentId(), snapshot.priceType().name(), snapshot.price(),
			snapshot.currency(), snapshot.businessDate(), snapshot.source().name(), snapshot.revisionNo(),
			snapshot.sourceUpdatedAt(), snapshot.fetchedAt(), freshness);
	}

	private void appendAudit(UUID userId, String requestId, String action, UUID resourceId, UUID accountId,
		java.util.Map<String, String> metadata) {
		auditLogs.append(new AuditLogWritePort.AuditLogEntry(
			clock.instant(), userId, AuditLogWritePort.ActorType.USER, action, "MARKET_DATA", resourceId, accountId,
			requestId == null || requestId.isBlank() ? "marketdata" : requestId,
			AuditLogWritePort.Result.SUCCESS, null, metadata));
	}

	private static int boundedLimit(int limit) {
		if (limit < 1 || limit > 200) {
			throw new MarketDataValidationException("查询数量必须在 1 到 200 之间。");
		}
		return limit;
	}

	private static String requiredText(String value, int max, String field) {
		if (value == null || value.isBlank() || value.trim().length() != value.length() || value.length() > max) {
			throw new MarketDataValidationException(field + "无效。");
		}
		return value;
	}

	/** 同花顺产品代码：6 位数字，允许带交易所后缀；空白视为不提供映射。 */
	private static String normalizeSourceCode(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim();
		if (!normalized.matches("[0-9]{1,10}(\\.[A-Za-z]{2})?") || normalized.length() > 20) {
			throw new MarketDataValidationException("产品代码无效。");
		}
		return normalized;
	}

	private static String requiredCurrency(String value) {
		String normalized = requiredText(value, 3, "币种").toUpperCase(java.util.Locale.ROOT);
		if (!java.util.Set.of("CNY", "USD", "HKD", "JPY", "EUR").contains(normalized)) {
			throw new MarketDataValidationException("不支持的币种。");
		}
		return normalized;
	}

	private static boolean manualPriceTypeAllowed(InstrumentType instrumentType, PriceType priceType) {
		return switch (instrumentType) {
			case STOCK, ETF -> priceType == PriceType.CLOSE || priceType == PriceType.MANUAL;
			case FUND -> priceType == PriceType.UNIT_NAV || priceType == PriceType.MANUAL;
			case OTHER -> priceType == PriceType.MANUAL;
		};
	}

	private static void requireUser(UUID userId) {
		if (userId == null) {
			throw new MarketDataValidationException("当前用户不能为空。");
		}
	}

	private static <T extends Enum<T>> T enumValue(String value, Class<T> type, String field) {
		try {
			return type.cast(Enum.valueOf(type, requiredText(value, 40, field)));
		} catch (RuntimeException exception) {
			throw new MarketDataValidationException(field + "无效。");
		}
	}

	private static String hash(
		UUID instrumentId, PriceSource source, PriceType priceType, LocalDate businessDate, BigDecimal price,
		String currency, UUID supersedesId) {
		try {
			String value = String.join("|", instrumentId.toString(), source.name(), priceType.name(), businessDate.toString(),
				price.toPlainString(), currency, String.valueOf(supersedesId));
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 不可用。", exception);
		}
	}

	public record InstrumentView(
		UUID id, String instrumentType, String name, String market, String currency, String status, int version,
		List<MappingView> sourceMappings) {
	}

	public record MappingView(String source, String externalCode, String sourceMarket) {
	}

	public record PriceView(
		UUID id, UUID instrumentId, String priceType, BigDecimal price, String currency, LocalDate businessDate,
		String source, int revision, Instant sourceUpdatedAt, Instant fetchedAt, String freshness) {
	}

	public record MarketDataStatusView(
		String source, String status, Instant lastSuccessfulSyncAt, String freshness) {
	}
}
