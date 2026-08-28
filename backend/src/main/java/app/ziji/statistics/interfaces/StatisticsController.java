package app.ziji.statistics.interfaces;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.statistics.application.StatisticsSeriesResult;
import app.ziji.statistics.application.StatisticsValidationException;
import app.ziji.user.application.CurrentUserIdResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** GET /api/v1/statistics/*：资产、现金流与账户趋势；日期与粒度校验失败返回 400。 */
@RestController
public class StatisticsController {

	private final app.ziji.statistics.application.StatisticsQueryUseCase statistics;
	private final CurrentUserIdResolver currentUserIdResolver;

	public StatisticsController(
		app.ziji.statistics.application.StatisticsQueryUseCase statistics,
		CurrentUserIdResolver currentUserIdResolver) {
		this.statistics = statistics;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(path = "/api/v1/statistics/assets", name = "getAssetStatistics")
	public ResponseEntity<StatisticsSeriesEnvelope> getAssetStatistics(
		@RequestParam(name = "dateFrom", required = false) String dateFrom,
		@RequestParam(name = "dateTo", required = false) String dateTo,
		@RequestParam(name = "granularity", required = false) String granularity,
		Principal principal,
		HttpServletResponse response) {
		return series("assets", dateFrom, dateTo, granularity, principal, response);
	}

	@GetMapping(path = "/api/v1/statistics/cash-flow", name = "getCashFlowStatistics")
	public ResponseEntity<StatisticsSeriesEnvelope> getCashFlowStatistics(
		@RequestParam(name = "dateFrom", required = false) String dateFrom,
		@RequestParam(name = "dateTo", required = false) String dateTo,
		@RequestParam(name = "granularity", required = false) String granularity,
		Principal principal,
		HttpServletResponse response) {
		return series("cash-flow", dateFrom, dateTo, granularity, principal, response);
	}

	@GetMapping(path = "/api/v1/statistics/accounts", name = "getAccountStatistics")
	public ResponseEntity<StatisticsSeriesEnvelope> getAccountStatistics(
		@RequestParam(name = "dateFrom", required = false) String dateFrom,
		@RequestParam(name = "dateTo", required = false) String dateTo,
		@RequestParam(name = "granularity", required = false) String granularity,
		Principal principal,
		HttpServletResponse response) {
		return series("accounts", dateFrom, dateTo, granularity, principal, response);
	}

	private ResponseEntity<StatisticsSeriesEnvelope> series(
		String series, String dateFrom, String dateTo, String granularity,
		Principal principal, HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		LocalDate from = parseDate(dateFrom, "dateFrom");
		LocalDate to = parseDate(dateTo, "dateTo");
		StatisticsSeriesResult result = switch (series) {
			case "assets" -> statistics.getAssetStatistics(userId, from, to, granularity);
			case "cash-flow" -> statistics.getCashFlowStatistics(userId, from, to, granularity);
			default -> statistics.getAccountStatistics(userId, from, to, granularity);
		};
		List<PointView> points = result.points().stream()
			.map(point -> new PointView(point.businessDate().toString(), point.values()))
			.toList();
		return ResponseEntity.ok(new StatisticsSeriesEnvelope(
			new SeriesView(result.baseCurrency(), result.valuationRevision(), points),
			new ResponseMeta(requestId(response))));
	}

	private LocalDate parseDate(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException exception) {
			throw new StatisticsValidationException(field + " 必须是 YYYY-MM-DD 日期。");
		}
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	@ExceptionHandler(StatisticsValidationException.class)
	org.springframework.http.ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail
			.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_REQUEST, "请求校验失败");
		problem.setType(java.net.URI.create("https://ziji.app/problems/validation-error"));
		problem.setTitle(org.springframework.http.HttpStatus.BAD_REQUEST.getReasonPhrase());
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		problem.setProperty("code", "VALIDATION_ERROR");
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		return problem;
	}

	public record StatisticsSeriesEnvelope(SeriesView data, ResponseMeta meta) {
	}

	public record ResponseMeta(String requestId) {
	}

	/** OpenAPI StatisticsPoint.values 为 additionalProperties Money；键为指标名或账户 ID。 */
	public record SeriesView(String baseCurrency, int valuationRevision, List<PointView> points) {
	}

	public record PointView(String businessDate, Map<String, String> values) {
	}
}
