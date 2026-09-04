package app.ziji.investment.interfaces;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentBusinessRuleException;
import app.ziji.investment.application.InvestmentNotVisibleException;
import app.ziji.investment.application.InvestmentPermissionDeniedException;
import app.ziji.investment.application.InvestmentPerformanceResult;
import app.ziji.investment.application.InvestmentPositionResult;
import app.ziji.investment.application.InvestmentRequestValidationException;
import app.ziji.investment.application.InvestmentReturnCalendarResult;
import app.ziji.investment.application.InvestmentReturnDayDetailsResult;
import app.ziji.investment.application.InvestmentTradeCommand;
import app.ziji.investment.application.InvestmentTradeResult;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.investment.domain.ReturnStatus;
import app.ziji.investment.domain.XirrStatus;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.CurrentUserTimezonePort;
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

/** 投资交易、持仓、绩效和收益日历的 HTTP 边界。 */
@RestController
@RequestMapping("/api/v1")
public class InvestmentController {

	private static final java.util.Set<String> TRADE_FIELDS = java.util.Set.of(
		"id", "side", "investmentAccountId", "instrumentId", "quantity", "unitPrice", "dividendAmount",
		"currency", "feeAmount", "taxAmount", "tradeAt");

	private final InvestmentApplicationService investments;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final CurrentUserTimezonePort timezones;
	private final UnifiedIdempotencyService idempotency;

	public InvestmentController(
		InvestmentApplicationService investments,
		CurrentUserIdResolver currentUserIdResolver,
		CurrentUserTimezonePort timezones,
		UnifiedIdempotencyService idempotency) {
		this.investments = investments;
		this.currentUserIdResolver = currentUserIdResolver;
		this.timezones = timezones;
		this.idempotency = idempotency;
	}

	@GetMapping(path = "/investment-trades", name = "listInvestmentTrades")
	public ResponseEntity<InvestmentTradeListEnvelope> listInvestmentTrades(
		@RequestParam(name = "accountId", required = false) String accountId,
		@RequestParam(name = "dateFrom", required = false) String dateFrom,
		@RequestParam(name = "dateTo", required = false) String dateTo,
		@RequestParam(name = "limit", required = false, defaultValue = "50") String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new InvestmentTradeListEnvelope(
			investments.listTrades(userId, optionalUuid(accountId), parseDate(dateFrom), parseDate(dateTo), parseLimit(rawLimit)).stream()
				.map(InvestmentController::tradeView).toList(),
			new PageMeta(requestId(response), null, false)));
	}

	@PostMapping(path = "/investment-trades", consumes = MediaType.APPLICATION_JSON_VALUE, name = "createInvestmentTrade")
	public ResponseEntity<?> createInvestmentTrade(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		InvestmentTradeCommand command = parseTrade(body, userId);
		String key = idempotencyKey(request);
		IdempotencyExecution<app.ziji.investment.application.InvestmentTradeResult> execution = idempotency.executeAuthenticated(
			userId, 1, "createInvestmentTrade", key,
			IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, "/api/v1/investment-trades",
				tradePayload(command), null), () -> {
				var created = investments.createTrade(command);
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "INVESTMENT_TRADE", created.id(), new IdempotencyResponse.ResourceReference(
						"/api/v1/investment-trades/" + created.id(), null, null)));
			});
		if (execution.executedNow()) {
			return ResponseEntity.status(HttpStatus.CREATED)
				.body(new InvestmentTradeEnvelope(tradeView(execution.value()), new ResponseMeta(requestId(response))));
		}
		if (execution.replayed()) {
			return ResponseEntity.status(execution.response().responseStatus())
				.body(new InvestmentTradeEnvelope(
					tradeView(investments.listTrades(userId, execution.response().resourceId() == null ? null : command.investmentAccountId(), null, null, 200)
						.stream().filter(item -> execution.response().resourceId().equals(item.id())).findFirst()
						.orElseThrow(InvestmentNotVisibleException::new)),
					new ResponseMeta(requestId(response))));
		}
		throw switch (execution.status()) {
			case KEY_REUSED -> new InvestmentApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false);
			case REQUEST_IN_PROGRESS -> new InvestmentApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", true);
			case SAFE_REPLAY_UNAVAILABLE -> new InvestmentApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", false);
			default -> new InvestmentApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", false);
		};
	}

	@GetMapping(path = "/investment-accounts/{accountId}/positions", name = "listInvestmentPositions")
	public ResponseEntity<PositionListEnvelope> listInvestmentPositions(
		@PathVariable String accountId,
		@RequestParam(name = "asOf", required = false) String asOf,
		@RequestParam(name = "limit", required = false, defaultValue = "200") String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new PositionListEnvelope(
			investments.listPositions(userId, parseUuid(accountId), parseInstant(asOf), parseLimit(rawLimit)).stream()
				.map(InvestmentController::positionView).toList(),
			new PageMeta(requestId(response), null, false)));
	}

	@GetMapping(path = "/investment-accounts/{accountId}/performance", name = "getInvestmentPerformance")
	public ResponseEntity<InvestmentPerformanceEnvelope> getInvestmentPerformance(
		@PathVariable String accountId,
		@RequestParam(name = "dateFrom", required = false) String dateFrom,
		@RequestParam(name = "dateTo", required = false) String dateTo,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		if (dateFrom != null && dateTo != null && parseDate(dateFrom).isAfter(parseDate(dateTo))) {
			throw new InvestmentRequestValidationException("dateFrom 不能晚于 dateTo。");
		}
		ZoneId zone = timezones.currentTimezone(userId);
		Instant from = dateFrom == null ? null : parseDate(dateFrom).atStartOfDay(zone).toInstant();
		Instant to = dateTo == null ? null : parseDate(dateTo).plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1);
		return ResponseEntity.ok(new InvestmentPerformanceEnvelope(
			performanceView(investments.performance(userId, parseUuid(accountId), from, to)), new ResponseMeta(requestId(response))));
	}

	@GetMapping(path = "/investments/overview", name = "getInvestmentOverview")
	public ResponseEntity<InvestmentOverviewEnvelope> getInvestmentOverview(
		@RequestParam(name = "asOf", required = false) String asOf,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new InvestmentOverviewEnvelope(
			overviewView(investments.overview(userId, parseInstant(asOf))), new ResponseMeta(requestId(response))));
	}

	@GetMapping(path = "/investment-returns/calendar", name = "getInvestmentReturnCalendar")
	public ResponseEntity<InvestmentReturnCalendarEnvelope> getInvestmentReturnCalendar(
		@RequestParam String month,
		@RequestParam String scopeType,
		@RequestParam(name = "instrumentId", required = false) String instrumentId,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new InvestmentReturnCalendarEnvelope(
			investments.returnCalendar(userId, parseMonth(month), scopeType, optionalUuid(instrumentId)),
			new ResponseMeta(requestId(response))));
	}

	@GetMapping(path = "/investment-returns/calendar/{businessDate}/details", name = "getInvestmentReturnDayDetails")
	public ResponseEntity<InvestmentReturnDayDetailsEnvelope> getInvestmentReturnDayDetails(
		@PathVariable String businessDate,
		@RequestParam String scopeType,
		@RequestParam(name = "instrumentId", required = false) String instrumentId,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		return ResponseEntity.ok(new InvestmentReturnDayDetailsEnvelope(
			investments.returnDayDetails(userId, parseDate(businessDate), scopeType, optionalUuid(instrumentId)),
			new ResponseMeta(requestId(response))));
	}

	private InvestmentTradeCommand parseTrade(JsonNode body, UUID userId) {
		if (body == null || !body.isObject()) {
			throw new InvestmentRequestValidationException("请求体必须是 JSON 对象。");
		}
		for (String field : body.propertyNames()) {
			if (!TRADE_FIELDS.contains(field)) {
				throw new InvestmentRequestValidationException("请求包含未知字段。");
			}
		}
		InvestmentSideParser side = new InvestmentSideParser(requiredText(body, "side"));
		UUID accountId = parseUuid(requiredText(body, "investmentAccountId"));
		UUID instrumentId = parseUuid(requiredText(body, "instrumentId"));
		BigDecimal quantity = optionalDecimal(body, "quantity");
		BigDecimal unitPrice = optionalDecimal(body, "unitPrice");
		BigDecimal dividend = optionalDecimal(body, "dividendAmount");
		BigDecimal fee = decimal(body, "feeAmount");
		BigDecimal tax = decimal(body, "taxAmount");
		Instant tradeAt = parseInstant(requiredText(body, "tradeAt"));
		return new InvestmentTradeCommand(
			userId, optionalUuid(nullableText(body, "id")), accountId, instrumentId, side.value(), quantity, unitPrice, dividend,
			requiredText(body, "currency"), fee, tax, tradeAt, timezones.currentTimezone(userId).getId(), null);
	}

	private Map<String, Object> tradePayload(InvestmentTradeCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", command.tradeId());
		payload.put("side", command.side());
		payload.put("investmentAccountId", command.investmentAccountId());
		payload.put("instrumentId", command.instrumentId());
		payload.put("quantity", command.quantity());
		payload.put("unitPrice", command.unitPrice());
		payload.put("dividendAmount", command.dividendAmount());
		payload.put("currency", command.currency());
		payload.put("feeAmount", command.feeAmount());
		payload.put("taxAmount", command.taxAmount());
		payload.put("tradeAt", command.tradeAt());
		return payload;
	}

	private String requiredText(JsonNode body, String field) {
		String value = nullableText(body, field);
		if (value == null || value.isBlank()) {
			throw new InvestmentRequestValidationException("请求字段无效。");
		}
		return value;
	}

	private String nullableText(JsonNode body, String field) {
		JsonNode value = body.get(field);
		return value == null || value.isNull() ? null : value.isTextual() ? value.textValue() : null;
	}

	private BigDecimal decimal(JsonNode body, String field) {
		try {
			return new BigDecimal(requiredText(body, field));
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("金额格式无效。");
		}
	}

	private BigDecimal optionalDecimal(JsonNode body, String field) {
		String value = nullableText(body, field);
		if (value == null) {
			return null;
		}
		try {
			return new BigDecimal(value);
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("数量或价格格式无效。");
		}
	}

	private UUID optionalUuid(String raw) {
		return raw == null || raw.isBlank() ? null : parseUuid(raw);
	}

	private UUID parseUuid(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("资源 ID 格式无效。");
		}
	}

	private LocalDate parseDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("日期格式无效。");
		}
	}

	private Instant parseInstant(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(raw).toInstant();
		} catch (RuntimeException exception) {
			try {
				return Instant.parse(raw);
			} catch (RuntimeException ignored) {
				throw new InvestmentRequestValidationException("时间格式无效。");
			}
		}
	}

	private YearMonth parseMonth(String raw) {
		try {
			return YearMonth.parse(raw);
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("月份格式无效。");
		}
	}

	private int parseLimit(String raw) {
		try {
			int result = Integer.parseInt(raw);
			if (result < 1 || result > 200) throw new NumberFormatException();
			return result;
		} catch (RuntimeException exception) {
			throw new InvestmentRequestValidationException("limit 必须在 1 到 200 之间。");
		}
	}

	private String idempotencyKey(HttpServletRequest request) {
		String key = request.getHeader("Idempotency-Key");
		if (key == null || key.isBlank() || key.length() > 100) {
			throw new InvestmentRequestValidationException("缺少有效的 Idempotency-Key。");
		}
		return key;
	}

	private String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	/** HTTP 只输出 OpenAPI 约定的字符串金额，避免 BigDecimal 被序列化为 JSON 数字。 */
	private static InvestmentTradeView tradeView(InvestmentTradeResult result) {
		return new InvestmentTradeView(result.id(), result.transactionId(), result.investmentAccountId(), result.instrumentId(),
			result.side().name(), plain(result.quantity()), plain(result.unitPrice()), result.currency(), plain(result.grossAmount()),
			plain(result.feeAmount()), plain(result.taxAmount()), result.tradeAt());
	}

	private static InvestmentPositionView positionView(InvestmentPositionResult result) {
		return new InvestmentPositionView(result.instrumentId(), plain(result.quantity()), plain(result.costBasis()),
			plain(result.averageCost()), result.valuationStatus().name(), plain(result.marketPrice()), plain(result.marketValue()),
			plain(result.unrealizedProfit()), result.priceAsOf());
	}

	private static InvestmentPerformanceView performanceView(InvestmentPerformanceResult result) {
		return new InvestmentPerformanceView(result.currency(), plain(result.realizedProfit()), plain(result.unrealizedProfit()),
			plain(result.dividends()), plain(result.fees()), plain(result.taxes()), plain(result.annualizedReturn()),
			plain(result.xirr()), xirrStatus(result.xirrStatus()));
	}

	private static InvestmentOverviewView overviewView(InvestmentApplicationService.InvestmentOverviewResult result) {
		return new InvestmentOverviewView(result.baseCurrency(), plain(result.brokerCash()), plain(result.positionMarketValue()),
			plain(result.totalInvestmentAssets()), result.unpricedInstrumentCount());
	}

	private static InvestmentReturnCalendarView calendarView(InvestmentReturnCalendarResult result) {
		return new InvestmentReturnCalendarView(result.scopeType(), result.instrumentId(), result.baseCurrency(), result.month().toString(),
			result.valuationRevision(), result.asOf(), result.recalculatedAt(), result.summaryStatus(), plain(result.monthlyProfit()),
			plain(result.monthlyReturnRate()), result.profitDayCount(), result.lossDayCount(), result.zeroDayCount(),
			result.days().stream().map(InvestmentController::calendarDayView).toList(), warningViews(result.dataQualityWarnings()));
	}

	private static InvestmentReturnDayView calendarDayView(InvestmentReturnCalendarResult.InvestmentReturnDayResult result) {
		return new InvestmentReturnDayView(result.businessDate(), result.status().name(), plain(result.dailyProfit()),
			plain(result.dailyReturnRate()), result.missingInstrumentCount());
	}

	private static InvestmentReturnDayDetailsView dayDetailsView(InvestmentReturnDayDetailsResult result) {
		return new InvestmentReturnDayDetailsView(result.scopeType(), result.instrumentId(), result.businessDate(), result.baseCurrency(),
			result.valuationRevision(), result.asOf(), result.status().name(), plain(result.beginValue()), plain(result.endValue()),
			plain(result.netCashFlow()), plain(result.dailyProfit()), plain(result.dailyReturnRate()), plain(result.marketEffect()),
			plain(result.fxEffect()), plain(result.dividends()), plain(result.fees()), plain(result.taxes()),
			result.contributions().stream().map(InvestmentController::contributionView).toList(), warningViews(result.dataQualityWarnings()));
	}

	private static InvestmentReturnContributionView contributionView(InvestmentReturnDayDetailsResult.Contribution result) {
		return new InvestmentReturnContributionView(result.contributionType(), result.instrumentId(), result.label(), plain(result.profit()),
			plain(result.returnRate()), result.status().name(), result.priceAsOf());
	}

	private static List<DataQualityWarningView> warningViews(List<String> warnings) {
		return warnings.stream().distinct().map(code -> new DataQualityWarningView(code, 1)).toList();
	}

	private static String plain(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	private static String xirrStatus(XirrStatus status) {
		return switch (status) {
			case AVAILABLE -> "AVAILABLE";
			case INVALID_CASH_FLOWS -> "UNPRICED";
			case INSUFFICIENT_CASH_FLOWS, NON_CONVERGENT -> "INSUFFICIENT_CASH_FLOWS";
		};
	}

	private record InvestmentSideParser(String raw) {
		InvestmentSide value() {
			try {
				return app.ziji.investment.domain.InvestmentSide.valueOf(raw);
			} catch (RuntimeException exception) {
				throw new InvestmentRequestValidationException("成交方向无效。");
			}
		}
	}

	public record ResponseMeta(String requestId) {
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}

	public record InvestmentTradeEnvelope(InvestmentTradeView data, ResponseMeta meta) {
	}

	public record InvestmentTradeListEnvelope(List<InvestmentTradeView> data, PageMeta meta) {
	}

	public record PositionListEnvelope(List<InvestmentPositionView> data, PageMeta meta) {
	}

	public record InvestmentPerformanceEnvelope(InvestmentPerformanceView data, ResponseMeta meta) {
	}

	public record InvestmentOverviewEnvelope(InvestmentOverviewView data, ResponseMeta meta) {
	}

	public record InvestmentReturnCalendarEnvelope(InvestmentReturnCalendarView data, ResponseMeta meta) {
		public InvestmentReturnCalendarEnvelope(InvestmentReturnCalendarResult result, ResponseMeta meta) {
			this(calendarView(result), meta);
		}
	}

	public record InvestmentReturnDayDetailsEnvelope(InvestmentReturnDayDetailsView data, ResponseMeta meta) {
		public InvestmentReturnDayDetailsEnvelope(InvestmentReturnDayDetailsResult result, ResponseMeta meta) {
			this(dayDetailsView(result), meta);
		}
	}

	public record InvestmentTradeView(
		UUID id, UUID transactionId, UUID investmentAccountId, UUID instrumentId, String side, String quantity, String unitPrice,
		String currency, String grossAmount, String feeAmount, String taxAmount, Instant tradeAt) {
	}

	public record InvestmentPositionView(
		UUID instrumentId, String quantity, String costBasis, String averageCost, String valuationStatus, String marketPrice,
		String marketValue, String unrealizedProfit, LocalDate priceAsOf) {
	}

	public record InvestmentPerformanceView(
		String currency, String realizedProfit, String unrealizedProfit, String dividends, String fees, String taxes,
		String annualizedReturn, String xirr, String xirrStatus) {
	}

	public record InvestmentOverviewView(
		String baseCurrency, String brokerCash, String positionMarketValue, String totalInvestmentAssets, int unpricedInstrumentCount) {
	}

	public record InvestmentReturnCalendarView(
		String scopeType, UUID instrumentId, String baseCurrency, String month, int valuationRevision, Instant asOf, Instant recalculatedAt,
		String summaryStatus, String monthlyProfit, String monthlyReturnRate, int profitDayCount, int lossDayCount, int zeroDayCount,
		List<InvestmentReturnDayView> days, List<DataQualityWarningView> dataQualityWarnings) {
	}

	public record InvestmentReturnDayView(
		LocalDate businessDate, String status, String dailyProfit, String dailyReturnRate, int missingInstrumentCount) {
	}

	public record InvestmentReturnDayDetailsView(
		String scopeType, UUID instrumentId, LocalDate businessDate, String baseCurrency, int valuationRevision, Instant asOf,
		String status, String beginValue, String endValue, String netCashFlow, String dailyProfit, String dailyReturnRate,
		String marketEffect, String fxEffect, String dividends, String fees, String taxes,
		List<InvestmentReturnContributionView> contributions, List<DataQualityWarningView> dataQualityWarnings) {
	}

	public record InvestmentReturnContributionView(
		String contributionType, UUID instrumentId, String label, String profit, String returnRate, String status, LocalDate priceAsOf) {
	}

	public record DataQualityWarningView(String code, int affectedCount) {
	}
}
