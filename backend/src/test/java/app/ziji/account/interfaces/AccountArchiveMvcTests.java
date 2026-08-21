package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountArchiveException;
import app.ziji.account.application.AccountArchiveUseCase;
import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.application.AccountPermissionDeniedException;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountVersionConflictException;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BE-ACC-005 的归档 HTTP、幂等终态、脱敏 Problem 和强 If-Match 契约测试。 */
class AccountArchiveMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000911");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000912");
	private static final Instant NOW = Instant.parse("2026-08-21T05:06:07Z");

	@Test
	void successfulArchiveReplaysTheSameArchivedResource() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		String key = "archive-success-key-01";

		perform(fixture.mvc, key, archiveJson(true), "\"1\"")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"));
		perform(fixture.mvc, key, archiveJson(true), "\"1\"")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"));

		assertEquals(1, fixture.useCase.archiveCalls);
		assertEquals(1, fixture.useCase.replayCalls);
	}

	@Test
	void nonZeroConfirmationFalseIsFailedFinalAndDoesNotExposeBalance() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().nonZero());
		String key = "archive-nonzero-key-1";

		perform(fixture.mvc, key, archiveJson(false), "\"1\"")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.code").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.detail").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"));
		String replay = perform(fixture.mvc, key, archiveJson(false), "\"1\"")
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"))
			.andReturn().getResponse().getContentAsString();

		org.junit.jupiter.api.Assertions.assertFalse(replay.contains("12.34"));
		org.junit.jupiter.api.Assertions.assertFalse(replay.contains("CNY"));
		assertEquals(1, fixture.useCase.archiveCalls);
	}

	@Test
	void changingConfirmationWithSameKeyIsRejectedAndNewKeyCanProceed() throws Exception {
		Fixture fixture = fixture(new FakeUseCase().nonZero());

		perform(fixture.mvc, "archive-confirm-key-1", archiveJson(false), "\"1\"")
			.andExpect(status().isUnprocessableEntity());
		perform(fixture.mvc, "archive-confirm-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		fixture.useCase.nonZero = false;
		perform(fixture.mvc, "archive-confirm-key-2", archiveJson(true), "\"1\"")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""));
		assertEquals(2, fixture.useCase.archiveCalls);
	}

	@Test
	void alreadyArchivedAndVersionConflictAreStableFailedFinals() throws Exception {
		Fixture archived = fixture(new FakeUseCase().alreadyArchived());
		perform(archived.mvc, "archive-already-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_ARCHIVED"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());
		perform(archived.mvc, "archive-already-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_ARCHIVED"));

		Fixture conflict = fixture(new FakeUseCase().conflict());
		perform(conflict.mvc, "archive-conflict-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(
				"/api/v1/accounts/" + ACCOUNT_ID));
	}

	@Test
	void invalidHeaderBodyAndVisibilityFailBeforeCreatingIdempotencyRecord() throws Exception {
		Fixture fixture = fixture(new FakeUseCase());
		perform(fixture.mvc, "archive-invalid-key-1", "{\"reason\":\"清理\"}", "\"1\"")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		perform(fixture.mvc, "archive-invalid-key-2", archiveJson(true), "7")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		perform(fixture.mvc, "archive-invalid-key-3", archiveJson(true), "\"1\"", "\"1\"")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, fixture.store.acquisitions);

		Fixture invisible = fixture(new FakeUseCase().invisible());
		perform(invisible.mvc, "archive-invisible-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(invisible.mvc, "archive-invisible-key-2", "{not-json", "\"1\"")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		perform(invisible.mvc, "archive-invisible-key-3", "", "\"1\"")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		Fixture editor = fixture(new FakeUseCase().denied());
		perform(editor.mvc, "archive-editor-key-1", archiveJson(true), "\"1\"")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
	}

	private static org.springframework.test.web.servlet.ResultActions perform(
		MockMvc mvc,
		String key,
		String body,
		String ifMatch,
		String... extraIfMatch) throws Exception {
		var request = post("/api/v1/accounts/{accountId}/archive", ACCOUNT_ID)
			.principal(() -> USER_ID.toString())
			.header("Idempotency-Key", key)
			.header("If-Match", ifMatch)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		for (String duplicate : extraIfMatch) {
			request.header("If-Match", duplicate);
		}
		return mvc.perform(request);
	}

	private static String archiveJson(boolean confirm) {
		return "{\"reason\":\"账户已完成清理\",\"confirmNonZeroBalance\":" + confirm + "}";
	}

	private static Fixture fixture(FakeUseCase useCase) {
		MemoryIdempotencyStore store = new MemoryIdempotencyStore();
		IdempotencyAnonymousSubjectHasher anonymousHasher = email ->
			IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		TransactionRunner transactions = new TransactionRunner() {
			@Override
			public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }

			@Override
			public void required(Runnable action) { action.run(); }
		};
		UnifiedIdempotencyService idempotency = new UnifiedIdempotencyService(
			transactions, store, anonymousHasher, Clock.fixed(NOW, ZoneOffset.UTC));
		CurrentUserIdResolver resolver = principal -> USER_ID;
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new AccountArchiveController(
			useCase, resolver, idempotency))
			.setControllerAdvice(new AccountApiExceptionHandler())
			.build();
		return new Fixture(mvc, useCase, store);
	}

	private record Fixture(MockMvc mvc, FakeUseCase useCase, MemoryIdempotencyStore store) {
	}

	private static final class FakeUseCase implements AccountArchiveUseCase {
		private boolean nonZero;
		private boolean alreadyArchived;
		private boolean conflict;
		private boolean invisible;
		private boolean denied;
		private int archiveCalls;
		private int replayCalls;

		private FakeUseCase nonZero() { nonZero = true; return this; }
		private FakeUseCase alreadyArchived() { alreadyArchived = true; return this; }
		private FakeUseCase conflict() { conflict = true; return this; }
		private FakeUseCase invisible() { invisible = true; return this; }
		private FakeUseCase denied() { denied = true; return this; }

		@Override
		public void preflightAccess(UUID userId, UUID accountId) {
			if (invisible) throw new AccountNotVisibleException();
			if (denied) throw new AccountPermissionDeniedException();
		}

		@Override
		public AccountQueryResult archive(
			UUID userId, UUID accountId, int expectedVersion, String reason,
			boolean confirmNonZeroBalance, String requestId) {
			archiveCalls++;
			if (nonZero && !confirmNonZeroBalance) throw new AccountArchiveException.NonZeroBalanceConfirmationRequired();
			if (alreadyArchived) throw new AccountArchiveException.AlreadyArchived();
			if (conflict) throw new AccountVersionConflictException(result(AccountStatus.ACTIVE, 2));
			return result(AccountStatus.ARCHIVED, 2);
		}

		@Override
		public AccountQueryResult replay(UUID userId, UUID accountId, int expectedVersion) {
			replayCalls++;
			return result(AccountStatus.ARCHIVED, expectedVersion);
		}
	}

	private static AccountQueryResult result(AccountStatus status, int version) {
		return new AccountQueryResult(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "归档账户", null, AccountCurrency.CNY,
			status, NOW, version, "OWNER", BigDecimal.ONE);
	}

	private static final class MemoryIdempotencyStore implements IdempotencyRecordStore {
		private final Map<String, CompletedRecord> completed = new HashMap<>();
		private final Map<UUID, IdempotencyRequest> acquired = new HashMap<>();
		private int acquisitions;

		@Override
		public Optional<Acquisition> inspect(IdempotencyRequest request, Instant now) {
			CompletedRecord prior = completed.get(request.idempotencyKey());
			if (prior == null) return Optional.empty();
			return Optional.of(prior.request.requestHash().equals(request.requestHash())
				? new Acquisition.Replay(prior.response) : new Acquisition.KeyReused());
		}

		@Override
		public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquisitions++;
			CompletedRecord prior = completed.get(request.idempotencyKey());
			if (prior != null) {
				return prior.request.requestHash().equals(request.requestHash())
					? new Acquisition.Replay(prior.response) : new Acquisition.KeyReused();
			}
			UUID id = UUID.randomUUID();
			acquired.put(id, request);
			return new Acquisition.Acquired(id);
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			IdempotencyRequest request = acquired.remove(recordId);
			if (request == null) throw new IllegalStateException("幂等记录不存在");
			completed.put(request.idempotencyKey(), new CompletedRecord(request, response));
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) { return 0; }

		private record CompletedRecord(IdempotencyRequest request, IdempotencyResponse response) {}
	}
}
