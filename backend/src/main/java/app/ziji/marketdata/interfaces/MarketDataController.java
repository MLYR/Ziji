package app.ziji.marketdata.interfaces;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.marketdata.application.MarketDataApplicationService;
import app.ziji.marketdata.application.MarketDataConflictException;
import app.ziji.marketdata.application.MarketDataRetryableException;
import app.ziji.marketdata.application.MarketDataValidationException;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 产品、价格和行情状态的 HTTP 边界；请求只转换为受控市场数据用例。 */
@RestController
@RequestMapping("/api/v1")
public class MarketDataController {

	private static final int API_MAJOR_VERSION = 1;
	private static final java.util.Set<String> INSTRUMENT_FIELDS = java.util.Set.of(
		"instrumentType", "name", "market", "currency", "sourceCode");
	private static final java.util.Set<String> PRICE_FIELDS = java.util.Set.of(
		"priceType", "businessDate", "price", "currency", "reason");
	private static final java.util.Set<String> CORRECTION_FIELDS = java.util.Set.of(
		"supersedesPriceId", "price", "reason");

	private final MarketDataApplicationService marketData;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;

	public MarketDataController(
		MarketDataApplicationService marketData,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency) {
		this.marketData = marketData;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
	}

	@GetMapping(path = "/instruments/search", name = "searchInstruments")
	public ResponseEntity<InstrumentListEnvelope> searchInstruments(
		@RequestParam("q") String query,
		@RequestParam(name = "limit", required = false, defaultValue = "50") String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		int limit = parseLimit(rawLimit);
		List<MarketDataApplicationService.InstrumentView> data = marketData.search(
			userId, query, limit, requestId(response));
		return ResponseEntity.ok(new InstrumentListEnvelope(data, new PageMeta(requestId(response), null, false)));
	}

	@PostMapping(path = "/instruments", consumes = MediaType.APPLICATION_JSON_VALUE, name = "createInstrument")
	public ResponseEntity<?> createInstrument(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		InstrumentCommand command = parseInstrument(body);
		String key = idempotencyKey(request);
		IdempotencyExecution<MarketDataApplicationService.InstrumentView> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "createInstrument", key,
			requestHash("/api/v1/instruments", command.hashPayload(), null), () -> {
				MarketDataApplicationService.InstrumentView created = marketData.createInstrument(
					userId, command.instrumentType(), command.name(), command.market(), command.currency(),
					command.sourceCode(), requestId(response));
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "INSTRUMENT", created.id(), new IdempotencyResponse.ResourceReference(
						"/api/v1/instruments/" + created.id(), quote(created.version()), (long) created.version())));
			});
		if (execution.executedNow()) {
			return ResponseEntity.status(HttpStatus.CREATED)
				.eTag(quote(execution.value().version()))
				.body(new InstrumentEnvelope(execution.value(), new ResponseMeta(requestId(response))));
		}
		if (execution.replayed()) {
			MarketDataApplicationService.InstrumentView instrument = marketData.getInstrument(execution.response().resourceId());
			return ResponseEntity.status(execution.response().responseStatus())
				.eTag(quote(instrument.version()))
				.body(new InstrumentEnvelope(instrument, new ResponseMeta(requestId(response))));
		}
		throw idempotencyFailure(execution);
	}

	@GetMapping(path = "/instruments/{instrumentId}", name = "getInstrument")
	public ResponseEntity<InstrumentEnvelope> getInstrument(
		@PathVariable String instrumentId, Principal principal, HttpServletResponse response) {
		currentUserIdResolver.resolve(principal);
		MarketDataApplicationService.InstrumentView instrument = marketData.getInstrument(parseUuid(instrumentId));
		return ResponseEntity.ok().eTag(quote(instrument.version()))
			.body(new InstrumentEnvelope(instrument, new ResponseMeta(requestId(response))));
	}

	@GetMapping(path = "/instruments/{instrumentId}/prices", name = "listInstrumentPrices")
	public ResponseEntity<PriceListEnvelope> listInstrumentPrices(
		@PathVariable String instrumentId,
		@RequestParam(name = "dateFrom", required = false) String rawFrom,
		@RequestParam(name = "dateTo", required = false) String rawTo,
		@RequestParam(name = "limit", required = false, defaultValue = "50") String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		currentUserIdResolver.resolve(principal);
		List<MarketDataApplicationService.PriceView> prices = marketData.listPrices(
			parseUuid(instrumentId), parseDate(rawFrom), parseDate(rawTo), parseLimit(rawLimit));
		return ResponseEntity.ok(new PriceListEnvelope(prices.stream().map(MarketDataController::priceView).toList(),
			new PageMeta(requestId(response), null, false)));
	}

	@PostMapping(path = "/instruments/{instrumentId}/manual-prices", consumes = MediaType.APPLICATION_JSON_VALUE, name = "createManualPrice")
	public ResponseEntity<?> createManualPrice(
		@PathVariable String instrumentId,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		PriceCommand command = parsePrice(body);
		UUID parsedInstrumentId = parseUuid(instrumentId);
		String key = idempotencyKey(request);
		IdempotencyExecution<MarketDataApplicationService.PriceView> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "createManualPrice", key,
			requestHash("/api/v1/instruments/" + parsedInstrumentId + "/manual-prices", command.hashPayload(), null), () -> {
				MarketDataApplicationService.PriceView created = marketData.createManualPrice(
					userId, parsedInstrumentId, command.priceType(), command.businessDate(), command.price(),
					command.currency(), command.reason(), requestId(response));
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "PRICE", created.id(), new IdempotencyResponse.ResourceReference(
						"/api/v1/instruments/" + parsedInstrumentId + "/prices/" + created.id(), null, null)));
			});
		if (execution.executedNow()) {
			return ResponseEntity.status(HttpStatus.CREATED)
				.body(new PriceEnvelope(priceView(execution.value()), new ResponseMeta(requestId(response))));
		}
		if (execution.replayed()) {
			return ResponseEntity.status(execution.response().responseStatus())
				.body(new PriceEnvelope(priceView(marketData.getPrice(execution.response().resourceId())), new ResponseMeta(requestId(response))));
		}
		throw idempotencyFailure(execution);
	}

	@PostMapping(path = "/instruments/{instrumentId}/price-corrections", consumes = MediaType.APPLICATION_JSON_VALUE, name = "correctInstrumentPrice")
	public ResponseEntity<?> correctInstrumentPrice(
		@PathVariable String instrumentId,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		CorrectionCommand command = parseCorrection(body);
		UUID parsedInstrumentId = parseUuid(instrumentId);
		String key = idempotencyKey(request);
		IdempotencyExecution<MarketDataApplicationService.PriceView> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "correctInstrumentPrice", key,
			requestHash("/api/v1/instruments/" + parsedInstrumentId + "/price-corrections", command.hashPayload(), null), () -> {
				MarketDataApplicationService.PriceView created = marketData.correctPrice(
					userId, command.supersedesPriceId(), command.price(), command.reason(), requestId(response));
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "PRICE", created.id(), new IdempotencyResponse.ResourceReference(
						"/api/v1/instruments/" + parsedInstrumentId + "/prices/" + created.id(), null, null)));
			});
		if (execution.executedNow()) {
			return ResponseEntity.status(HttpStatus.CREATED)
				.body(new PriceEnvelope(priceView(execution.value()), new ResponseMeta(requestId(response))));
		}
		if (execution.replayed()) {
			return ResponseEntity.status(execution.response().responseStatus())
				.body(new PriceEnvelope(priceView(marketData.getPrice(execution.response().resourceId())), new ResponseMeta(requestId(response))));
		}
		throw idempotencyFailure(execution);
	}

	@GetMapping(path = "/market-data/status", name = "getMarketDataStatus")
	public ResponseEntity<MarketDataStatusEnvelope> getMarketDataStatus(Principal principal, HttpServletResponse response) {
		currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new MarketDataStatusEnvelope(marketData.status(), new ResponseMeta(requestId(response))));
	}

	private RuntimeException idempotencyFailure(IdempotencyExecution<?> execution) {
		return switch (execution.status()) {
			case KEY_REUSED -> new MarketDataConflictException("幂等键已用于不同请求。");
			case REQUEST_IN_PROGRESS -> new MarketDataRetryableException();
			case SAFE_REPLAY_UNAVAILABLE -> new MarketDataConflictException("幂等结果无法安全重放。");
			default -> new MarketDataConflictException("幂等请求状态无效。");
		};
	}

	private InstrumentCommand parseInstrument(JsonNode body) {
		rejectUnknown(body, INSTRUMENT_FIELDS);
		return new InstrumentCommand(text(body, "instrumentType"), text(body, "name"), nullableText(body, "market"),
			text(body, "currency"), nullableText(body, "sourceCode"));
	}

	private PriceCommand parsePrice(JsonNode body) {
		rejectUnknown(body, PRICE_FIELDS);
		return new PriceCommand(text(body, "priceType"), parseDate(requiredText(body, "businessDate")), decimal(body, "price"),
			text(body, "currency"), text(body, "reason"));
	}

	private CorrectionCommand parseCorrection(JsonNode body) {
		rejectUnknown(body, CORRECTION_FIELDS);
		return new CorrectionCommand(parseUuid(requiredText(body, "supersedesPriceId")), decimal(body, "price"), text(body, "reason"));
	}

	private void rejectUnknown(JsonNode body, java.util.Set<String> fields) {
		if (body == null || !body.isObject()) {
			throw new MarketDataValidationException("请求体必须是 JSON 对象。");
		}
		for (String field : body.propertyNames()) {
			if (!fields.contains(field)) {
				throw new MarketDataValidationException("请求包含未知字段。");
			}
		}
	}

	private String text(JsonNode body, String field) {
		String value = requiredText(body, field);
		return value;
	}

	private String nullableText(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		return requiredText(body, field);
	}

	private String requiredText(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
			throw new MarketDataValidationException("请求字段无效。");
		}
		return value.textValue();
	}

	private BigDecimal decimal(JsonNode body, String field) {
		String raw = requiredText(body, field);
		try {
			return new BigDecimal(raw);
		} catch (RuntimeException exception) {
			throw new MarketDataValidationException("金额或价格格式无效。");
		}
	}

	private LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (RuntimeException exception) {
			throw new MarketDataValidationException("日期格式无效。");
		}
	}

	private UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (RuntimeException exception) {
			throw new MarketDataValidationException("资源 ID 格式无效。");
		}
	}

	private int parseLimit(String raw) {
		try {
			int limit = Integer.parseInt(raw);
			if (limit < 1 || limit > 200) {
				throw new NumberFormatException();
			}
			return limit;
		} catch (RuntimeException exception) {
			throw new MarketDataValidationException("limit 必须在 1 到 200 之间。");
		}
	}

	private String idempotencyKey(HttpServletRequest request) {
		String key = request.getHeader("Idempotency-Key");
		if (key == null || key.isBlank() || key.length() > 100) {
			throw new MarketDataValidationException("缺少有效的 Idempotency-Key。");
		}
		return key;
	}

	private String requestHash(String resource, Object payload, String ifMatch) {
		return IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, resource, payload, ifMatch);
	}

	private String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	/** 价格/净值属于 API Rate 字符串，不能直接暴露 application 层 BigDecimal。 */
	private static PriceResponseView priceView(MarketDataApplicationService.PriceView price) {
		return new PriceResponseView(price.id(), price.instrumentId(), price.priceType(), price.price().toPlainString(),
			price.currency(), price.businessDate(), price.source(), price.revision(), price.sourceUpdatedAt(), price.fetchedAt(), price.freshness());
	}

	private static String quote(int version) {
		return "\"" + version + "\"";
	}

	private record InstrumentCommand(String instrumentType, String name, String market, String currency, String sourceCode) {
		Map<String, Object> hashPayload() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("instrumentType", instrumentType);
			map.put("name", name);
			map.put("market", market);
			map.put("currency", currency);
			map.put("sourceCode", sourceCode);
			return map;
		}
	}

	private record PriceCommand(String priceType, LocalDate businessDate, BigDecimal price, String currency, String reason) {
		Map<String, Object> hashPayload() {
			return Map.of("priceType", priceType, "businessDate", businessDate, "price", price, "currency", currency, "reason", reason);
		}
	}

	private record CorrectionCommand(UUID supersedesPriceId, BigDecimal price, String reason) {
		Map<String, Object> hashPayload() {
			return Map.of("supersedesPriceId", supersedesPriceId, "price", price, "reason", reason);
		}
	}

	public record ResponseMeta(String requestId) {
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}

	public record InstrumentEnvelope(MarketDataApplicationService.InstrumentView data, ResponseMeta meta) {
	}

	public record InstrumentListEnvelope(List<MarketDataApplicationService.InstrumentView> data, PageMeta meta) {
	}

	public record PriceEnvelope(PriceResponseView data, ResponseMeta meta) {
	}

	public record PriceListEnvelope(List<PriceResponseView> data, PageMeta meta) {
	}

	public record PriceResponseView(
		UUID id, UUID instrumentId, String priceType, String price, String currency, LocalDate businessDate,
		String source, int revision, Instant sourceUpdatedAt, Instant fetchedAt, String freshness) {
	}

	public record MarketDataStatusEnvelope(MarketDataApplicationService.MarketDataStatusView data, ResponseMeta meta) {
	}
}
