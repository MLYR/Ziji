package app.ziji.statistics.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.ziji.statistics.application.DashboardResult;
import app.ziji.user.application.CurrentUserIdResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.Principal;

/** GET /api/v1/dashboard：只读当前指标；历史 projectionAsOf 语义由后续统计任务实现。 */
@RestController
public class DashboardController {

	private final app.ziji.statistics.application.DashboardQueryUseCase dashboard;
	private final CurrentUserIdResolver currentUserIdResolver;

	public DashboardController(
		app.ziji.statistics.application.DashboardQueryUseCase dashboard,
		CurrentUserIdResolver currentUserIdResolver) {
		this.dashboard = dashboard;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(path = "/api/v1/dashboard", name = "getDashboard")
	public ResponseEntity<DashboardEnvelope> getDashboard(
		@RequestParam(name = "asOf", required = false) Instant asOf,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		if (asOf != null) {
			throw new app.ziji.statistics.application.DashboardValidationException("Dashboard 暂不支持历史估值时点。");
		}
		DashboardResult result = dashboard.getDashboard(userId);
		return ResponseEntity.ok(new DashboardEnvelope(view(result), new ResponseMeta(requestId(response))));
	}

	private DashboardView view(DashboardResult result) {
		int scale = "JPY".equals(result.baseCurrency()) ? 0 : 2;
		return new DashboardView(
			result.baseCurrency(), result.asOf(), result.asOfSequence(), result.valuationRevision(),
			result.recalculatedAt(), result.projectionStatus(),
			new SummaryView(
				money(result.summary().totalAssets(), scale), money(result.summary().availableFunds(), scale),
				money(result.summary().investmentAssets(), scale), money(result.summary().totalLiabilities(), scale),
				money(result.summary().netAssets(), scale)),
			new AttributionView(
				money(result.changeAttribution().income(), scale), money(result.changeAttribution().expense(), scale),
				money(result.changeAttribution().market(), scale), money(result.changeAttribution().fx(), scale),
				money(result.changeAttribution().adjustment(), scale), money(result.changeAttribution().inclusion(), scale)),
			result.distribution().stream()
				.map(item -> new DistributionItemView(
					item.key(), item.label(), money(item.amount(), scale), money(item.ratio(), 6)))
				.toList(),
			new InvestmentOverviewView(
				result.investmentOverview().baseCurrency(), money(result.investmentOverview().brokerCash(), scale),
				money(result.investmentOverview().positionMarketValue(), scale),
				money(result.investmentOverview().totalInvestmentAssets(), scale),
				result.investmentOverview().unpricedInstrumentCount()),
			result.dataQualityWarnings().stream()
				.map(warning -> new QualityWarningView(warning.code(), warning.affectedCount()))
				.toList());
	}

	private String money(BigDecimal value, int scale) {
		return value.setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString();
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	@ExceptionHandler(app.ziji.statistics.application.DashboardValidationException.class)
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

	public record DashboardEnvelope(DashboardView data, ResponseMeta meta) {
	}

	public record ResponseMeta(String requestId) {
	}

	public record DashboardView(
		String baseCurrency,
		Instant asOf,
		long asOfSequence,
		int valuationRevision,
		Instant recalculatedAt,
		String projectionStatus,
		SummaryView summary,
		AttributionView changeAttribution,
		List<DistributionItemView> distribution,
		InvestmentOverviewView investmentOverview,
		List<QualityWarningView> dataQualityWarnings) {
	}

	public record SummaryView(
		String totalAssets,
		String availableFunds,
		String investmentAssets,
		String totalLiabilities,
		String netAssets) {
	}

	public record AttributionView(
		String income,
		String expense,
		String market,
		String fx,
		String adjustment,
		String inclusion) {
	}

	public record DistributionItemView(String key, String label, String amount, String ratio) {
	}

	public record InvestmentOverviewView(
		String baseCurrency,
		String brokerCash,
		String positionMarketValue,
		String totalInvestmentAssets,
		int unpricedInstrumentCount) {
	}

	public record QualityWarningView(String code, int affectedCount) {
	}
}
