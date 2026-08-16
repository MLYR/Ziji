package app.ziji;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.category.application.CategoryStore;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerOutbox;
import app.ziji.ledger.application.LedgerSyncCommandPort;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.application.SyncLedgerCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.sync.application.SyncOperationApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL/Testcontainers HTTP 证据：同步入口复用认证、Ledger 事实链和统一幂等记录。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SyncOperationsHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DeviceSessionApplicationService sessions;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private AccountPostingReferencePort accounts;

	@Autowired
	private AccountPostingAccessPort accountAccess;

	@Autowired
	private CategoryStore categories;

	@Autowired
	private LedgerAccountStore ledgerAccounts;

	@Autowired
	private LedgerTransactionStore ledgerTransactions;

	@Autowired
	private AuditLogWritePort auditLogs;

	@Autowired
	private LedgerOutbox ledgerOutbox;

	@Autowired
	private IdempotencyRecordStore idempotencyRecords;

	@Autowired
	private IdempotencyAnonymousSubjectHasher anonymousSubjectHasher;

	@AfterEach
	void removeIdempotencyCompletionFailureTrigger() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_sync_reject_idempotency_completion_for_test ON idempotency_records");
		jdbc.execute("DROP FUNCTION IF EXISTS sync_reject_idempotency_completion_for_test()");
	}

	@Test
	void authenticatedCreateIsAtomicAndTenReplaysProduceOneFactChain() throws Exception {
		Fixture fixture = fixture("create-replay");
		UUID operationId = UUID.randomUUID();
		UUID transactionId = UUID.randomUUID();
		String key = key();
		String body = envelope(income(operationId, key, transactionId, fixture.accountId(), fixture.incomeCategoryId(), "12.00"));

		mvc.perform(post("/api/v1/sync/operations").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		for (int attempt = 0; attempt < 10; attempt++) {
			mvc.perform(post("/api/v1/sync/operations")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(fixture.ownerId()))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.results[0].status").value(attempt == 0 ? "APPLIED" : "DUPLICATE"))
				.andExpect(jsonPath("$.data.results[0].entityId").value(transactionId.toString()))
				.andExpect(jsonPath("$.data.results[0].changeSequence").doesNotExist())
				.andExpect(jsonPath("$.meta.serverCursor").doesNotExist());
		}

		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC'", transactionId));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ? AND action = 'TRANSACTION_POSTED'", fixture.ownerId()));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId));
		assertEquals(1, count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ? AND status = 'SUCCEEDED'", fixture.ownerId(), key));
		assertEquals(0, count("SELECT count(*) FROM change_log WHERE entity_id = ?", transactionId));
	}

	@Test
	void perOperationRejectionKeepsBatchOrderAndDoesNotRollBackEarlierFact() throws Exception {
		Fixture fixture = fixture("batch");
		UUID appliedOperation = UUID.randomUUID();
		UUID appliedTransaction = UUID.randomUUID();
		UUID rejectedOperation = UUID.randomUUID();
		UUID rejectedTransaction = UUID.randomUUID();
		String invalid = income(rejectedOperation, key(), rejectedTransaction, fixture.accountId(), fixture.incomeCategoryId(), "5.00")
			.replace("\"categoryId\":\"" + fixture.incomeCategoryId() + "\"", "\"categoryId\":\"" + fixture.incomeCategoryId() + "\",\"tagIds\":[]");

		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(fixture.ownerId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(envelope(income(appliedOperation, key(), appliedTransaction, fixture.accountId(), fixture.incomeCategoryId(), "7.00"), invalid)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].operationId").value(appliedOperation.toString()))
			.andExpect(jsonPath("$.data.results[0].status").value("APPLIED"))
			.andExpect(jsonPath("$.data.results[1].operationId").value(rejectedOperation.toString()))
			.andExpect(jsonPath("$.data.results[1].status").value("REJECTED"));

		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ?", appliedTransaction));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", rejectedTransaction));
	}

	@Test
	void staleVisibleUpdateStoresAndReplaysTheFirstConflictReference() throws Exception {
		Fixture fixture = fixture("conflict");
		UUID original = UUID.randomUUID();
		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(fixture.ownerId()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(envelope(income(UUID.randomUUID(), key(), original, fixture.accountId(), fixture.incomeCategoryId(), "8.00"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("APPLIED"));

		UUID operationId = UUID.randomUUID();
		String conflictKey = key();
		String update = envelope(update(operationId, conflictKey, original, fixture.accountId(), fixture.incomeCategoryId(), 2));
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/sync/operations")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(fixture.ownerId()))
					.contentType(MediaType.APPLICATION_JSON).content(update))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.results[0].status").value(attempt == 0 ? "CONFLICT" : "DUPLICATE"))
				.andExpect(jsonPath("$.data.results[0].error.code").value("VERSION_CONFLICT"))
				.andExpect(jsonPath("$.data.results[0].error.versionConflict.currentVersion").value(1))
				.andExpect(jsonPath("$.data.results[0].error.versionConflict.currentEtag").value("\"1\""))
				.andExpect(jsonPath("$.data.results[0].error.versionConflict.resourceLocation")
					.value("/api/v1/transactions/" + original))
				.andExpect(jsonPath("$.data.results[0].error.currentResource").doesNotExist());
		}
		assertEquals("VERSION_CONFLICT", jdbc.queryForObject("""
			SELECT response_reference ->> 'kind' FROM idempotency_records
			WHERE user_id = ? AND idempotency_key = ?
			""", String.class, fixture.ownerId(), conflictKey));
		assertEquals("1", jdbc.queryForObject("""
			SELECT response_reference ->> 'currentVersion' FROM idempotency_records
			WHERE user_id = ? AND idempotency_key = ?
			""", String.class, fixture.ownerId(), conflictKey));
	}

	@Test
	void boundaryAndAuthorizationRejectWithoutOpeningUnknownRoutes() throws Exception {
		Fixture fixture = fixture("authorization");
		UUID viewer = insertUser("viewer");
		addMembership(fixture.accountId(), viewer, "VIEWER", "ACTIVE");
		String viewerBody = envelope(income(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), fixture.incomeCategoryId(), "3.00"));

		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer))
				.contentType(MediaType.APPLICATION_JSON).content(viewerBody))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("REJECTED"))
			.andExpect(jsonPath("$.data.results[0].error.versionConflict").doesNotExist());
		assertEquals(1, count("""
			SELECT count(*) FROM account_members
			WHERE account_id = ? AND user_id = ? AND role = 'VIEWER' AND status = 'ACTIVE'
				AND joined_at <= TIMESTAMPTZ '2026-08-16T04:00:00Z'
			""", fixture.accountId(), viewer));
		assertEquals(0, count("SELECT count(*) FROM ledger_accounts WHERE owner_user_id = ?", viewer));
		mvc.perform(post("/api/v1/sync/operations/nope")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(fixture.ownerId()))
				.contentType(MediaType.APPLICATION_JSON).content(viewerBody))
			.andExpect(status().isForbidden());

		List<String> operations = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			operations.add(income(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), fixture.incomeCategoryId(), "1.00"));
		}
		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer))
				.contentType(MediaType.APPLICATION_JSON).content(envelope(operations.toArray(String[]::new))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results.length()").value(100));
		operations.add(income(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), fixture.incomeCategoryId(), "1.00"));
		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer))
				.contentType(MediaType.APPLICATION_JSON).content(envelope(operations.toArray(String[]::new))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void requiredNullableFeeCategoryMustBePresentForCreateAndUpdateReplacement() throws Exception {
		Fixture fixture = fixture("fee-category");
		String token = bearer(fixture.ownerId());

		assertRejected(token, envelope(transfer(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), false)), "VALIDATION_ERROR");
		assertRejected(token, envelope(transfer(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), true)), "BUSINESS_RULE_VIOLATION");

		UUID original = UUID.randomUUID();
		assertApplied(token, envelope(income(UUID.randomUUID(), key(), original, fixture.accountId(), fixture.incomeCategoryId(), "4.00")));
		assertRejected(token, envelope(updateWithTransfer(UUID.randomUUID(), key(), original, fixture.accountId(), false)), "VALIDATION_ERROR");
		assertRejected(token, envelope(updateWithTransfer(UUID.randomUUID(), key(), original, fixture.accountId(), true)), "BUSINESS_RULE_VIOLATION");
	}

	@Test
	void semanticWritesCoverExpenseRefundTransfersRevisionAndReverseThroughHttp() throws Exception {
		Fixture fixture = fixture("semantic-matrix");
		String token = bearer(fixture.ownerId());
		UUID expenseId = UUID.randomUUID();
		UUID refundId = UUID.randomUUID();
		UUID transferWithoutFeeId = UUID.randomUUID();
		UUID transferWithFeeId = UUID.randomUUID();
		UUID revisionOriginalId = UUID.randomUUID();
		UUID reverseOriginalId = UUID.randomUUID();

		assertApplied(token, envelope(expense(UUID.randomUUID(), key(), expenseId, fixture.accountId(), fixture.expenseCategoryId(), "20.00")));
		assertApplied(token, envelope(refund(UUID.randomUUID(), key(), refundId, fixture.accountId(), expenseId, "5.00")));
		assertApplied(token, envelope(transfer(UUID.randomUUID(), key(), transferWithoutFeeId, fixture.accountId(), fixture.secondAccountId(), "12.00", "0.00", null)));
		assertApplied(token, envelope(transfer(UUID.randomUUID(), key(), transferWithFeeId, fixture.accountId(), fixture.secondAccountId(), "12.00", "1.00", fixture.expenseCategoryId())));
		assertApplied(token, envelope(expense(UUID.randomUUID(), key(), revisionOriginalId, fixture.accountId(), fixture.expenseCategoryId(), "11.00")));

		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content(envelope(updateExpense(UUID.randomUUID(), key(), revisionOriginalId, fixture.accountId(), fixture.expenseCategoryId(), 1))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("APPLIED"))
			.andExpect(jsonPath("$.data.results[0].entityId").isNotEmpty());
		UUID replacementId = jdbc.queryForObject("SELECT id FROM transactions WHERE previous_version_id = ?", UUID.class, revisionOriginalId);
		assertNotEquals(revisionOriginalId, replacementId);

		assertApplied(token, envelope(expense(UUID.randomUUID(), key(), reverseOriginalId, fixture.accountId(), fixture.expenseCategoryId(), "7.00")));
		mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content(envelope(reverse(UUID.randomUUID(), key(), reverseOriginalId, 1))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("APPLIED"))
			.andExpect(jsonPath("$.data.results[0].entityId").isNotEmpty())
			.andExpect(jsonPath("$.data.results[0].changeSequence").doesNotExist())
			.andExpect(jsonPath("$.meta.serverCursor").doesNotExist());
		UUID reversalId = jdbc.queryForObject("SELECT id FROM transactions WHERE reversal_of_id = ?", UUID.class, reverseOriginalId);
		assertNotEquals(reverseOriginalId, reversalId);

		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC'", expenseId));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC'", refundId));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC'", transferWithoutFeeId));
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC'", transferWithFeeId));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transferWithoutFeeId));
		assertEquals(4, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transferWithFeeId));
		assertEquals(1, count("SELECT count(*) FROM refund_details WHERE transaction_id = ? AND original_transaction_id = ?", refundId, expenseId));
		assertEquals(8, count("SELECT count(*) FROM audit_logs WHERE actor_user_id = ?", fixture.ownerId()));
		assertEquals(9, count("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (SELECT id FROM transactions WHERE created_by = ?)", fixture.ownerId()));
		assertEquals(0, count("SELECT count(*) FROM change_log WHERE entity_id IN (?, ?, ?, ?)", expenseId, refundId, transferWithoutFeeId, transferWithFeeId));
	}

	@Test
	void ownerAndEditorWriteWhileAllOtherMembershipStatesAreSafelyRejected() throws Exception {
		Fixture fixture = fixture("permission-matrix");
		String ownerToken = bearer(fixture.ownerId());
		String sharedKey = key();
		UUID ownerTransaction = UUID.randomUUID();
		assertApplied(ownerToken, envelope(income(UUID.randomUUID(), sharedKey, ownerTransaction, fixture.accountId(), fixture.incomeCategoryId(), "2.00")));
		assertRejected(ownerToken, envelope(income(UUID.randomUUID(), sharedKey, UUID.randomUUID(), fixture.accountId(), fixture.incomeCategoryId(), "3.00")), "IDEMPOTENCY_KEY_REUSED");

		UUID editor = insertUser("editor");
		addMembership(fixture.accountId(), editor, "EDITOR", "ACTIVE", null);
		UUID editorCategory = category(editor, fixture.accountId(), "INCOME", "编辑收入", fixture.startedAt());
		assertApplied(bearer(editor), envelope(income(UUID.randomUUID(), sharedKey, UUID.randomUUID(), fixture.accountId(), editorCategory, "2.00")));

		UUID viewer = insertUser("viewer-matrix");
		UUID left = insertUser("left");
		UUID removed = insertUser("removed");
		UUID ended = insertUser("ended");
		UUID stranger = insertUser("stranger");
		addMembership(fixture.accountId(), viewer, "VIEWER", "ACTIVE", null);
		addMembership(fixture.accountId(), left, "EDITOR", "LEFT", Instant.parse("2026-08-16T03:00:00Z"));
		addMembership(fixture.accountId(), removed, "EDITOR", "REMOVED", Instant.parse("2026-08-16T03:00:00Z"));
		addMembership(fixture.accountId(), ended, "EDITOR", "LEFT", Instant.parse("2026-08-15T00:00:00Z"));

		for (UUID denied : List.of(viewer, left, removed, ended, stranger)) {
			String body = envelope(income(UUID.randomUUID(), key(), UUID.randomUUID(), fixture.accountId(), fixture.incomeCategoryId(), "2.00"));
			mvc.perform(post("/api/v1/sync/operations").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(denied))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.results[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.data.results[0].error.versionConflict").doesNotExist())
				.andExpect(jsonPath("$.data.results[0].entityId").doesNotExist());
		}
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE user_id = ? AND role = 'EDITOR' AND status = 'ACTIVE' AND joined_at <= TIMESTAMPTZ '2026-08-16T04:00:00Z'", editor));
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE user_id = ? AND status = 'LEFT' AND ended_at <= TIMESTAMPTZ '2026-08-16T04:00:00Z'", ended));
		assertEquals(0, count("SELECT count(*) FROM ledger_accounts WHERE owner_user_id IN (?, ?, ?, ?)", viewer, left, removed, ended));
	}

	@Test
	void concurrentSameKeyUsesPostgresLockAndLeavesOneFactChainAndTerminalRecord() throws Exception {
		Fixture fixture = fixture("concurrent");
		UUID transactionId = UUID.randomUUID();
		SyncOperationApplicationService.Operation operation = operation(fixture.ownerId(), transactionId, key(), fixture);
		// 第一入口持有真实幂等行锁并暂停账务，第二入口只能得到 5 秒可重试结果，不能并行制造事实。
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		LedgerSyncCommandPort realLedger = ledgerService(fixture, auditLogs, ledgerOutbox);
		LedgerSyncCommandPort firstLedger = command -> {
			entered.countDown();
			await(release);
			return realLedger.applySync(command);
		};
		LedgerSyncCommandPort secondLedger = command -> { throw new AssertionError("第二请求不应执行账务工作"); };
		SyncOperationApplicationService firstService = sync(firstLedger);
		SyncOperationApplicationService secondService = sync(secondLedger);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<SyncOperationApplicationService.Result> first = executor.submit(
				() -> firstService.apply(fixture.ownerId(), operation, "sync-concurrent-first"));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			Future<SyncOperationApplicationService.Result> second = executor.submit(
				() -> secondService.apply(fixture.ownerId(), operation, "sync-concurrent-second"));
			SyncOperationApplicationService.Result secondResult = second.get(8, TimeUnit.SECONDS);
			assertEquals("RETRYABLE", secondResult.status());
			assertEquals("IDEMPOTENCY_REQUEST_IN_PROGRESS", secondResult.error().get("code"));
			release.countDown();
			assertEquals("APPLIED", first.get(5, TimeUnit.SECONDS).status());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId));
		assertEquals(1, count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ? AND status = 'SUCCEEDED'", fixture.ownerId(), operation.idempotencyKey()));
	}

	@Test
	void auditFailureIsRetryableAndRollsBackEveryFactIncludingIdempotency() throws Exception {
		Fixture fixture = fixture("atomic-audit");
		UUID transactionId = UUID.randomUUID();
		SyncOperationApplicationService.Operation operation = operation(fixture.ownerId(), transactionId, key(), fixture);
		AuditLogWritePort failingAudit = entry -> { throw new IllegalStateException("测试 audit 失败"); };
		SyncOperationApplicationService.Result result = sync(ledgerService(fixture, failingAudit, ledgerOutbox))
			.apply(fixture.ownerId(), operation, "sync-audit-failure");

		assertEquals("RETRYABLE", result.status());
		assertEquals("INTERNAL_ERROR", result.error().get("code"));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?", fixture.ownerId(), operation.idempotencyKey()));

		// 失败项只回滚自身；后续正常项仍可独立提交，批量顺序语义由 HTTP 混合批测试覆盖。
		assertApplied(bearer(fixture.ownerId()), envelope(income(UUID.randomUUID(), key(), UUID.randomUUID(),
			fixture.accountId(), fixture.incomeCategoryId(), "1.00")));
	}

	@Test
	void idempotencyCompletionFailureRollsBackBusinessFactsAndRecord() {
		Fixture fixture = fixture("atomic-idempotency");
		UUID transactionId = UUID.randomUUID();
		SyncOperationApplicationService.Operation operation = operation(fixture.ownerId(), transactionId, key(), fixture);
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION sync_reject_idempotency_completion_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN RAISE EXCEPTION 'test-only idempotency completion failure'; END $$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_sync_reject_idempotency_completion_for_test
			BEFORE UPDATE ON idempotency_records FOR EACH ROW
			EXECUTE FUNCTION sync_reject_idempotency_completion_for_test()
			""");

		SyncOperationApplicationService.Result result = sync(ledgerService(fixture, auditLogs, ledgerOutbox))
			.apply(fixture.ownerId(), operation, "sync-idempotency-failure");

		assertEquals("RETRYABLE", result.status());
		assertEquals("INTERNAL_ERROR", result.error().get("code"));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?", fixture.ownerId(), operation.idempotencyKey()));
	}

	@Test
	void outboxFailureIsRetryableAndLeavesNoPartialFacts() {
		Fixture fixture = fixture("atomic-outbox");
		UUID transactionId = UUID.randomUUID();
		SyncOperationApplicationService.Operation operation = operation(fixture.ownerId(), transactionId, key(), fixture);
		LedgerOutbox failingOutbox = event -> { throw new IllegalStateException("测试 outbox 失败"); };
		SyncOperationApplicationService.Result result = sync(ledgerService(fixture, auditLogs, failingOutbox))
			.apply(fixture.ownerId(), operation, "sync-outbox-failure");

		assertEquals("RETRYABLE", result.status());
		assertEquals("INTERNAL_ERROR", result.error().get("code"));
		assertEquals(0, count("SELECT count(*) FROM transactions WHERE id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId));
		assertEquals(0, count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?", fixture.ownerId(), operation.idempotencyKey()));
	}

	private void assertApplied(String token, String body) throws Exception {
		mvc.perform(post("/api/v1/sync/operations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("APPLIED"));
	}

	private void assertRejected(String token, String body, String code) throws Exception {
		mvc.perform(post("/api/v1/sync/operations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.results[0].status").value("REJECTED"))
			.andExpect(jsonPath("$.data.results[0].error.code").value(code));
	}

	private SyncOperationApplicationService sync(LedgerSyncCommandPort ledger) {
		return new SyncOperationApplicationService(
			new UnifiedIdempotencyService(transactions, idempotencyRecords, anonymousSubjectHasher,
				Clock.fixed(Instant.parse("2026-08-16T04:00:00Z"), ZoneOffset.UTC)), ledger);
	}

	private LedgerCommandApplicationService ledgerService(
		Fixture fixture, AuditLogWritePort audits, LedgerOutbox outbox) {
		return new LedgerCommandApplicationService(
			transactions, accounts, accountAccess, categories, ledgerAccounts, ledgerTransactions, audits, outbox,
			() -> "sync-test-request", new PostingService(), Clock.fixed(Instant.parse("2026-08-16T04:00:00Z"), ZoneOffset.UTC));
	}

	private SyncOperationApplicationService.Operation operation(
		UUID userId, UUID transactionId, String idempotencyKey, Fixture fixture) {
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("type", "EXPENSE");
		payload.put("accountId", fixture.accountId());
		payload.put("categoryId", fixture.expenseCategoryId());
		payload.put("amount", "1.00");
		payload.put("currency", "CNY");
		Map<String, Object> hashPayload = new java.util.LinkedHashMap<>();
		hashPayload.put("operationId", transactionId);
		hashPayload.put("entityType", "TRANSACTION");
		hashPayload.put("operationType", "CREATE");
		hashPayload.put("entityId", transactionId);
		hashPayload.put("baseVersion", null);
		hashPayload.put("payloadVersion", 1);
		hashPayload.put("payload", payload);
		return new SyncOperationApplicationService.Operation(
			transactionId, idempotencyKey, transactionId, null, 1,
			new SyncLedgerCommand.Expense(userId, transactionId, fixture.accountId(), fixture.expenseCategoryId(),
				new Money(new java.math.BigDecimal("1.00"), CurrencyCode.CNY),
				Instant.parse("2026-08-16T04:00:00Z"), java.time.LocalDate.of(2026, 8, 16), "Asia/Shanghai", null, null),
			hashPayload);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("测试并发栅栏超时");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("测试并发线程被中断", exception);
		}
	}

	private Fixture fixture(String suffix) {
		UUID ownerId = insertUser("owner-" + suffix);
		UUID accountId = UUID.randomUUID();
		UUID secondAccountId = UUID.randomUUID();
		// 业务时间固定在 8 月；成员周期必须更早生效，避免夹具误把合法 OWNER 造成为历史无权。
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return transactions.required(() -> {
			insertVisibleAccount(ownerId, accountId, now);
			insertVisibleAccount(ownerId, secondAccountId, now);
			UUID incomeCategory = category(ownerId, accountId, "INCOME", "同步收入", now);
			UUID expenseCategory = category(ownerId, accountId, "EXPENSE", "同步支出", now);
			return new Fixture(ownerId, accountId, secondAccountId, incomeCategory, expenseCategory, now);
		});
	}

	private void insertVisibleAccount(UUID ownerId, UUID accountId, Instant now) {
		UUID membershipId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO accounts (id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'ASSET', 'BANK', ?, 'CNY', 'ACTIVE', ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", accountId, "同步账户-" + accountId, ownerId, now.toString(), now.toString());
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
			VALUES (?, ?, ?, 'OWNER', 'ACTIVE', CAST(? AS timestamptz), 1, 1)
			""", membershipId, accountId, ownerId, now.toString());
		jdbc.update("""
			INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""", UUID.randomUUID(), membershipId, now.toString(), ownerId, now.toString());
		jdbc.update("""
			INSERT INTO ledger_accounts (id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', CAST(? AS timestamptz))
			""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, now.toString());
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "sync-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '同步 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return userId;
	}

	private void addMembership(UUID accountId, UUID userId, String role, String membershipStatus) {
		addMembership(accountId, userId, role, membershipStatus, null);
	}

	private void addMembership(UUID accountId, UUID userId, String role, String membershipStatus, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		// 角色测试必须让成员周期早于固定 businessAt，拒绝原因才能归因于 VIEWER 而非尚未加入。
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
			VALUES (?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1, 1)
			""", membershipId, accountId, userId, role, membershipStatus, now.toString(), endedAt == null ? null : endedAt.toString());
		jdbc.update("""
			INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""", UUID.randomUUID(), membershipId, now.toString(), userId, now.toString());
	}

	private UUID category(UUID ownerId, UUID accountId, String type, String name, Instant now) {
		UUID categoryId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, account_id, category_type, parent_id, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, NULL, ?, ?, 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", categoryId, ownerId, accountId, type, name, name, now.toString(), now.toString());
		return categoryId;
	}

	private static String expense(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, UUID categoryId, String amount) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"CREATE","baseVersion":null,"payloadVersion":1,
			 "payload":{"type":"EXPENSE","businessAt":"2026-08-16T04:00:00Z","businessDate":"2026-08-16",
			 "timezone":"Asia/Shanghai","accountId":"%s","amount":"%s","currency":"CNY","categoryId":"%s"},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, amount, categoryId).replaceAll("\\s+", "");
	}

	private static String refund(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, UUID originalTransactionId, String amount) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"CREATE","baseVersion":null,"payloadVersion":1,
			 "payload":{"type":"REFUND","businessAt":"2026-08-16T04:00:00Z","businessDate":"2026-08-16",
			 "timezone":"Asia/Shanghai","accountId":"%s","amount":"%s","currency":"CNY","originalTransactionId":"%s"},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, amount, originalTransactionId).replaceAll("\\s+", "");
	}

	private static String updateExpense(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, UUID categoryId, int baseVersion) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"UPDATE","baseVersion":%d,"payloadVersion":1,
			 "payload":{"reason":"同步修订","replacement":{"type":"EXPENSE","businessAt":"2026-08-16T04:00:00Z",
			 "businessDate":"2026-08-16","timezone":"Asia/Shanghai","accountId":"%s","amount":"21.00","currency":"CNY","categoryId":"%s"}},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, baseVersion, accountId, categoryId).replaceAll("\\s+", "");
	}

	private static String reverse(UUID operationId, String idempotencyKey, UUID transactionId, int baseVersion) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"REVERSE","baseVersion":%d,"payloadVersion":1,"payload":{"reason":"同步作废"},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, baseVersion).replaceAll("\\s+", "");
	}

	private static String transfer(UUID operationId, String idempotencyKey, UUID transactionId, UUID fromAccountId,
		UUID toAccountId, String amount, String fee, UUID feeCategoryId) {
		String category = feeCategoryId == null ? "null" : "\"" + feeCategoryId + "\"";
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"CREATE","baseVersion":null,"payloadVersion":1,
			 "payload":{"type":"TRANSFER","businessAt":"2026-08-16T04:00:00Z","businessDate":"2026-08-16","timezone":"Asia/Shanghai",
			 "fromAccountId":"%s","toAccountId":"%s","fromAmount":{"amount":"%s","currency":"CNY"},
			 "toAmount":{"amount":"%s","currency":"CNY"},"fee":{"amount":"%s","currency":"CNY"},"feeCategoryId":%s},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, fromAccountId, toAccountId, amount, amount, fee, category).replaceAll("\\s+", "");
	}

	private String bearer(UUID userId) {
		SessionTokenResult result = sessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "sync-http", "sync-device-" + userId));
		return result.accessToken();
	}

	private int count(String sql, Object... arguments) {
		Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
		return value == null ? 0 : value;
	}

	private static String envelope(String... operations) {
		return "{\"deviceId\":\"sync-http-device\",\"operations\":[" + String.join(",", operations) + "]}";
	}

	private static String income(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, UUID categoryId, String amount) {
		return """
			{\"operationId\":\"%s\",\"idempotencyKey\":\"%s\",\"entityType\":\"TRANSACTION\",\"entityId\":\"%s\",
			 \"operationType\":\"CREATE\",\"baseVersion\":null,\"payloadVersion\":1,
			 \"payload\":{\"type\":\"INCOME\",\"businessAt\":\"2026-08-16T04:00:00Z\",\"businessDate\":\"2026-08-16\",
			 \"timezone\":\"Asia/Shanghai\",\"accountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"CNY\",\"categoryId\":\"%s\"},
			 \"createdAt\":\"2026-08-16T04:00:00Z\"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, amount, categoryId).replaceAll("\\s+", "");
	}

	private static String update(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, UUID categoryId, int baseVersion) {
		return """
			{\"operationId\":\"%s\",\"idempotencyKey\":\"%s\",\"entityType\":\"TRANSACTION\",\"entityId\":\"%s\",
			 \"operationType\":\"UPDATE\",\"baseVersion\":%d,\"payloadVersion\":1,
			 \"payload\":{\"reason\":\"测试陈旧版本\",\"replacement\":{\"type\":\"INCOME\",\"businessAt\":\"2026-08-16T04:00:00Z\",
			 \"businessDate\":\"2026-08-16\",\"timezone\":\"Asia/Shanghai\",\"accountId\":\"%s\",\"amount\":\"9.00\",\"currency\":\"CNY\",\"categoryId\":\"%s\"}},
			 \"createdAt\":\"2026-08-16T04:00:00Z\"}
			""".formatted(operationId, idempotencyKey, transactionId, baseVersion, accountId, categoryId).replaceAll("\\s+", "");
	}

	private static String transfer(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, boolean explicitNullFeeCategory) {
		String feeCategory = explicitNullFeeCategory ? ",\"feeCategoryId\":null" : "";
		return """
			{\"operationId\":\"%s\",\"idempotencyKey\":\"%s\",\"entityType\":\"TRANSACTION\",\"entityId\":\"%s\",
			 \"operationType\":\"CREATE\",\"baseVersion\":null,\"payloadVersion\":1,
			 \"payload\":{\"type\":\"TRANSFER\",\"businessAt\":\"2026-08-16T04:00:00Z\",\"businessDate\":\"2026-08-16\",\"timezone\":\"Asia/Shanghai\",
			 \"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"fromAmount\":{\"amount\":\"1.00\",\"currency\":\"CNY\"},
			 \"toAmount\":{\"amount\":\"1.00\",\"currency\":\"CNY\"},\"fee\":{\"amount\":\"0.00\",\"currency\":\"CNY\"}%s},
			 \"createdAt\":\"2026-08-16T04:00:00Z\"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, accountId, feeCategory).replaceAll("\\s+", "");
	}

	private static String updateWithTransfer(UUID operationId, String idempotencyKey, UUID transactionId, UUID accountId, boolean explicitNullFeeCategory) {
		String feeCategory = explicitNullFeeCategory ? ",\"feeCategoryId\":null" : "";
		return """
			{\"operationId\":\"%s\",\"idempotencyKey\":\"%s\",\"entityType\":\"TRANSACTION\",\"entityId\":\"%s\",
			 \"operationType\":\"UPDATE\",\"baseVersion\":1,\"payloadVersion\":1,
			 \"payload\":{\"reason\":\"替代转账\",\"replacement\":{\"type\":\"TRANSFER\",\"businessAt\":\"2026-08-16T04:00:00Z\",
			 \"businessDate\":\"2026-08-16\",\"timezone\":\"Asia/Shanghai\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",
			 \"fromAmount\":{\"amount\":\"1.00\",\"currency\":\"CNY\"},\"toAmount\":{\"amount\":\"1.00\",\"currency\":\"CNY\"},
			 \"fee\":{\"amount\":\"0.00\",\"currency\":\"CNY\"}%s}},\"createdAt\":\"2026-08-16T04:00:00Z\"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, accountId, feeCategory).replaceAll("\\s+", "");
	}

	private static String key() {
		return "sync-operation-key-" + UUID.randomUUID();
	}

	private record Fixture(UUID ownerId, UUID accountId, UUID secondAccountId, UUID incomeCategoryId,
		UUID expenseCategoryId, Instant startedAt) {
	}
}
