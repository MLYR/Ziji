package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceResult;
import app.ziji.account.application.AccountBalanceResult.LiquidityStatus;
import app.ziji.account.application.AccountBalanceUseCase;
import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.application.AccountPage;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryUseCase;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountPatch;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.interfaces.UserApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 余额 HTTP seam 的响应格式、时点归一化和错误优先级测试。 */
class AccountBalanceMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final Instant AS_OF = Instant.parse("2026-08-16T04:05:06Z");

	@Test
	void getReturnsDecimalStringBalanceAndNoEtag() throws Exception {
		FakeBalanceUseCase balance = new FakeBalanceUseCase(normalBalance());

		mvc(balance).perform(get(path()).principal(principal())
				.header("If-Match", "\"999\"").header("Idempotency-Key", "ignored-on-read"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID.toString()))
			.andExpect(jsonPath("$.data.currency").value("CNY"))
			.andExpect(jsonPath("$.data.ledgerBalance").value("100.00"))
			.andExpect(jsonPath("$.data.unavailableAmount").value("15.00"))
			.andExpect(jsonPath("$.data.unavailableBreakdown.frozen").value("5.00"))
			.andExpect(jsonPath("$.data.unavailableBreakdown.inTransit").value("7.00"))
			.andExpect(jsonPath("$.data.unavailableBreakdown.reserved").value("3.00"))
			.andExpect(jsonPath("$.data.availableBalance").value("85.00"))
			.andExpect(jsonPath("$.data.liquidityStatus").value("NORMAL"))
			.andExpect(jsonPath("$.data.asOf").value(AS_OF.toString()))
			.andExpect(jsonPath("$.data.asOfSequence").value(0))
			.andExpect(jsonPath("$.meta.requestId").value("unknown"));
	}

	@Test
	void explicitOffsetIsNormalizedToUtcBeforeCallingUseCase() throws Exception {
		FakeBalanceUseCase balance = new FakeBalanceUseCase(normalBalance());

		mvc(balance).perform(get(path()).principal(principal())
				.param("asOf", "2026-08-16T12:05:06+08:00"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.asOf").value(AS_OF.toString()));

		assertEquals(AS_OF, balance.requestedAsOf);
		assertEquals(USER_ID, balance.userId);
		assertEquals(ACCOUNT_ID, balance.accountId);
	}

	@Test
	void asOfRequiresExplicitOffsetAndRejectsInvalidDate() throws Exception {
		FakeBalanceUseCase balance = new FakeBalanceUseCase(normalBalance());
		for (String value : List.of("2026-08-16T12:05:06", "2026-02-30T12:05:06Z", "not-a-date")) {
			mvc(balance).perform(get(path()).principal(principal()).param("asOf", value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(header().doesNotExist("ETag"));
		}
		assertEquals(0, balance.calls);
	}

	@Test
	void authenticationVisibilityAndPersistenceErrorsUseTheContractCodes() throws Exception {
		mvc(new FakeBalanceUseCase(normalBalance())).perform(get(path()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

		mvc((userId, accountId, asOf) -> {
			throw new AccountNotVisibleException();
		}).perform(get(path()).principal(principal()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		mvc((userId, accountId, asOf) -> {
			throw app.ziji.account.application.AccountBalanceException.persistence(
				new IllegalStateException("hidden persistence detail"));
		}).perform(get(path()).principal(principal()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value("服务器处理请求失败"));
	}

	@Test
	void negativeAvailableBalanceIsRenderedWithoutClamping() throws Exception {
		AccountBalanceResult result = new AccountBalanceResult(
			ACCOUNT_ID, AccountCurrency.CNY, new BigDecimal("10.00"), new BigDecimal("11.00"),
			new AccountBalanceResult.UnavailableBreakdown(new BigDecimal("11.00"), BigDecimal.ZERO, BigDecimal.ZERO),
			new BigDecimal("-1.00"), LiquidityStatus.NEGATIVE_AVAILABLE, AS_OF, 0);

		mvc(new FakeBalanceUseCase(result)).perform(get(path()).principal(principal()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.availableBalance").value("-1.00"))
			.andExpect(jsonPath("$.data.liquidityStatus").value("NEGATIVE_AVAILABLE"));
	}

	private MockMvc mvc(AccountBalanceUseCase balanceUseCase) {
		CurrentUserIdResolver resolver = principal -> {
			if (principal == null) {
				throw new app.ziji.user.application.UserAuthenticationException();
			}
			return USER_ID;
		};
		return MockMvcBuilders.standaloneSetup(new AccountController(
			new NoopAccountQueryUseCase(), balanceUseCase, null, resolver, null, null))
			.setControllerAdvice(new AccountApiExceptionHandler(), new UserApiExceptionHandler())
			.build();
	}

	private String path() {
		return "/api/v1/accounts/" + ACCOUNT_ID + "/balance";
	}

	private java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private AccountBalanceResult normalBalance() {
		return new AccountBalanceResult(
			ACCOUNT_ID, AccountCurrency.CNY, new BigDecimal("100.00"), new BigDecimal("15.00"),
			new AccountBalanceResult.UnavailableBreakdown(
				new BigDecimal("5.00"), new BigDecimal("7.00"), new BigDecimal("3.00")),
			new BigDecimal("85.00"), LiquidityStatus.NORMAL, AS_OF, 0);
	}

	private static final class FakeBalanceUseCase implements AccountBalanceUseCase {
		private final AccountBalanceResult result;
		private int calls;
		private UUID userId;
		private UUID accountId;
		private Instant requestedAsOf;

		private FakeBalanceUseCase(AccountBalanceResult result) {
			this.result = result;
		}

		@Override
		public AccountBalanceResult getBalance(UUID userId, UUID accountId, Instant requestedAsOf) {
			calls++;
			this.userId = userId;
			this.accountId = accountId;
			this.requestedAsOf = requestedAsOf;
			return result;
		}
	}

	private static final class NoopAccountQueryUseCase implements AccountQueryUseCase {
		@Override
		public AccountPage listVisibleAccounts(UUID userId, Integer limit, String cursor) {
			return new AccountPage(List.of(), null, false);
		}

		@Override
		public AccountQueryResult getVisibleAccount(UUID userId, UUID accountId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public AccountQueryResult updateAccount(UUID userId, UUID accountId, int expectedVersion, AccountPatch patch) {
			throw new UnsupportedOperationException();
		}
	}
}
