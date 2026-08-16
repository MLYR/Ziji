package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.account.application.LiquidityHoldCommand;
import app.ziji.account.application.LiquidityHoldException;
import app.ziji.account.application.LiquidityHoldPage;
import app.ziji.account.application.LiquidityHoldUseCase;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldType;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BUG-API-006 的 HTTP 载荷类型、响应形状和幂等前置校验测试。 */
class LiquidityHoldMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000801");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000802");
	private static final UUID HOLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000803");
	private static final Instant NOW = Instant.parse("2026-08-15T01:02:03Z");

	@Test
	void createUsesTopLevelAmountAndCurrencyAndReturnsIndependentFields() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());

		fixture.mvc().perform(post(path())
				.principal(principal())
				.header("Idempotency-Key", "create-cny-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY")))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.amount").isString())
			.andExpect(jsonPath("$.data.amount").value("10.00"))
			.andExpect(jsonPath("$.data.currency").isString())
			.andExpect(jsonPath("$.data.currency").value("CNY"))
			.andExpect(jsonPath("$.data.amount.amount").doesNotExist());

		assertEquals(AccountCurrency.CNY, fixture.useCase().lastCommand.currency());
		assertEquals(new BigDecimal("10.00"), fixture.useCase().lastCommand.amount());
	}

	@Test
	void createReplayReturnsTheSameResourceAndStrongEtag() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "create-replay-key-01";
		String body = commandJson("10.00", "CNY");

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.id").value(HOLD_ID.toString()));
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.id").value(HOLD_ID.toString()));

		assertEquals(1, fixture.useCase().createCalls);
		assertEquals(1, fixture.useCase().replayCalls);
	}

	@Test
	void replayWithChangedResourceVersionFailsClosedWithoutSuccessEtag() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().replayVersion(2));
		String key = "create-replay-drift-01";
		String body = commandJson("10.00", "CNY");

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""));
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		assertEquals(1, fixture.useCase().createCalls);
		assertEquals(1, fixture.useCase().replayCalls);
	}

	@Test
	void futurePendingReplaySafeFailureReturnsInternalErrorWithoutSuccessEtag() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().futurePending().safeReplayUnavailable());
		String key = "future-pending-replay-01";
		String body = commandJson("10.00", "CNY");

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.status").value("PENDING"));
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		assertEquals(1, fixture.useCase().createCalls);
		assertEquals(1, fixture.useCase().replayCalls);
	}

	@Test
	void reviseUsesTopLevelAmountAndCurrency() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", "revise-cny-key-001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("12.50", "CNY")))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.amount").isString())
			.andExpect(jsonPath("$.data.currency").value("CNY"));

		assertEquals(AccountCurrency.CNY, fixture.useCase().lastCommand.currency());
		assertEquals(new BigDecimal("12.50"), fixture.useCase().lastCommand.amount());
	}

	@Test
	void reviseAndReleaseRejectMalformedIfMatchBeforeIdempotency() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		for (String value : List.of("W/\"1\"", "*", "1", "\"0\"", "\"-1\"", "\"abc\"", "\"2147483648\"")) {
			assertInvalidIfMatch(fixture, post(path() + "/{holdId}/revisions", HOLD_ID)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("12.50", "CNY")), value);
			assertInvalidIfMatch(fixture, post(path() + "/{holdId}/release", HOLD_ID), value);
		}
		assertInvalidIfMatch(fixture, post(path() + "/{holdId}/revisions", HOLD_ID)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson("12.50", "CNY")), null);
		assertInvalidIfMatch(fixture, post(path() + "/{holdId}/release", HOLD_ID), null);
		assertDuplicateIfMatch(fixture, post(path() + "/{holdId}/revisions", HOLD_ID)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson("12.50", "CNY")));
		assertDuplicateIfMatch(fixture, post(path() + "/{holdId}/release", HOLD_ID));

		assertEquals(0, fixture.idempotency().acquisitions);
		assertEquals(0, fixture.idempotency().committedRecords.size());
	}

	@Test
	void staleMutationsMustBeRejectedBeforeIdempotencyAcquisition() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().staleRevision());

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", "stale-revision-key-001")
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("12.50", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

		assertEquals(0, fixture.idempotency().acquisitions);
		assertEquals(0, fixture.idempotency().committedRecords.size());

		fixture.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", "stale-release-key-0001")
				.header("If-Match", "\"2\""))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

		assertEquals(0, fixture.idempotency().acquisitions);
		assertEquals(0, fixture.idempotency().committedRecords.size());
	}

	@Test
	void invalidCurrencyAndLegacyNestedAmountAreRejectedBeforeIdempotency() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		List<String> invalidBodies = List.of(
			"{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"缺币种\"}",
			"{\"type\":\"FROZEN\",\"amount\":10,\"currency\":\"CNY\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"金额类型错误\"}",
			"{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"currency\":123,\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"类型错误\"}",
			"{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"currency\":null,\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"空值\"}",
			"{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"currency\":\"XXX\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"未知\"}",
			"{\"type\":\"FROZEN\",\"amount\":{\"amount\":\"10.00\",\"currency\":\"CNY\"},\"currency\":\"CNY\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"旧形状\"}",
			"{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"currency\":\"CNY\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"reason\":\"额外字段\",\"source\":\"MANUAL\"}");

		for (String body : invalidBodies) {
			fixture.mvc().perform(post(path())
					.principal(principal())
					.header("Idempotency-Key", "invalid-key-000001")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		assertEquals(0, fixture.idempotency().acquisitions);
		assertEquals(0, fixture.idempotency().committedRecords.size());
	}

	@Test
	void positiveMoneyScaleAndIntegerBoundsAreRejectedBeforePersistence() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		List<String> invalidBodies = List.of(
			commandJson("0", "CNY"),
			commandJson("-1", "CNY"),
			commandJson("1.234", "CNY"),
			commandJson("1.1", "JPY"),
			commandJson("123456789012345678901234", "CNY"));

		for (String body : invalidBodies) {
			fixture.mvc().perform(post(path())
					.principal(principal())
					.header("Idempotency-Key", "amount-boundary-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		assertEquals(0, fixture.idempotency().acquisitions);
	}

	@Test
	void reasonUsesUnicodeCodePointBoundary() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());

		fixture.mvc().perform(post(path())
				.principal(principal())
				.header("Idempotency-Key", "reason-codepoint-500")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY", "😀".repeat(500))))
			.andExpect(status().isCreated());

		fixture.mvc().perform(post(path())
				.principal(principal())
				.header("Idempotency-Key", "reason-codepoint-501")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY", "😀".repeat(501))))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		assertEquals(1, fixture.useCase().createCalls);
	}

	@Test
	void currencyMismatchIsBusinessRuleAndDoesNotCommitIdempotency() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().rejectNonCnyCurrency());

		fixture.mvc().perform(post(path())
				.principal(principal())
				.header("Idempotency-Key", "mismatch-key-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "USD")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

		assertEquals(1, fixture.idempotency().acquisitions);
		assertEquals(0, fixture.idempotency().committedRecords.size());
	}

	@Test
	void idempotencyKeyReuseDoesNotReturnSuccessEtag() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "reused-key-cny-0001";

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""));
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("11.00", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertEquals(1, fixture.idempotency().committedRecords.size());
	}

	@Test
	void normalizedHashChangesWhenOnlyCurrencyChanges() throws Exception {
		MemoryIdempotencyStore store = new MemoryIdempotencyStore();
		FakeUseCase useCase = new FakeUseCase();
		MockMvc mvc = mvc(useCase, idempotency(store));

		mvc.perform(post(path()).principal(principal()).header("Idempotency-Key", "hash-key-cny-0001")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
			.andExpect(status().isCreated());
		mvc.perform(post(path()).principal(principal()).header("Idempotency-Key", "hash-key-usd-0001")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "USD")))
			.andExpect(status().isCreated());

		assertEquals(2, store.requests.size());
		assertFalse(store.requests.get(0).requestHash().equals(store.requests.get(1).requestHash()));
		assertNotNull(store.requests.get(0).requestHash());
	}

	private static Fixture fixture(FakeUseCase useCase) {
		MemoryIdempotencyStore store = new MemoryIdempotencyStore();
		return new Fixture(mvc(useCase, idempotency(store)), useCase, store);
	}

	private static UnifiedIdempotencyService idempotency(MemoryIdempotencyStore store) {
		TransactionRunner transactions = new TransactionRunner() {
			@Override
			public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }

			@Override
			public void required(Runnable action) { action.run(); }
		};
		IdempotencyAnonymousSubjectHasher anonymousHasher = email ->
			IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		return new UnifiedIdempotencyService(transactions, store, anonymousHasher, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static MockMvc mvc(FakeUseCase useCase, UnifiedIdempotencyService idempotency) {
		CurrentUserIdResolver resolver = principal -> USER_ID;
		return MockMvcBuilders.standaloneSetup(new LiquidityHoldController(
			useCase, resolver, idempotency, Clock.fixed(NOW, ZoneOffset.UTC)))
			.setControllerAdvice(new AccountApiExceptionHandler())
			.build();
	}

	private static String path() {
		return "/api/v1/accounts/" + ACCOUNT_ID + "/liquidity-holds";
	}

	private static String commandJson(String amount, String currency) {
		return commandJson(amount, currency, "人工冻结");
	}

	private static String commandJson(String amount, String currency, String reason) {
		return "{\"type\":\"FROZEN\",\"amount\":\"" + amount
			+ "\",\"currency\":\"" + currency
			+ "\",\"effectiveAt\":\"2026-08-15T01:02:03Z\",\"expiresAt\":null,\"reason\":\"" + reason + "\"}";
	}

	private static void assertInvalidIfMatch(
		Fixture fixture,
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
		String ifMatch) throws Exception {
		request.principal(principal()).header("Idempotency-Key", "invalid-if-match-key-001");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		fixture.mvc().perform(request)
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private static void assertDuplicateIfMatch(
		Fixture fixture,
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		fixture.mvc().perform(request.principal(principal())
				.header("Idempotency-Key", "duplicate-if-match-key-01")
				.header("If-Match", "\"1\"")
				.header("If-Match", "\"1\""))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private static java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private record Fixture(MockMvc mvc, FakeUseCase useCase, MemoryIdempotencyStore idempotency) {}

	private static final class FakeUseCase implements LiquidityHoldUseCase {
		private LiquidityHoldCommand lastCommand;
		private boolean rejectNonCnyCurrency;
		private boolean staleRevision;
		private boolean futurePending;
		private boolean safeReplayUnavailable;
		private int replayVersion = 1;
		private int createCalls;
		private int replayCalls;

		private FakeUseCase rejectNonCnyCurrency() {
			rejectNonCnyCurrency = true;
			return this;
		}

		private FakeUseCase replayVersion(int version) {
			replayVersion = version;
			return this;
		}

		private FakeUseCase staleRevision() {
			staleRevision = true;
			return this;
		}

		private FakeUseCase futurePending() {
			futurePending = true;
			return this;
		}

		private FakeUseCase safeReplayUnavailable() {
			safeReplayUnavailable = true;
			return this;
		}

		@Override
		public LiquidityHoldPage list(UUID userId, UUID accountId, Integer requestedLimit, String cursor) {
			return new LiquidityHoldPage(List.of(hold(AccountCurrency.CNY)), null, false);
		}

		@Override
		public void preflightCreate(UUID userId, UUID accountId) {}

		@Override
		public void preflightMutation(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
			if (staleRevision) {
				throw new LiquidityHoldException.VersionConflict(hold(AccountCurrency.CNY));
			}
		}

		@Override
		public LiquidityHold create(UUID userId, UUID accountId, LiquidityHoldCommand command, String requestId) {
			createCalls++;
			lastCommand = command;
			if (rejectNonCnyCurrency && command.currency() != AccountCurrency.CNY) {
				throw new LiquidityHoldException.BusinessRule();
			}
			return futurePending ? futureHold(command.currency()) : hold(command.currency());
		}

		@Override
		public LiquidityHold revise(UUID userId, UUID accountId, UUID holdId, int expectedVersion,
			LiquidityHoldCommand command, String requestId) {
			lastCommand = command;
			return hold(command.currency());
		}

		@Override
		public LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId) {
			return hold(AccountCurrency.CNY);
		}

		@Override
		public LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
			replayCalls++;
			if (safeReplayUnavailable) {
				throw new LiquidityHoldException.SafeReplayUnavailable();
			}
			return hold(AccountCurrency.CNY, replayVersion);
		}
	}

	private static LiquidityHold hold(AccountCurrency currency) {
		return hold(currency, 1);
	}

	private static LiquidityHold hold(AccountCurrency currency, int version) {
		return LiquidityHold.restore(HOLD_ID, ACCOUNT_ID, HOLD_ID, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), currency, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "人工冻结", null, null, USER_ID, NOW, NOW, version);
	}

	private static LiquidityHold futureHold(AccountCurrency currency) {
		return LiquidityHold.restore(HOLD_ID, ACCOUNT_ID, HOLD_ID, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), currency, NOW.plusSeconds(60), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "人工冻结", null, null, USER_ID, NOW, NOW, 1);
	}

	private static final class MemoryIdempotencyStore implements IdempotencyRecordStore {
		private final List<IdempotencyRequest> requests = new ArrayList<>();
		private final List<IdempotencyResponse> committedRecords = new ArrayList<>();
		private final Map<UUID, IdempotencyRequest> acquired = new HashMap<>();
		private final Map<String, CompletedRecord> completed = new HashMap<>();
		private int acquisitions;

		@Override
		public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquisitions++;
			requests.add(request);
			CompletedRecord prior = completed.get(request.idempotencyKey());
			if (prior != null) {
				return prior.request().requestHash().equals(request.requestHash())
					? new Acquisition.Replay(prior.response())
					: new Acquisition.KeyReused();
			}
			UUID recordId = UUID.randomUUID();
			acquired.put(recordId, request);
			return new Acquisition.Acquired(recordId);
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			IdempotencyRequest request = acquired.remove(recordId);
			if (request == null) {
				throw new IllegalStateException("测试幂等记录不存在");
			}
			committedRecords.add(response);
			completed.put(request.idempotencyKey(), new CompletedRecord(request, response));
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
			return 0;
		}

		private record CompletedRecord(IdempotencyRequest request, IdempotencyResponse response) {}
	}
}
