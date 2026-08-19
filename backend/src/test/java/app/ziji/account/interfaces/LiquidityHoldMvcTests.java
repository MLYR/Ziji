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

import app.ziji.account.application.AccountNotVisibleException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
	private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000804");
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
	void controllerPassesExplicitArchivedSemanticsToTheMandatoryMutationPreflight() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal()).header("Idempotency-Key", "explicit-revise-preflight")
				.header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("12.50", "CNY")))
			.andExpect(status().isCreated());
		assertEquals(Boolean.FALSE, fixture.useCase().lastAllowArchivedAccount);

		fixture.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID)
				.principal(principal()).header("Idempotency-Key", "explicit-release-preflight")
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk());
		assertEquals(Boolean.TRUE, fixture.useCase().lastAllowArchivedAccount);
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
	void staleMutationIsStoredAndSafelyReplayedWithoutReexecutingTheWrite() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().staleMutation());
		String key = "stale-revision-key-001";

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("12.50", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"1\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(path()));

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("12.50", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));

		fixture.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("13.50", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertEquals(1, fixture.idempotency().acquisitions);
		assertEquals(1, fixture.idempotency().committedRecords.size());
		assertEquals(1, fixture.useCase().reviseCalls);

		fixture.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID)
				.principal(principal())
				.header("Idempotency-Key", "stale-release-key-0001")
				.header("If-Match", "\"2\""))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));

		assertEquals(2, fixture.idempotency().acquisitions);
		assertEquals(2, fixture.idempotency().committedRecords.size());
		assertEquals(1, fixture.useCase().releaseCalls);
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
	void storedProblemsOnlyReplayWhitelistedFinalOrRetryableResponses() throws Exception {
		assertStoredProblem(
			IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"), 422, "BUSINESS_RULE_VIOLATION", null);
		assertStoredProblem(
			IdempotencyResponse.failedRetryable(503, "INTERNAL_ERROR"), 503, "INTERNAL_ERROR", "5");
		assertStoredProblem(
			IdempotencyResponse.failedFinal(400, "BUSINESS_RULE_VIOLATION"), 500, "INTERNAL_ERROR", null);
		assertStoredProblem(IdempotencyResponse.failedFinal(400, "VALIDATION_ERROR"), 500, "INTERNAL_ERROR", null);
		assertStoredProblem(IdempotencyResponse.failedFinal(403, "PERMISSION_DENIED"), 500, "INTERNAL_ERROR", null);
		assertStoredProblem(IdempotencyResponse.failedFinal(404, "RESOURCE_NOT_FOUND"), 500, "INTERNAL_ERROR", null);
		assertStoredProblem(IdempotencyResponse.failedFinal(409, "IDEMPOTENCY_KEY_REUSED"), 500, "INTERNAL_ERROR", null);
	}

	@Test
	void revokedAccessAfterInspectionDoesNotRenderStoredTerminalSummary() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "revoked-after-inspection-key";
		String body = commandJson("10.00", "CNY");

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		fixture.idempotency().replaceCompletedResponse(key, IdempotencyResponse.failedFinalVersionConflict(409, 1, path()));
		fixture.useCase().denyAtAccessCall(3);

		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());

		assertEquals(0, fixture.useCase().replayCalls);
	}

	@Test
	void revokedAccessAfterInspectionDoesNotRevealKeyReuseOrInProgress() throws Exception {
		Fixture inProgress = fixture(new FakeUseCase().denyAtAccessCall(2));
		inProgress.idempotency().forceInspection("revoked-in-progress-key", new IdempotencyRecordStore.Acquisition.InProgress());
		inProgress.mvc().perform(post(path()).principal(principal())
				.header("Idempotency-Key", "revoked-in-progress-key").contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY")))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		Fixture reused = fixture(new FakeUseCase());
		String key = "revoked-key-reused-key";
		reused.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
			.andExpect(status().isCreated());
		reused.useCase().denyAtAccessCall(3);
		reused.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("11.00", "CNY")))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void revokedAccessAfterEmptyInspectionDoesNotRevealAcquireDiscoveredStates() throws Exception {
		Fixture replayedCreate = fixture(new FakeUseCase().denyAtAccessCall(2));
		replayedCreate.idempotency().forceAcquisition("empty-then-replay-key",
			new IdempotencyRecordStore.Acquisition.Replay(IdempotencyResponse.succeededResource(
				201, "LIQUIDITY_HOLD", HOLD_ID,
				new IdempotencyResponse.ResourceReference(path(), "\"1\"", 1L))));
		assertRevokedAfterEmptyInspection(replayedCreate,
			post(path()).principal(principal()).header("Idempotency-Key", "empty-then-replay-key")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")));
		assertEquals(0, replayedCreate.useCase().replayCalls);

		Fixture keyReusedRevision = fixture(new FakeUseCase().denyAtAccessCall(2));
		keyReusedRevision.idempotency().forceAcquisition("empty-then-key-reused",
			new IdempotencyRecordStore.Acquisition.KeyReused());
		assertRevokedAfterEmptyInspection(keyReusedRevision,
			post(path() + "/{holdId}/revisions", HOLD_ID).principal(principal())
				.header("Idempotency-Key", "empty-then-key-reused").header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")));
		assertEquals(0, keyReusedRevision.useCase().reviseCalls);

		Fixture inProgressRelease = fixture(new FakeUseCase().denyAtAccessCall(2));
		inProgressRelease.idempotency().forceAcquisition("empty-then-in-progress",
			new IdempotencyRecordStore.Acquisition.InProgress());
		assertRevokedAfterEmptyInspection(inProgressRelease,
			post(path() + "/{holdId}/release", HOLD_ID).principal(principal())
				.header("Idempotency-Key", "empty-then-in-progress").header("If-Match", "\"1\""));
		assertEquals(0, inProgressRelease.useCase().releaseCalls);

		Fixture unavailableCreate = fixture(new FakeUseCase().denyAtAccessCall(2));
		unavailableCreate.idempotency().forceAcquisition("empty-then-unavailable",
			new IdempotencyRecordStore.Acquisition.SafeReplayUnavailable());
		assertRevokedAfterEmptyInspection(unavailableCreate,
			post(path()).principal(principal()).header("Idempotency-Key", "empty-then-unavailable")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")));

		Fixture versionConflictRevision = fixture(new FakeUseCase().denyAtAccessCall(2));
		versionConflictRevision.idempotency().forceAcquisition("empty-then-version-conflict",
			new IdempotencyRecordStore.Acquisition.Replay(
				IdempotencyResponse.failedFinalVersionConflict(409, 2, path())));
		assertRevokedAfterEmptyInspection(versionConflictRevision,
			post(path() + "/{holdId}/revisions", HOLD_ID).principal(principal())
				.header("Idempotency-Key", "empty-then-version-conflict").header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")));
	}

	@Test
	void mutationReplaysValidateTheirOperationSpecificReferences() throws Exception {
		Fixture revise = fixture(new FakeUseCase());
		String reviseKey = "valid-revision-replay-key";
		for (int attempt = 0; attempt < 2; attempt++) {
			revise.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID).principal(principal())
					.header("Idempotency-Key", reviseKey).header("If-Match", "\"1\"")
					.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
				.andExpect(status().isCreated())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.data.id").value(REVISION_ID.toString()));
		}
		assertEquals(1, revise.useCase().replayCalls);

		Fixture release = fixture(new FakeUseCase());
		String releaseKey = "valid-release-replay-key";
		for (int attempt = 0; attempt < 2; attempt++) {
			release.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID).principal(principal())
					.header("Idempotency-Key", releaseKey).header("If-Match", "\"1\""))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"2\""))
				.andExpect(jsonPath("$.data.id").value(HOLD_ID.toString()))
				.andExpect(jsonPath("$.data.status").value("RELEASED"));
		}
		assertEquals(1, release.useCase().replayCalls);
	}

	@Test
	void resourceReferencesMustMatchTheirOperationBeforeReplay() throws Exception {
		assertMalformedCreateReference(
			IdempotencyResponse.succeededResource(200, "LIQUIDITY_HOLD", HOLD_ID,
				new IdempotencyResponse.ResourceReference(path(), "\"1\"", 1L)));
		assertMalformedCreateReference(
			IdempotencyResponse.succeededResource(201, "OTHER_RESOURCE", HOLD_ID,
				new IdempotencyResponse.ResourceReference(path(), "\"1\"", 1L)));

		Fixture revise = fixture(new FakeUseCase());
		String reviseKey = "bad-revision-reference-key";
		revise.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID).principal(principal())
				.header("Idempotency-Key", reviseKey).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
			.andExpect(status().isCreated());
		revise.idempotency().replaceCompletedResponse(reviseKey,
			IdempotencyResponse.succeededResource(201, "LIQUIDITY_HOLD", REVISION_ID,
				new IdempotencyResponse.ResourceReference(path(), "\"1\"", 1L)));
		revise.mvc().perform(post(path() + "/{holdId}/revisions", HOLD_ID).principal(principal())
				.header("Idempotency-Key", reviseKey).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson("10.00", "CNY")))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertEquals(0, revise.useCase().replayCalls);

		Fixture release = fixture(new FakeUseCase());
		String releaseKey = "bad-release-reference-key";
		release.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID).principal(principal())
				.header("Idempotency-Key", releaseKey).header("If-Match", "\"1\""))
			.andExpect(status().isOk());
		release.idempotency().replaceCompletedResponse(releaseKey,
			IdempotencyResponse.succeededResource(200, "LIQUIDITY_HOLD", REVISION_ID,
				new IdempotencyResponse.ResourceReference(path() + "/" + HOLD_ID + "/release", "\"2\"", 2L)));
		release.mvc().perform(post(path() + "/{holdId}/release", HOLD_ID).principal(principal())
				.header("Idempotency-Key", releaseKey).header("If-Match", "\"1\""))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertEquals(0, release.useCase().replayCalls);
	}

	@Test
	void impossibleInspectionStatesFailClosedWhileInProgressKeepsRetryAfter() throws Exception {
		Fixture inProgress = fixture(new FakeUseCase());
		inProgress.idempotency().forceInspection("inspection-progress-key", new IdempotencyRecordStore.Acquisition.InProgress());
		inProgress.mvc().perform(post(path()).principal(principal())
				.header("Idempotency-Key", "inspection-progress-key").contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY")))
			.andExpect(status().isConflict())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));

		Fixture unavailable = fixture(new FakeUseCase());
		unavailable.idempotency().forceInspection("inspection-unavailable-key", new IdempotencyRecordStore.Acquisition.SafeReplayUnavailable());
		unavailable.mvc().perform(post(path()).principal(principal())
				.header("Idempotency-Key", "inspection-unavailable-key").contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10.00", "CNY")))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
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

	private static void assertStoredProblem(
		IdempotencyResponse stored,
		int expectedStatus,
		String expectedCode,
		String retryAfter) throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "stored-problem-key-" + UUID.randomUUID();
		String body = commandJson("10.00", "CNY");
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		fixture.idempotency().replaceCompletedResponse(key, stored);
		var result = fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(expectedStatus))
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.type").exists())
			.andExpect(jsonPath("$.title").exists())
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.code").value(expectedCode))
			.andExpect(jsonPath("$.requestId").exists())
			.andReturn();
		if (retryAfter == null) {
			assertEquals(null, result.getResponse().getHeader("Retry-After"));
		} else {
			assertEquals(retryAfter, result.getResponse().getHeader("Retry-After"));
		}
	}

	private static void assertMalformedCreateReference(IdempotencyResponse stored) throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "bad-create-reference-" + UUID.randomUUID();
		String body = commandJson("10.00", "CNY");
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		fixture.idempotency().replaceCompletedResponse(key, stored);
		fixture.mvc().perform(post(path()).principal(principal()).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertEquals(0, fixture.useCase().replayCalls);
	}

	private static void assertRevokedAfterEmptyInspection(Fixture fixture, MockHttpServletRequestBuilder request) throws Exception {
		fixture.mvc().perform(request)
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());
		assertEquals(1, fixture.idempotency().acquisitions);
	}

	private static java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private record Fixture(MockMvc mvc, FakeUseCase useCase, MemoryIdempotencyStore idempotency) {}

	private static final class FakeUseCase implements LiquidityHoldUseCase {
		private LiquidityHoldCommand lastCommand;
		private boolean rejectNonCnyCurrency;
		private boolean staleMutation;
		private boolean futurePending;
		private boolean safeReplayUnavailable;
		private int replayVersion = 1;
		private int accessCalls;
		private int denyAtAccessCall;
		private int createCalls;
		private int reviseCalls;
		private int releaseCalls;
		private int replayCalls;
		private Boolean lastAllowArchivedAccount;

		private FakeUseCase rejectNonCnyCurrency() {
			rejectNonCnyCurrency = true;
			return this;
		}

		private FakeUseCase replayVersion(int version) {
			replayVersion = version;
			return this;
		}

		private FakeUseCase staleMutation() {
			staleMutation = true;
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

		private FakeUseCase denyAtAccessCall(int value) {
			denyAtAccessCall = value;
			return this;
		}

		@Override
		public LiquidityHoldPage list(UUID userId, UUID accountId, Integer requestedLimit, String cursor) {
			return new LiquidityHoldPage(List.of(hold(AccountCurrency.CNY)), null, false);
		}

		@Override
		public void preflightCreate(UUID userId, UUID accountId) {}

		@Override
		public void preflightCreateAccess(UUID userId, UUID accountId) {
			denyWhenConfigured();
		}

		@Override
		public void preflightMutationAccess(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
			denyWhenConfigured();
		}

		@Override
		public void preflightMutation(
			UUID userId,
			UUID accountId,
			UUID holdId,
			int expectedVersion,
			boolean allowArchivedAccount) {
			lastAllowArchivedAccount = allowArchivedAccount;
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
			reviseCalls++;
			if (staleMutation) {
				throw new LiquidityHoldException.VersionConflict(hold(AccountCurrency.CNY));
			}
			lastCommand = command;
			return revision(command.currency());
		}

		@Override
		public LiquidityHold release(UUID userId, UUID accountId, UUID holdId, int expectedVersion, String requestId) {
			releaseCalls++;
			if (staleMutation) {
				throw new LiquidityHoldException.VersionConflict(hold(AccountCurrency.CNY));
			}
			return releasedHold(AccountCurrency.CNY);
		}

		@Override
		public LiquidityHold replay(UUID userId, UUID accountId, UUID holdId, int expectedVersion) {
			replayCalls++;
			if (safeReplayUnavailable) {
				throw new LiquidityHoldException.SafeReplayUnavailable();
			}
			if (holdId.equals(REVISION_ID)) {
				return revision(AccountCurrency.CNY);
			}
			if (expectedVersion == 2) {
				return releasedHold(AccountCurrency.CNY);
			}
			return hold(AccountCurrency.CNY, replayVersion);
		}

		private void denyWhenConfigured() {
			accessCalls++;
			if (accessCalls == denyAtAccessCall) {
				throw new AccountNotVisibleException();
			}
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

	private static LiquidityHold revision(AccountCurrency currency) {
		return LiquidityHold.restore(REVISION_ID, ACCOUNT_ID, HOLD_ID, HOLD_ID, 2, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), currency, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "人工冻结", null, null, USER_ID, NOW, NOW, 1);
	}

	private static LiquidityHold releasedHold(AccountCurrency currency) {
		return LiquidityHold.restore(HOLD_ID, ACCOUNT_ID, HOLD_ID, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), currency, NOW.minusSeconds(1), null, NOW,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "人工冻结", NOW,
			app.ziji.account.domain.LiquidityHoldEndReason.RELEASED, USER_ID, NOW, NOW, 2);
	}

	private static final class MemoryIdempotencyStore implements IdempotencyRecordStore {
		private final List<IdempotencyRequest> requests = new ArrayList<>();
		private final List<IdempotencyResponse> committedRecords = new ArrayList<>();
		private final Map<UUID, IdempotencyRequest> acquired = new HashMap<>();
		private final Map<String, CompletedRecord> completed = new HashMap<>();
		private final Map<String, Acquisition> forcedInspections = new HashMap<>();
		private final Map<String, Acquisition> forcedAcquisitions = new HashMap<>();
		private int acquisitions;

		@Override
		public java.util.Optional<Acquisition> inspect(IdempotencyRequest request, Instant now) {
			Acquisition forced = forcedInspections.get(request.idempotencyKey());
			if (forced != null) {
				return java.util.Optional.of(forced);
			}
			CompletedRecord prior = completed.get(request.idempotencyKey());
			if (prior == null) {
				return java.util.Optional.empty();
			}
			return java.util.Optional.of(prior.request().requestHash().equals(request.requestHash())
				? new Acquisition.Replay(prior.response()) : new Acquisition.KeyReused());
		}

		@Override
		public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquisitions++;
			requests.add(request);
			Acquisition forced = forcedAcquisitions.get(request.idempotencyKey());
			if (forced != null) {
				return forced;
			}
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

		private void replaceCompletedResponse(String key, IdempotencyResponse response) {
			CompletedRecord prior = completed.get(key);
			if (prior == null) {
				throw new IllegalStateException("测试幂等终态不存在");
			}
			completed.put(key, new CompletedRecord(prior.request(), response));
		}

		private void forceInspection(String key, Acquisition acquisition) {
			forcedInspections.put(key, acquisition);
		}

		private void forceAcquisition(String key, Acquisition acquisition) {
			forcedAcquisitions.put(key, acquisition);
		}

		private record CompletedRecord(IdempotencyRequest request, IdempotencyResponse response) {}
	}
}
