package app.ziji.investment.interfaces;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.ZoneId;
import java.util.UUID;

import app.ziji.investment.application.InvestmentApplicationService;
import app.ziji.investment.application.InvestmentPerformanceResult;
import app.ziji.investment.domain.XirrStatus;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.CurrentUserTimezonePort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投资模块接口层单元测试：覆盖参数校验及返回值契约对齐。
 */
class InvestmentMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");

	@Test
	void getInvestmentPerformanceReturnsBadRequestWhenDateFromAfterDateTo() throws Exception {
		Fixture fixture = fixture();

		fixture.mvc.perform(get("/api/v1/investment-accounts/{accountId}/performance", ACCOUNT_ID)
				.param("dateFrom", "2026-08-10")
				.param("dateTo", "2026-08-01")
				.principal(principal()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void getInvestmentPerformanceReturnsAnnualizedReturnAndXirr() throws Exception {
		Fixture fixture = fixture();
		InvestmentPerformanceResult performance = new InvestmentPerformanceResult(
			"CNY",
			new BigDecimal("100.00"),
			new BigDecimal("200.00"),
			new BigDecimal("50.00"),
			new BigDecimal("5.00"),
			new BigDecimal("2.00"),
			new BigDecimal("300.00"),
			new BigDecimal("0.300000"),
			new BigDecimal("0.123456"),
			new BigDecimal("0.123456"),
			XirrStatus.AVAILABLE
		);
		when(fixture.investments.performance(any(), any(), any(), any())).thenReturn(performance);

		fixture.mvc.perform(get("/api/v1/investment-accounts/{accountId}/performance", ACCOUNT_ID)
				.param("dateFrom", "2026-08-01")
				.param("dateTo", "2026-08-10")
				.principal(principal()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.currency").value("CNY"))
			.andExpect(jsonPath("$.data.annualizedReturn").value("0.123456"))
			.andExpect(jsonPath("$.data.xirr").value("0.123456"))
			.andExpect(jsonPath("$.data.xirrStatus").value("AVAILABLE"));
	}

	private static Fixture fixture() {
		InvestmentApplicationService investments = mock(InvestmentApplicationService.class);
		CurrentUserIdResolver userIdResolver = principal -> USER_ID;
		CurrentUserTimezonePort timezoneResolver = userId -> ZoneId.of("Asia/Shanghai");
		UnifiedIdempotencyService idempotency = mock(UnifiedIdempotencyService.class);
		InvestmentController controller = new InvestmentController(investments, userIdResolver, timezoneResolver, idempotency);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new InvestmentApiExceptionHandler())
			.build();
		return new Fixture(mvc, investments);
	}

	private static Principal principal() {
		return () -> USER_ID.toString();
	}

	private record Fixture(MockMvc mvc, InvestmentApplicationService investments) {
	}
}
