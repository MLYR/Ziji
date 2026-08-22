package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.accountmember.infrastructure.PostgresAccountPostingAccessPort;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实 SecurityFilterChain + PostgreSQL 验收 Ledger 读取的分页、ETag 与 404 防枚举。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TransactionHttpIntegrationTests.PermissionRaceConfiguration.class)
class TransactionHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private DeviceSessionApplicationService deviceSessions;
	@Autowired private TransactionRunner transactions;
	@Autowired private PermissionRacePostingAccessPort permissionRace;

	@Test
	void postsExpenseReplaysSameKeyAndIsImmediatelyQueryable() throws Exception {
		User owner = user("ledger-write-owner");
		Account account = account(owner.id());
		UUID categoryId = category(owner.id());
		String token = bearer(owner);
		String key = "ledger-expense-key-0001";
		String body = """
			{
			  "type":"EXPENSE",
			  "businessAt":"2026-08-17T12:00:00Z",
			  "accountId":"%s",
			  "amount":"50.00",
			  "currency":"CNY",
			  "categoryId":"%s",
			  "merchant":"示例餐厅",
			  "tagIds":[]
			}
			""".formatted(account.id(), categoryId);

		MvcResult first = mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/transactions/")))
			.andExpect(jsonPath("$.data.type").value("EXPENSE"))
			.andExpect(jsonPath("$.data.businessDate").value("2026-08-17"))
			.andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
			.andExpect(jsonPath("$.data.entries.length()").value(2)).andReturn();
		String transactionId = json(first).at("/data/id").asString();

		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(transactionId));
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(body.replace("50.00", "51.00")))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		mvc.perform(get("/api/v1/transactions/{id}", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class,
			UUID.fromString(transactionId)));
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class,
			UUID.fromString(transactionId)));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id = ?", Integer.class,
			UUID.fromString(transactionId)));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class,
			UUID.fromString(transactionId)));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE operation_id = 'postTransaction' AND idempotency_key = ?",
			Integer.class, key));
	}

	@Test
	void revisesAndReversesWithStrongIfMatchAndPersistsSafeConflictReference() throws Exception {
		User owner = user("ledger-mutation-owner");
		Account account = account(owner.id());
		UUID categoryId = category(owner.id());
		String token = bearer(owner);
		String createBody = """
			{"type":"EXPENSE","businessAt":"2026-08-17T12:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.id(), categoryId);
		String originalId = json(mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-create-key-01")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(createBody))
			.andExpect(status().isCreated()).andReturn()).at("/data/id").asString();

		String replacementId = UUID.randomUUID().toString();
		String revisionBody = """
			{"reason":"修订金额","replacement":{"id":"%s","type":"EXPENSE",
			 "businessAt":"2026-08-17T13:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"12.00","currency":"CNY","categoryId":"%s"}}
			""".formatted(replacementId, account.id(), categoryId);
		mvc.perform(post("/api/v1/transactions/{id}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-revision-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(revisionBody))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.LOCATION,
				"/api/v1/transactions/" + replacementId)).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(replacementId));

		mvc.perform(post("/api/v1/transactions/{id}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-revision-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(revisionBody))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(replacementId));
		assertEquals(2, jdbc.queryForObject("SELECT entity_version FROM transactions WHERE id = ?", Integer.class, UUID.fromString(originalId)));
		assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, UUID.fromString(originalId)));

		FactCounts beforeStaleRevision = factCounts();
		mvc.perform(post("/api/v1/transactions/{id}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-stale-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(revisionBody))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value("/api/v1/transactions/" + originalId));
		mvc.perform(post("/api/v1/transactions/{id}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-stale-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(revisionBody))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value("/api/v1/transactions/" + originalId));
		FactCounts afterStaleRevision = factCounts();
		assertEquals(beforeStaleRevision.transactions(), afterStaleRevision.transactions());
		assertEquals(beforeStaleRevision.entries(), afterStaleRevision.entries());
		assertEquals(beforeStaleRevision.audits(), afterStaleRevision.audits());
		assertEquals(beforeStaleRevision.outbox(), afterStaleRevision.outbox());
		assertEquals(beforeStaleRevision.idempotency() + 1, afterStaleRevision.idempotency());

		String reasonBody = "{\"reason\":\"作废\"}";
		mvc.perform(post("/api/v1/transactions/{id}/reversal", replacementId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-reverse-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(reasonBody))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.reversalOfId").value(replacementId));
		FactCounts beforeStaleReverse = factCounts();
		mvc.perform(post("/api/v1/transactions/{id}/reversal", replacementId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-reverse-stale-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(reasonBody))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value("/api/v1/transactions/" + replacementId));
		mvc.perform(post("/api/v1/transactions/{id}/reversal", replacementId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "mutation-reverse-stale-key-01")
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(reasonBody))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value("/api/v1/transactions/" + replacementId));
		FactCounts afterStaleReverse = factCounts();
		assertEquals(beforeStaleReverse.transactions(), afterStaleReverse.transactions());
		assertEquals(beforeStaleReverse.entries(), afterStaleReverse.entries());
		assertEquals(beforeStaleReverse.audits(), afterStaleReverse.audits());
		assertEquals(beforeStaleReverse.outbox(), afterStaleReverse.outbox());
		assertEquals(beforeStaleReverse.idempotency() + 1, afterStaleReverse.idempotency());

		// 直接篡改历史安全引用，验证交易 operation 只回放当前原交易的 canonical 地址。
		String staleKey = "mutation-stale-key-01";
		jdbc.update("""
			UPDATE idempotency_records
			SET response_reference = jsonb_build_object(
				'kind', 'VERSION_CONFLICT', 'errorCode', 'VERSION_CONFLICT',
				'currentVersion', 2, 'currentEtag', '"2"',
				'resourceLocation', ?)
			WHERE user_id = ? AND operation_id = 'reviseTransaction' AND idempotency_key = ?
			""", "/api/v1/transactions/00000000-0000-0000-0000-000000000999", owner.id(), staleKey);
		mvc.perform(post("/api/v1/transactions/{id}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", staleKey)
				.header("If-Match", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(revisionBody))
			.andExpect(status().isInternalServerError()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());
	}

	@Test
	void postsEveryFrozenSemanticUnionAndBalanceAdjustmentThroughLedgerFacts() throws Exception {
		User owner = user("ledger-union-owner");
		Account cash = account(owner.id());
		Account savings = account(owner.id());
		Account creditCard = account(owner.id(), "LIABILITY", "CREDIT_CARD", "CNY");
		Account loan = account(owner.id(), "LIABILITY", "LOAN", "CNY");
		UUID incomeCategory = category(owner.id(), "INCOME");
		UUID expenseCategory = category(owner.id(), "EXPENSE");
		UUID feeCategory = category(owner.id(), "EXPENSE");
		String token = bearer(owner);

		postLedgerTransaction(token, "union-income-key-0001", """
			{"type":"INCOME","businessAt":"2026-08-17T01:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"100.00","currency":"CNY","categoryId":"%s"}
			""".formatted(cash.id(), incomeCategory), "INCOME");
		postLedgerTransaction(token, "union-credit-key-0001", """
			{"type":"EXPENSE","businessAt":"2026-08-17T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"30.00","currency":"CNY","categoryId":"%s"}
			""".formatted(creditCard.id(), expenseCategory), "EXPENSE");
		UUID expenseId = postLedgerTransaction(token, "union-expense-key-001", """
			{"type":"EXPENSE","businessAt":"2026-08-17T03:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"20.00","currency":"CNY","categoryId":"%s"}
			""".formatted(cash.id(), expenseCategory), "EXPENSE");
		postLedgerTransaction(token, "union-refund-key-0001", """
			{"type":"REFUND","businessAt":"2026-08-17T04:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"5.00","currency":"CNY","originalTransactionId":"%s"}
			""".formatted(cash.id(), expenseId), "REFUND");
		postLedgerTransaction(token, "union-transfer-key-001", """
			{"type":"TRANSFER","businessAt":"2026-08-17T05:00:00Z","timezone":"Asia/Shanghai",
			 "fromAccountId":"%s","toAccountId":"%s",
			 "fromAmount":{"amount":"10.00","currency":"CNY"},
			 "toAmount":{"amount":"10.00","currency":"CNY"},
			 "fee":{"amount":"1.00","currency":"CNY"},"feeCategoryId":"%s"}
			""".formatted(cash.id(), savings.id(), feeCategory), "TRANSFER");
		postLedgerTransaction(token, "union-borrow-key-00001", """
			{"type":"LIABILITY_BORROWING","businessAt":"2026-08-17T06:00:00Z","timezone":"Asia/Shanghai",
			 "assetAccountId":"%s","liabilityAccountId":"%s","currency":"CNY","amount":"1000.00"}
			""".formatted(cash.id(), loan.id()), "TRANSFER");
		postLedgerTransaction(token, "union-repayment-key-01", """
			{"type":"LIABILITY_REPAYMENT","businessAt":"2026-08-17T07:00:00Z","timezone":"Asia/Shanghai",
			 "cashAccountId":"%s","liabilityAccountId":"%s","currency":"CNY",
			 "principalAmount":"100.00","interestAmount":"2.00","feeAmount":"1.00",
			 "interestCategoryId":"%s","feeCategoryId":"%s"}
			""".formatted(cash.id(), loan.id(), expenseCategory, feeCategory), "REPAYMENT");

		mvc.perform(post("/api/v1/accounts/{id}/balance-adjustments", savings.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "union-adjustment-key-01")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""
					{"actualBalance":"100.00","businessAt":"2026-08-17T08:00:00Z",
					 "timezone":"Asia/Shanghai","reason":"与银行余额对账"}
					"""))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.type").value("ADJUSTMENT"));

		assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM transactions WHERE created_by = ?", Integer.class, owner.id()));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_details", Integer.class));
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM transfer_details", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM repayment_details", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM balance_adjustment_details", Integer.class));
	}

	@Test
	void currentActiveMembershipAllowsHistoricalIncomeBusinessAt() throws Exception {
		User owner = user("ledger-historical-income-owner");
		Account account = account(owner.id());
		UUID incomeCategory = category(owner.id(), "INCOME");
		Instant businessAt = Instant.parse("2020-01-01T01:00:00Z");
		Instant joinedAt = jdbc.queryForObject(
			"SELECT joined_at FROM account_members WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'",
			java.sql.Timestamp.class, account.id(), owner.id()).toInstant();
		assertTrue(businessAt.isBefore(joinedAt));

		// businessAt 固化历史账务日期，不应被误当成当前成员授权的生效时点。
		UUID transactionId = postLedgerTransaction(bearer(owner), "historical-income-key-01", """
			{"type":"INCOME","businessAt":"2020-01-01T01:00:00Z","timezone":"UTC",
			 "accountId":"%s","amount":"100.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.id(), incomeCategory), "INCOME");
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
			Integer.class, transactionId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM idempotency_records WHERE idempotency_key = 'historical-income-key-01' AND status = 'SUCCEEDED'",
			Integer.class));
	}

	@Test
	void writePermissionAndValidationFailuresAreBoundedAndDoNotAcquireIdempotency() throws Exception {
		User owner = user("ledger-write-permission-owner");
		User editor = user("ledger-write-permission-editor");
		User viewer = user("ledger-write-permission-viewer");
		User left = user("ledger-write-permission-left");
		User removed = user("ledger-write-permission-removed");
		User creatorOnly = user("ledger-write-permission-creator");
		User stranger = user("ledger-write-permission-stranger");
		Account account = account(owner.id());
		membership(account.id(), editor.id(), "EDITOR", "ACTIVE", null);
		membership(account.id(), viewer.id(), "VIEWER", "ACTIVE", null);
		membership(account.id(), left.id(), "EDITOR", "LEFT", Instant.now().minus(1, ChronoUnit.HOURS));
		membership(account.id(), removed.id(), "EDITOR", "REMOVED", Instant.now().minus(1, ChronoUnit.HOURS));
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", creatorOnly.id(), account.id());
		String body = """
			{"type":"EXPENSE","businessAt":"2026-08-17T12:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY",
			 "categoryId":"00000000-0000-4000-8000-000000000201"}
			""".formatted(account.id());

		postLedgerTransaction(bearer(editor), "permission-editor-key-1", body, "EXPENSE");
		assertWriteFailure(viewer, body, "permission-viewer-key-1", 403, "PERMISSION_DENIED");
		for (var value : List.of(
			new FailureUser(left, "permission-left-key-001"),
			new FailureUser(removed, "permission-removed-key1"),
			new FailureUser(creatorOnly, "permission-creator-key1"),
			new FailureUser(stranger, "permission-stranger-key"))) {
			assertWriteFailure(value.user(), body, value.key(), 404, "RESOURCE_NOT_FOUND");
		}
		mvc.perform(post("/api/v1/transactions").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "permission-noauth-key1").content(body))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

		String invalidKey = "permission-invalid-key1";
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner)).header("Idempotency-Key", invalidKey)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(body.substring(0, body.length() - 2) + ",\"entries\":[]}"))
			.andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		for (String key : List.of(
			"permission-viewer-key-1", "permission-left-key-001", "permission-removed-key1",
			"permission-creator-key1", "permission-stranger-key", "permission-noauth-key1", invalidKey)) {
			assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
				Integer.class, key));
		}
	}

	@Test
	void permissionChangesAfterPreflightRollbackEveryWriteOperation() throws Exception {
		User postOwner = user("ledger-race-post-owner");
		User postEditor = user("ledger-race-post-editor");
		Account postAccount = account(postOwner.id());
		membership(postAccount.id(), postEditor.id(), "EDITOR", "ACTIVE", null);
		UUID postCategory = category(postOwner.id());
		String postKey = "permission-race-post-key";
		assertPermissionRaceRollback(
			post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(postEditor))
				.header("Idempotency-Key", postKey)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
					{"type":"EXPENSE","businessAt":"2026-08-17T12:00:00Z","timezone":"Asia/Shanghai",
					 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
					""".formatted(postAccount.id(), postCategory)),
			postEditor.id(), postAccount.id(), postKey, MembershipChange.ENDED, 404, "RESOURCE_NOT_FOUND");

		User reviseOwner = user("ledger-race-revise-owner");
		User reviseEditor = user("ledger-race-revise-editor");
		Account reviseAccount = account(reviseOwner.id());
		membership(reviseAccount.id(), reviseEditor.id(), "EDITOR", "ACTIVE", null);
		UUID reviseCategory = category(reviseOwner.id());
		UUID reviseOriginal = postLedgerTransaction(bearer(reviseOwner), "permission-race-revise-create", """
			{"type":"EXPENSE","businessAt":"2026-08-17T10:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(reviseAccount.id(), reviseCategory), "EXPENSE");
		String reviseKey = "permission-race-revise-key";
		assertPermissionRaceRollback(
			post("/api/v1/transactions/{id}/revisions", reviseOriginal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(reviseEditor))
				.header("Idempotency-Key", reviseKey).header("If-Match", "\"1\"")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
					{"reason":"并发降权","replacement":{"type":"EXPENSE",
					 "businessAt":"2026-08-17T11:00:00Z","timezone":"Asia/Shanghai",
					 "accountId":"%s","amount":"11.00","currency":"CNY","categoryId":"%s"}}
					""".formatted(reviseAccount.id(), reviseCategory)),
			reviseEditor.id(), reviseAccount.id(), reviseKey, MembershipChange.VIEWER, 403, "PERMISSION_DENIED");

		User reverseOwner = user("ledger-race-reverse-owner");
		User reverseEditor = user("ledger-race-reverse-editor");
		Account reverseAccount = account(reverseOwner.id());
		membership(reverseAccount.id(), reverseEditor.id(), "EDITOR", "ACTIVE", null);
		UUID reverseCategory = category(reverseOwner.id());
		UUID reverseOriginal = postLedgerTransaction(bearer(reverseOwner), "permission-race-reverse-create", """
			{"type":"EXPENSE","businessAt":"2026-08-17T09:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(reverseAccount.id(), reverseCategory), "EXPENSE");
		String reverseKey = "permission-race-reverse-key";
		assertPermissionRaceRollback(
			post("/api/v1/transactions/{id}/reversal", reverseOriginal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(reverseEditor))
				.header("Idempotency-Key", reverseKey).header("If-Match", "\"1\"")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"并发结束成员周期\"}"),
			reverseEditor.id(), reverseAccount.id(), reverseKey, MembershipChange.REMOVED, 404, "RESOURCE_NOT_FOUND");

		User adjustmentOwner = user("ledger-race-adjustment-owner");
		User adjustmentEditor = user("ledger-race-adjustment-editor");
		Account adjustmentAccount = account(adjustmentOwner.id());
		membership(adjustmentAccount.id(), adjustmentEditor.id(), "EDITOR", "ACTIVE", null);
		String adjustmentKey = "permission-race-adjustment-key";
		assertPermissionRaceRollback(
			post("/api/v1/accounts/{id}/balance-adjustments", adjustmentAccount.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(adjustmentEditor))
				.header("Idempotency-Key", adjustmentKey)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
					{"actualBalance":"100.00","businessAt":"2026-08-17T12:00:00Z",
					 "timezone":"Asia/Shanghai","reason":"并发降权"}
					"""),
			adjustmentEditor.id(), adjustmentAccount.id(), adjustmentKey,
			MembershipChange.VIEWER, 403, "PERMISSION_DENIED");
	}

	@Test
	void validatesIfMatchBusinessRulesAndMultiAccount404BeforeIdempotency() throws Exception {
		User owner = user("ledger-precondition-owner");
		User viewer = user("ledger-precondition-viewer");
		User otherOwner = user("ledger-precondition-other");
		Account account = account(owner.id());
		Account invisibleAccount = account(otherOwner.id());
		membership(account.id(), viewer.id(), "VIEWER", "ACTIVE", null);
		String ownerToken = bearer(owner);
		UUID categoryId = category(owner.id());
		UUID transactionId = postLedgerTransaction(ownerToken, "precondition-create-key", """
			{"type":"EXPENSE","businessAt":"2026-08-17T10:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.id(), categoryId), "EXPENSE");
		String reason = "{\"reason\":\"作废\"}";
		List<String> malformed = java.util.Arrays.asList(null, "W/\"1\"", "*", "1", "\"0\"", "\"-1\"",
			"\"abc\"", "\"2147483648\"", "\"1\", \"2\"");
		for (int index = 0; index < malformed.size(); index++) {
			String key = "invalid-if-match-key-" + index;
			var request = post("/api/v1/transactions/{id}/reversal", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken).header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(reason);
			if (malformed.get(index) != null) request.header("If-Match", malformed.get(index));
			mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
				Integer.class, key));
		}
		mvc.perform(post("/api/v1/transactions/{id}/reversal", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken).header("Idempotency-Key", "invalid-if-match-duplicate")
				.header("If-Match", "\"1\"", "\"1\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(reason))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		String transfer = """
			{"type":"TRANSFER","businessAt":"2026-08-17T11:00:00Z","timezone":"Asia/Shanghai",
			 "fromAccountId":"%s","toAccountId":"%s",
			 "fromAmount":{"amount":"10.00","currency":"CNY"},
			 "toAmount":{"amount":"10.00","currency":"CNY"},"fee":{"amount":"0","currency":"CNY"}}
			""".formatted(account.id(), invisibleAccount.id());
		assertWriteFailure(viewer, transfer, "multi-account-hidden-key", 404, "RESOURCE_NOT_FOUND");
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
			Integer.class, "multi-account-hidden-key"));

		// 非空 tagIds 是 schema 合法但当前事实链未开放的业务拒绝，不能被静默丢弃。
		String unsupportedTags = """
			{"type":"EXPENSE","businessAt":"2026-08-17T12:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s","tagIds":["%s"]}
			""".formatted(account.id(), categoryId, UUID.randomUUID());
		mvc.perform(post("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.header("Idempotency-Key", "unsupported-tags-key-01")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(unsupportedTags))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?",
			Integer.class, "unsupported-tags-key-01"));

		String missingCategory = """
			{"type":"EXPENSE","businessAt":"2026-08-17T12:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.id(), UUID.randomUUID());
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
					.header("Idempotency-Key", "failed-final-category-key")
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(missingCategory))
				.andExpect(status().isUnprocessableEntity()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		}
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ? AND status = 'FAILED_FINAL'",
			Integer.class, "failed-final-category-key"));
	}

	@Test
	void listsWithStableKeysetAndReturnsStrongEtagForVisibleDetail() throws Exception {
		User owner = user("ledger-owner");
		Account account = account(owner.id());
		List<UUID> seeded = List.of(
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z")),
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-15T01:00:00Z")),
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-14T01:00:00Z")));
		String token = bearer(owner);

		List<String> actual = new ArrayList<>();
		String cursor = null;
		while (true) {
			var request = get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("accountId", account.id().toString()).param("limit", "1");
			if (cursor != null) request = request.param("cursor", cursor);
			MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
			JsonNode body = json(result);
			actual.add(body.at("/data/0/id").asString());
			if (!body.at("/meta/hasMore").asBoolean()) break;
			cursor = body.at("/meta/nextCursor").asString();
		}
		assertEquals(seeded.stream().map(UUID::toString).toList(), actual);
		assertEquals(3, new HashSet<>(actual).size());

		mvc.perform(get("/api/v1/transactions/{id}", seeded.getFirst())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(seeded.getFirst().toString()))
			.andExpect(jsonPath("$.data.entries[0].ledgerAccountId").exists())
			.andExpect(jsonPath("$.data.internalLedgerAccountId").doesNotExist());
	}

	@Test
	void rejectsInvalidInputAndHidesTransactionsWithoutCurrentMembership() throws Exception {
		User owner = user("ledger-owner-hidden");
		User stranger = user("ledger-stranger");
		Account account = account(owner.id());
		UUID transactionId = transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z"));
		String ownerToken = bearer(owner);
		String strangerToken = bearer(stranger);

		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("dateFrom", "2026-08-17").param("dateTo", "2026-08-16"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "201")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("cursor", "invalid")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
				.param("accountId", account.id().toString()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		mvc.perform(get("/api/v1/transactions/{id}", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void hidesInvisibleTransactionBeforeMalformedMutationInput() throws Exception {
		User owner = user("ledger-mutation-hidden-owner");
		User stranger = user("ledger-mutation-hidden-stranger");
		Account account = account(owner.id());
		UUID categoryId = category(owner.id());
		UUID transactionId = transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z"));
		String token = bearer(stranger);
		String validRevisionBody = """
			{"reason":"修订","replacement":{"type":"EXPENSE",
			 "businessAt":"2026-08-16T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"11.00","currency":"CNY","categoryId":"%s"}}
			""".formatted(account.id(), categoryId);

		for (var request : List.of(
			post("/api/v1/transactions/{id}/revisions", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "hidden-revise-body-key")
				.header("If-Match", "\"abc\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{not-json"),
			post("/api/v1/transactions/{id}/revisions", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "hidden-revise-header-key")
				.header("If-Match", "\"abc\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(validRevisionBody),
			post("/api/v1/transactions/{id}/reversal", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "hidden-reverse-body-key")
				.header("If-Match", "\"abc\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{not-json"),
			post("/api/v1/transactions/{id}/reversal", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", "hidden-reverse-header-key")
				.header("If-Match", "\"abc\"").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"作废\"}"))) {
			mvc.perform(request)
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.versionConflict").doesNotExist());
		}
		assertEquals(0, jdbc.queryForObject("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND idempotency_key IN (?, ?, ?, ?)
			""", Integer.class, stranger.id(), "hidden-revise-body-key", "hidden-revise-header-key",
			"hidden-reverse-body-key", "hidden-reverse-header-key"));
	}

	@Test
	void activeRolesCanReadWhileEndedAndNonMembersReceiveTheSame404() throws Exception {
		User owner = user("ledger-roles-owner");
		User editor = user("ledger-roles-editor");
		User viewer = user("ledger-roles-viewer");
		User left = user("ledger-roles-left");
		User removed = user("ledger-roles-removed");
		User ended = user("ledger-roles-ended");
		User creatorOnly = user("ledger-roles-creator");
		User stranger = user("ledger-roles-stranger");
		Account account = account(owner.id());
		membership(account.id(), editor.id(), "EDITOR", "ACTIVE", null);
		membership(account.id(), viewer.id(), "VIEWER", "ACTIVE", null);
		membership(account.id(), left.id(), "VIEWER", "LEFT", Instant.now().minus(1, ChronoUnit.DAYS));
		membership(account.id(), removed.id(), "EDITOR", "REMOVED", Instant.now().minus(1, ChronoUnit.DAYS));
		membership(account.id(), ended.id(), "VIEWER", "LEFT", Instant.now().minus(1, ChronoUnit.DAYS));
		UUID transactionId = transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z"));
		jdbc.update("UPDATE transactions SET created_by = ?, updated_by = ? WHERE id = ?", creatorOnly.id(), creatorOnly.id(), transactionId);

		for (User visible : List.of(owner, editor, viewer)) {
			String token = bearer(visible);
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(transactionId.toString()));
			mvc.perform(get("/api/v1/transactions/{id}", transactionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		}
		for (User invisible : List.of(left, removed, ended, creatorOnly, stranger)) {
			String token = bearer(invisible);
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param("accountId", account.id().toString()))
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("transactions"))));
			mvc.perform(get("/api/v1/transactions/{id}", transactionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		}
	}

	@Test
	void filtersLimitsSameDayOrderAndCursorBindingsAreValidated() throws Exception {
		User owner = user("ledger-filter-owner");
		User other = user("ledger-filter-other");
		Account first = account(owner.id());
		Account second = account(owner.id());
		Account otherAccount = account(other.id());
		UUID categoryId = category(owner.id());
		UUID sameDayLower = UUID.fromString("00000000-0000-0000-0000-000000000011");
		UUID sameDayHigher = UUID.fromString("00000000-0000-0000-0000-000000000099");
		transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-15T01:00:00Z"), sameDayLower, "EXPENSE", categoryId);
		transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-15T02:00:00Z"), sameDayHigher, "EXPENSE", categoryId);
		UUID income = transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-14T01:00:00Z"), UUID.randomUUID(), "INCOME", null);
		transaction(owner.id(), second.ledgerId(), Instant.parse("2026-08-13T01:00:00Z"));
		transaction(other.id(), otherAccount.ledgerId(), Instant.parse("2026-08-12T01:00:00Z"));
		String token = bearer(owner);

		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("accountId", first.id().toString()).param("type", "EXPENSE")
				.param("dateFrom", "2026-08-15").param("dateTo", "2026-08-15").param("categoryId", categoryId.toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].id").value(sameDayHigher.toString()))
			.andExpect(jsonPath("$.data[1].id").value(sameDayLower.toString()));
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("type", "INCOME").param("dateFrom", "2026-08-14").param("dateTo", "2026-08-14"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(income.toString()));
		for (String limit : List.of("1", "200")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", limit))
				.andExpect(status().isOk());
		}
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4));
		for (String limit : List.of("0", "201", "abc", "999999999999999999999")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", limit))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		for (String parameter : List.of("accountId", "categoryId", "dateFrom", "type")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param(parameter, "invalid")).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1").param("limit", "2")).andExpect(status().isBadRequest());

		MvcResult firstPage = mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1")).andExpect(status().isOk()).andReturn();
		String cursor = json(firstPage).at("/meta/nextCursor").asString();
		assertTrue(!cursor.isBlank());
		for (var request : List.of(
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("type", "INCOME").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("accountId", second.id().toString()).param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("dateFrom", "2026-08-14").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("categoryId", categoryId.toString()).param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(other)).param("limit", "1").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1")
				.param("cursor", cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A")))) {
			mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
	}

	private User user(String suffix) {
		UUID id = UUID.randomUUID();
		String email = suffix + "-" + id + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-hash', 1, 'Ledger', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", id, email, email, ts(now), ts(now), ts(now));
		return new User(id);
	}

	private Account account(UUID ownerId) {
		return account(ownerId, "ASSET", "BANK", "CNY");
	}

	private Account account(UUID ownerId, String accountClass, String accountType, String currency) {
		UUID accountId = UUID.randomUUID();
		UUID ledgerId = UUID.randomUUID();
		Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
		String nature = "LIABILITY".equals(accountClass) ? "LIABILITY" : "ASSET";
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
				VALUES (?, ?, ?, 'Ledger account', ?, 'ACTIVE', ?, ?, ?, 1)
				""", accountId, accountClass, accountType, currency, ownerId, ts(now), ts(now));
			UUID membershipId = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, ownerId, ts(now));
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(now), ownerId, ts(now));
			jdbc.update("""
				INSERT INTO ledger_accounts (id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', ?, ?, 'ACTIVE', ?)
				""", ledgerId, accountId, "PRIMARY_" + accountId, nature, currency, ts(now));
		});
		return new Account(accountId, ledgerId);
	}

	private void membership(UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		Instant joinedAt = Instant.now().minus(2, ChronoUnit.DAYS);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status, ts(joinedAt), endedAt == null ? null : ts(endedAt));
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(joinedAt), userId, ts(joinedAt));
		});
	}

	private UUID category(UUID ownerId) {
		return category(ownerId, "EXPENSE");
	}

	private UUID category(UUID ownerId, String categoryType) {
		UUID categoryId = UUID.randomUUID();
		Instant now = Instant.now();
		String name = categoryType + "-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, categoryType, name, name, ts(now), ts(now));
		return categoryId;
	}

	private UUID transaction(UUID ownerId, UUID visibleLedgerId, Instant businessAt) {
		return transaction(ownerId, visibleLedgerId, businessAt, UUID.randomUUID(), "EXPENSE", null);
	}

	private UUID transaction(UUID ownerId, UUID visibleLedgerId, Instant businessAt, UUID transactionId, String type, UUID categoryId) {
		UUID systemLedgerId = UUID.randomUUID();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO ledger_accounts (id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'SYSTEM', 'EXPENSE', 'CNY', 'ACTIVE', ?)
				""", systemLedgerId, ownerId, "EXPENSE_" + systemLedgerId, ts(businessAt));
			jdbc.update("""
				INSERT INTO transactions (id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
				VALUES (?, ?, 'DRAFT', ?, ?, 'UTC', 'MANUAL', ?, 1, ?, ?, ?, ?)
				""", transactionId, type, ts(businessAt), java.sql.Date.valueOf(businessAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()),
				transactionId, ownerId, ownerId, ts(businessAt), ts(businessAt));
			for (Object[] entry : List.of(new Object[] {systemLedgerId, "D"}, new Object[] {visibleLedgerId, "C"})) {
				jdbc.update("""
					INSERT INTO ledger_entries (id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
					VALUES (?, ?, ?, ?, ?, 10.00, 'CNY', ?, ?)
					""", UUID.randomUUID(), transactionId, entry[0], "D".equals(entry[1]) ? 1 : 2, entry[1],
					java.sql.Date.valueOf(businessAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()), ts(businessAt));
			}
			jdbc.update("UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
				ts(businessAt), ts(businessAt), transactionId);
			if (categoryId != null) {
				jdbc.update("INSERT INTO transaction_categories (transaction_id, category_id, role) VALUES (?, ?, 'PRIMARY')",
					transactionId, categoryId);
			}
		});
		return transactionId;
	}

	private String bearer(User user) {
		SessionTokenResult session = deviceSessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.id(), "ledger-http", "ledger-http-device"));
		return session.accessToken();
	}

	private UUID postLedgerTransaction(String token, String key, String body, String expectedType) throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.type").value(expectedType)).andReturn();
		return UUID.fromString(json(result).at("/data/id").asString());
	}

	private void assertWriteFailure(User user, String body, String key, int statusCode, String code) throws Exception {
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(user)).header("Idempotency-Key", key)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(statusCode)).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.containsString("transactions"),
					org.hamcrest.Matchers.containsString("ledger_entries")))));
	}

	private void assertPermissionRaceRollback(
		MockHttpServletRequestBuilder request,
		UUID userId,
		UUID accountId,
		String idempotencyKey,
		MembershipChange change,
		int expectedStatus,
		String expectedCode) throws Exception {
		FactCounts before = factCounts();
		PermissionRaceGate gate = permissionRace.arm();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<MvcResult> future = executor.submit(() -> mvc.perform(request).andReturn());
			try {
				assertTrue(gate.finalCheckReached().await(10, TimeUnit.SECONDS), "最终权限复核未进入竞争栅栏");
				int updated = switch (change) {
					case ENDED -> jdbc.update("""
						UPDATE account_members
						SET status = 'LEFT', ended_at = CURRENT_TIMESTAMP, version = version + 1
						WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
						""", accountId, userId);
					case REMOVED -> jdbc.update("""
						UPDATE account_members
						SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP, version = version + 1
						WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
						""", accountId, userId);
					case VIEWER -> jdbc.update("""
						UPDATE account_members
						SET role = 'VIEWER', version = version + 1
						WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
						""", accountId, userId);
				};
				assertEquals(1, updated);
			} finally {
				gate.membershipChanged().countDown();
			}
			MvcResult result = future.get(10, TimeUnit.SECONDS);
			assertEquals(expectedStatus, result.getResponse().getStatus());
			assertEquals(expectedCode, json(result).at("/code").asString());
			assertEquals(null, result.getResponse().getHeader(HttpHeaders.ETAG));
		} finally {
			permissionRace.disarm(gate);
			gate.membershipChanged().countDown();
		}
		assertEquals(before, factCounts());
		assertEquals(0, jdbc.queryForObject(
			"SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", Integer.class, idempotencyKey));
	}

	private FactCounts factCounts() {
		return new FactCounts(
			jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class),
			jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class),
			jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class),
			jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class),
			jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Integer.class));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PermissionRaceConfiguration {

		@Bean
		@Primary
		PermissionRacePostingAccessPort permissionRacePostingAccessPort(
			PostgresAccountPostingAccessPort delegate) {
			return new PermissionRacePostingAccessPort(delegate);
		}
	}

	static final class PermissionRacePostingAccessPort implements AccountPostingAccessPort {

		private final AccountPostingAccessPort delegate;
		private final AtomicReference<PermissionRaceGate> armed = new AtomicReference<>();

		private PermissionRacePostingAccessPort(AccountPostingAccessPort delegate) {
			this.delegate = delegate;
		}

		PermissionRaceGate arm() {
			PermissionRaceGate gate = new PermissionRaceGate(new CountDownLatch(1), new CountDownLatch(1));
			if (!armed.compareAndSet(null, gate)) {
				throw new IllegalStateException("权限竞争栅栏已启用。");
			}
			return gate;
		}

		void disarm(PermissionRaceGate gate) {
			armed.compareAndSet(gate, null);
		}

		@Override
		public boolean mayPost(UUID userId, UUID accountId) {
			return delegate.mayPost(userId, accountId);
		}

		@Override
		public PostingAccessDecision postingDecision(UUID userId, UUID accountId) {
			PermissionRaceGate gate = armed.getAndSet(null);
			if (gate != null) {
				// 只暂停最终 application 复核，preflight 的授权检查已在此前完成。
				gate.finalCheckReached().countDown();
				try {
					if (!gate.membershipChanged().await(10, TimeUnit.SECONDS)) {
						throw new AssertionError("成员变更未释放权限竞争栅栏");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("权限竞争测试线程被中断", exception);
				}
			}
			return delegate.postingDecision(userId, accountId);
		}
	}

	private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
	private java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
	private enum MembershipChange { ENDED, REMOVED, VIEWER }
	private record PermissionRaceGate(CountDownLatch finalCheckReached, CountDownLatch membershipChanged) {}
	private record FactCounts(int transactions, int entries, int audits, int outbox, int idempotency) {}
	private record User(UUID id) {}
	private record Account(UUID id, UUID ledgerId) {}
	private record FailureUser(User user, String key) {}
}
