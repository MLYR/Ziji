package app.ziji.liability.interfaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.liability.application.LiabilityDetailApplicationException;
import app.ziji.liability.application.LiabilityDetailPutCondition;
import app.ziji.liability.application.LiabilityDetailUseCase;
import app.ziji.liability.application.LiabilityDetailWriteResult;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;
import app.ziji.user.application.CurrentUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LiabilityDetailMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000601");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000602");

	@Test
	void getEmptyAndWriteSuccessUseStrongIndependentEtags() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());

		fixture.mvc.perform(get(path()).principal(principal()))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.data.interestRate").doesNotExist())
			.andExpect(jsonPath("$.data.version").value(0));
		fixture.mvc.perform(put(path()).principal(principal())
				.header("If-None-Match", "*").header("Idempotency-Key", "mvc-liability-create-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.interestRate").isString())
			.andExpect(jsonPath("$.data.currentAmountDue").isString());
		fixture.mvc.perform(patch(path()).principal(principal())
				.header("If-Match", "\"1\"").header("Idempotency-Key", "mvc-liability-patch-01")
				.contentType("application/merge-patch+json").content("{\"billingDay\":null}"))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""));
	}

	@Test
	void putConditionHeadersRejectAllMalformedShapesBeforeUseCase() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		for (String value : new String[] {"W/\"1\"", "*", "1", "\"0\"", "\"-1\"", "\"abc\"", "\"2147483648\""}) {
			fixture.mvc.perform(put(path()).principal(principal()).header("If-Match", value)
					.header("Idempotency-Key", "mvc-invalid-" + Math.abs(value.hashCode()) + "-01")
					.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
				.andExpect(status().isBadRequest())
				.andExpect(header().doesNotExist("ETag"))
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		fixture.mvc.perform(put(path()).principal(principal())
				.header("Idempotency-Key", "mvc-missing-condition-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		fixture.mvc.perform(put(path()).principal(principal())
				.header("If-Match", "\"1\"").header("If-None-Match", "*")
				.header("Idempotency-Key", "mvc-both-condition-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		fixture.mvc.perform(put(path()).principal(principal()).header("If-None-Match", "*")
				.header("If-None-Match", "*").header("Idempotency-Key", "mvc-dup-condition-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		assertEquals(0, fixture.useCase.putCalls);
	}

	@Test
	void patchRequiresStrongIfMatchAndRejectsUnknownOrEmptyPayloadBeforeUseCase() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		for (String value : new String[] {null, "W/\"1\"", "*", "1", "\"0\"", "\"-1\"", "\"abc\"", "\"2147483648\""}) {
			var request = patch(path()).principal(principal())
				.header("Idempotency-Key", "mvc-patch-invalid-" + UUID.randomUUID())
				.contentType("application/merge-patch+json").content("{\"billingDay\":null}");
			if (value != null) request.header("If-Match", value);
			fixture.mvc.perform(request)
				.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		}
		fixture.mvc.perform(patch(path()).principal(principal()).header("If-Match", "\"1\"")
				.header("If-Match", "\"1\"").header("Idempotency-Key", "mvc-patch-duplicate-01")
				.contentType("application/merge-patch+json").content("{\"billingDay\":null}"))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		fixture.mvc.perform(patch(path()).principal(principal()).header("If-Match", "\"1\"")
				.header("If-None-Match", "*").header("Idempotency-Key", "mvc-patch-none-0001")
				.contentType("application/merge-patch+json").content("{\"billingDay\":null}"))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		fixture.mvc.perform(patch(path()).principal(principal()).header("If-Match", "\"1\"")
				.header("Idempotency-Key", "mvc-patch-empty-0001")
				.contentType("application/merge-patch+json").content("{}"))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		fixture.mvc.perform(put(path()).principal(principal()).header("If-None-Match", "*")
				.header("Idempotency-Key", "mvc-unknown-field-01").contentType(MediaType.APPLICATION_JSON)
				.content(completeJson().replace("}", ",\"unknown\":1}")))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist("ETag"));
		assertEquals(0, fixture.useCase.putCalls);
		assertEquals(0, fixture.useCase.patchCalls);
	}

	@Test
	void versionAndBusinessFailuresMapTo409And422WithoutSuccessEtag() throws Exception {
		LiabilityDetail current = LiabilityDetail.create(ACCOUNT_ID,
			new LiabilityDetailValues(new BigDecimal("0.05"), null, null, 8, 20, new BigDecimal("100")),
			Instant.parse("2026-08-17T00:00:00Z"));
		Fixture conflict = fixture(new FakeUseCase().writeFailure(
			new LiabilityDetailApplicationException.VersionConflict(current)));
		conflict.mvc.perform(put(path()).principal(principal()).header("If-Match", "\"1\"")
				.header("Idempotency-Key", "mvc-version-conflict-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));

		Fixture business = fixture(new FakeUseCase().writeFailure(
			new app.ziji.liability.domain.LiabilityDetailException.BusinessRule()));
		business.mvc.perform(put(path()).principal(principal()).header("If-None-Match", "*")
				.header("Idempotency-Key", "mvc-business-rule-0001")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson()))
			.andExpect(status().isUnprocessableEntity()).andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
	}

	@Test
	void exceptionMappingsReturnRequiredStatusWithoutSuccessEtag() throws Exception {
		for (RuntimeException failure : new RuntimeException[] {
			new LiabilityDetailApplicationException.NotFound(),
			new LiabilityDetailApplicationException.PermissionDenied(),
			new LiabilityDetailApplicationException.SafeReplayUnavailable()}) {
			FakeUseCase useCase = new FakeUseCase().failure(failure);
			Fixture fixture = fixture(useCase);
			var request = put(path()).principal(principal()).header("If-None-Match", "*")
				.header("Idempotency-Key", "mvc-failure-" + failure.getClass().getSimpleName() + "-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeJson());
			var result = fixture.mvc.perform(request).andExpect(header().doesNotExist("ETag"));
			if (failure instanceof LiabilityDetailApplicationException.NotFound) {
				result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
			} else if (failure instanceof LiabilityDetailApplicationException.PermissionDenied) {
				result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
			} else {
				result.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
			}
		}
	}

	private static Fixture fixture(FakeUseCase useCase) {
		CurrentUserIdResolver resolver = principal -> USER_ID;
		LiabilityDetailController controller = new LiabilityDetailController(useCase, resolver);
		MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new LiabilityDetailApiExceptionHandler()).build();
		return new Fixture(mvc, useCase);
	}

	private static String path() {
		return "/api/v1/accounts/" + ACCOUNT_ID + "/liability-details";
	}

	private static String completeJson() {
		return "{\"interestRate\":\"0.05\",\"loanDate\":\"2026-01-01\",\"dueDate\":\"2027-01-01\","
			+ "\"billingDay\":8,\"repaymentDay\":20,\"currentAmountDue\":\"100.00\"}";
	}

	private static java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private record Fixture(MockMvc mvc, FakeUseCase useCase) {
	}

	private static final class FakeUseCase implements LiabilityDetailUseCase {
		private int putCalls;
		private int patchCalls;
		private RuntimeException failure;
		private RuntimeException writeFailure;
		private int version;

		private FakeUseCase failure(RuntimeException failure) {
			this.failure = failure;
			return this;
		}

		private FakeUseCase writeFailure(RuntimeException failure) {
			this.writeFailure = failure;
			return this;
		}

		@Override
		public LiabilityDetail get(UUID userId, UUID accountId) {
			return version == 0 ? LiabilityDetail.empty(accountId)
				: LiabilityDetail.restore(accountId,
					new LiabilityDetailValues(new BigDecimal("0.05"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
						8, 20, new BigDecimal("100")), Instant.parse("2026-08-17T00:00:00Z"), version);
		}

		@Override
		public void authorizeWrite(UUID userId, UUID accountId) {
			if (failure != null) throw failure;
		}

		@Override
		public LiabilityDetailWriteResult put(
			UUID userId, UUID accountId, LiabilityDetailPutCondition condition,
			LiabilityDetailValues values, String idempotencyKey) {
			putCalls++;
			if (failure != null) throw failure;
			if (writeFailure != null) throw writeFailure;
			version = condition.isInitial() ? 1 : condition.expectedVersion() + 1;
			return new LiabilityDetailWriteResult(get(userId, accountId), condition.isInitial() ? 201 : 200);
		}

		@Override
		public LiabilityDetailWriteResult patch(
			UUID userId, UUID accountId, int expectedVersion,
			LiabilityDetailPatch patch, String idempotencyKey) {
			patchCalls++;
			if (failure != null) throw failure;
			if (writeFailure != null) throw writeFailure;
			version = expectedVersion + 1;
			return new LiabilityDetailWriteResult(get(userId, accountId), 200);
		}
	}
}
