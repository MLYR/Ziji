package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountPage;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryUseCase;
import app.ziji.account.application.AccountVersionConflictException;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountPatch;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 账户路由的 merge-patch、If-Match、ETag、分页和 VERSION_CONFLICT 契约测试。 */
class AccountMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03Z");

	@Test
	void getReturnsEnvelopeAndEtag() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("旧名称", "旧机构", 7, "OWNER", "1.000000")));

		mvc.perform(get("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal()))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"7\""))
			.andExpect(jsonPath("$.data.id").value(ACCOUNT_ID.toString()))
			.andExpect(jsonPath("$.data.accountClass").value("ASSET"))
			.andExpect(jsonPath("$.data.accountType").value("BANK"))
			.andExpect(jsonPath("$.data.currentUserRole").value("OWNER"))
			.andExpect(jsonPath("$.data.inclusionRatio").value("1.000000"))
			.andExpect(jsonPath("$.data.version").value(7));
	}

	@Test
	void listReturnsEnvelopeAndPageMeta() throws Exception {
		FakeUseCase useCase = new FakeUseCase(result("名称", "机构", 3, "EDITOR", "0.500000"));
		useCase.page = new AccountPage(List.of(useCase.single), "opaque-cursor", true);
		MockMvc mvc = mvc(useCase);

		mvc.perform(get("/api/v1/accounts").principal(principal()).param("limit", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.meta.nextCursor").value("opaque-cursor"))
			.andExpect(jsonPath("$.meta.hasMore").value(true));
	}

	@Test
	void listRejectsInvalidLimitAndAccountId() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("名称", "机构", 3, "OWNER", "1.000000")));

		mvc.perform(get("/api/v1/accounts").principal(principal()).param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid").principal(principal()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchReturnsUpdatedEnvelopeAndEtag() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("旧名称", "旧机构", 7, "OWNER", "1.000000")));

		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID)
				.principal(principal())
				.header("Idempotency-Key", "mvc-account-update-0001")
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"新名称\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"8\""))
			.andExpect(jsonPath("$.data.name").value("新名称"));
	}

	@Test
	void patchAllowsExplicitNullInstitution() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("名称", "旧机构", 7, "OWNER", "1.000000")));

		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID)
				.principal(principal())
				.header("Idempotency-Key", "mvc-account-update-0002")
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"institution\":null}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.institution").value(nullValue()));
	}

	@Test
	void patchRejectsMissingIfMatchAndInvalidBodies() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("名称", "机构", 7, "OWNER", "1.000000")));

		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("Idempotency-Key", "mvc-account-conflict-001")
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("Idempotency-Key", "mvc-account-stale-0001")
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{\"name\":null}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{\"accountClass\":\"LIABILITY\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void patchRejectsIllegalIfMatchFormats() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(result("名称", "机构", 7, "OWNER", "1.000000")));

		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "7")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"0\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"-1\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "W/\"7\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "*")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"2147483648\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"7\", \"8\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("If-Match", "\"7\"", "\"8\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void staleIfMatchReturnsBoundedVersionConflict() throws Exception {
		AccountQueryResult current = result("当前名称", "当前机构", 8, "OWNER", "1.000000");
		MockMvc mvc = mvc(new ConflictingUseCase(current));

		mvc.perform(patch("/api/v1/accounts/{id}", ACCOUNT_ID).principal(principal())
				.header("Idempotency-Key", "mvc-account-stale-0002")
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{\"name\":\"新名称\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(8))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"8\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation")
				.value("/api/v1/accounts/" + ACCOUNT_ID))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	private MockMvc mvc(AccountQueryUseCase useCase) {
		CurrentUserIdResolver resolver = principal -> {
			if (principal == null) {
				throw new app.ziji.user.application.UserAuthenticationException();
			}
			return USER_ID;
		};
		return MockMvcBuilders.standaloneSetup(new AccountController(
			useCase, null, resolver, idempotency(), null))
			.setControllerAdvice(new AccountApiExceptionHandler())
			.build();
	}

	private UnifiedIdempotencyService idempotency() {
		IdempotencyAnonymousSubjectHasher anonymous = email ->
			IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		return new UnifiedIdempotencyService(new DirectTransactions(), new Records(), anonymous,
			Clock.fixed(CREATED_AT, ZoneOffset.UTC));
	}

	private java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private static AccountQueryResult result(
		String name,
		String institution,
		int version,
		String role,
		String ratio) {
		return new AccountQueryResult(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, name, institution,
			AccountCurrency.CNY, AccountStatus.ACTIVE, CREATED_AT, version,
			role, new BigDecimal(ratio));
	}

	private static final class FakeUseCase implements AccountQueryUseCase {
		private AccountQueryResult single;
		private AccountPage page;

		private FakeUseCase(AccountQueryResult single) {
			this.single = single;
		}

		@Override
		public AccountPage listVisibleAccounts(UUID userId, Integer limit, String cursor) {
			if (page != null) {
				return page;
			}
			return new AccountPage(List.of(single), null, false);
		}

		@Override
		public AccountQueryResult getVisibleAccount(UUID userId, UUID accountId) {
			return single;
		}

		@Override
		public AccountQueryResult updateAccount(
			UUID userId, UUID accountId, int expectedVersion, AccountPatch patch) {
			single = new AccountQueryResult(
				single.id(), single.accountClass(), single.accountType(),
				patch.hasName() ? patch.name() : single.name(),
				patch.hasInstitution() ? patch.institution() : single.institution(),
				single.currency(), single.status(), single.createdAt(), single.version() + 1,
				single.currentUserRole(), single.inclusionRatio());
			return single;
		}
	}

	private static final class ConflictingUseCase implements AccountQueryUseCase {
		private final AccountQueryResult current;

		private ConflictingUseCase(AccountQueryResult current) {
			this.current = current;
		}

		@Override
		public AccountPage listVisibleAccounts(UUID userId, Integer limit, String cursor) {
			return new AccountPage(List.of(current), null, false);
		}

		@Override
		public AccountQueryResult getVisibleAccount(UUID userId, UUID accountId) {
			return current;
		}

		@Override
		public AccountQueryResult updateAccount(
			UUID userId, UUID accountId, int expectedVersion, AccountPatch patch) {
			throw new AccountVersionConflictException(current);
		}
	}

	private static final class DirectTransactions implements TransactionRunner {
		@Override public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }
		@Override public void required(Runnable action) { action.run(); }
	}

	private static final class Records implements IdempotencyRecordStore {
		@Override public Acquisition acquire(IdempotencyRequest request, Instant now) {
			return new Acquisition.Acquired(UUID.randomUUID());
		}
		@Override public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {}
		@Override public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) { return 0; }
	}
}
