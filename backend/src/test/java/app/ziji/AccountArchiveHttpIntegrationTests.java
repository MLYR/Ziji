package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** BE-ACC-005 的真实 Security/PostgreSQL 归档、历史保留、幂等和账务锁边界验收。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountArchiveHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private TransactionRunner transactions;

	@Test
	void ownerArchivesZeroBalanceAndKeepsAccountHistoryWithoutLedgerFacts() throws Exception {
		UserFixture owner = insertUser("archive-zero-owner");
		UserFixture formerEditor = insertUser("archive-zero-former-editor");
		AccountSeed account = seedAccount(owner.userId(), "零余额归档");
		addMembership(account.accountId(), formerEditor.userId(), "EDITOR", "LEFT", Instant.now());
		FactCounts before = factCounts(account.accountId());
		int membershipRowsBefore = count("SELECT count(*) FROM account_members WHERE account_id = ?", account.accountId());

		archive(owner, account, "archive-zero-key-0001", false)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"))
			.andExpect(jsonPath("$.data.version").value(2));

		assertEquals("ARCHIVED", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(2, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
		assertNotNull(jdbc.queryForObject(
			"SELECT archived_at FROM accounts WHERE id = ?", java.sql.Timestamp.class, account.accountId()));
		FactCounts after = factCounts(account.accountId());
		assertEquals(before.transactions(), after.transactions());
		assertEquals(before.entries(), after.entries());
		assertEquals(before.outboxEvents(), after.outboxEvents());
		assertEquals(before.auditLogs() + 1, after.auditLogs());
		assertEquals(membershipRowsBefore, count(
			"SELECT count(*) FROM account_members WHERE account_id = ?", account.accountId()));
		assertEquals(1, count("SELECT count(*) FROM account_members "
			+ "WHERE account_id = ? AND user_id = ? AND role = 'EDITOR' AND status = 'LEFT'",
			account.accountId(), formerEditor.userId()));
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_ARCHIVED' AND resource_id = ?",
			account.accountId()));
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE account_id = ? AND status = 'ACTIVE'",
			account.accountId()));

		mvc.perform(get("/api/v1/accounts/{id}", account.accountId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"));
		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(get("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner))
				.param("accountId", account.accountId().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));

		archive(owner, account, "archive-zero-key-0002", false)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_ARCHIVED"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());
		assertEquals(2, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
	}

	@Test
	void nonZeroArchiveRequiresConfirmationAndRetainsRealLedgerHistory() throws Exception {
		UserFixture owner = insertUser("archive-nonzero-owner");
		CreatedAccount created = createAccountWithOpening(owner, "archive-opening-key-0001");
		FactCounts beforeArchive = factCounts(created.accountId());
		String rejectedKey = "archive-nonzero-key-0001";

		MvcResult rejected = archive(owner, created.accountId(), rejectedKey, false)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"))
			.andExpect(jsonPath("$.detail").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"))
			.andReturn();
		String rejectedBody = rejected.getResponse().getContentAsString();
		assertFalse(rejectedBody.contains("12.34"));
		assertFalse(rejectedBody.contains("CNY"));
		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, created.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, created.accountId()));
		assertEquals("FAILED_FINAL", jdbc.queryForObject("""
			SELECT status FROM idempotency_records
			WHERE user_id = ? AND operation_id = 'archiveAccount' AND idempotency_key = ?
			""", String.class, owner.userId(), rejectedKey));
		assertEquals(beforeArchive, factCounts(created.accountId()));

		MvcResult replay = archive(owner, created.accountId(), rejectedKey, false)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"))
			.andReturn();
		assertFalse(replay.getResponse().getContentAsString().contains("12.34"));
		assertEquals(beforeArchive, factCounts(created.accountId()));
		archive(owner, created.accountId(), rejectedKey, true)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, created.accountId()));

		String confirmedKey = "archive-nonzero-key-0002";
		archive(owner, created.accountId(), confirmedKey, true)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"));
		FactCounts afterArchive = factCounts(created.accountId());
		assertEquals(beforeArchive.transactions(), afterArchive.transactions());
		assertEquals(beforeArchive.entries(), afterArchive.entries());
		assertEquals(beforeArchive.outboxEvents(), afterArchive.outboxEvents());
		assertEquals(beforeArchive.auditLogs() + 1, afterArchive.auditLogs());
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE account_id = ? AND status = 'ACTIVE'",
			created.accountId()));
		String metadata = jdbc.queryForObject("""
			SELECT metadata::text FROM audit_logs
			WHERE action = 'ACCOUNT_ARCHIVED' AND resource_id = ?
			""", String.class, created.accountId());
		assertFalse(metadata.contains("12.34"));
		assertFalse(metadata.contains("CNY"));

		mvc.perform(get("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner))
				.param("accountId", created.accountId().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value(created.openingTransactionId().toString()));
		assertEquals("SUCCEEDED", jdbc.queryForObject("""
			SELECT status FROM idempotency_records
			WHERE user_id = ? AND operation_id = 'archiveAccount' AND idempotency_key = ?
			""", String.class, owner.userId(), confirmedKey));
		archive(owner, created.accountId(), confirmedKey, true)
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"));
		assertEquals(afterArchive, factCounts(created.accountId()));
		jdbc.update("""
			UPDATE idempotency_records
			SET response_reference = jsonb_build_object(
				'kind', 'RESOURCE', 'location', ?, 'etag', '"2"', 'resourceVersion', 2)
			WHERE user_id = ? AND operation_id = 'archiveAccount' AND idempotency_key = ?
			""", "/api/v1/accounts/00000000-0000-0000-0000-000000000999", owner.userId(), confirmedKey);
		archive(owner, created.accountId(), confirmedKey, true)
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertEquals(afterArchive, factCounts(created.accountId()));
	}

	@Test
	void sameHttpCreatedAccountKeepsLifecycleVersionAndHistoryAcrossUpdateConflictAndArchive() throws Exception {
		// 同一账户跨越创建、资料更新、冲突和归档，验证版本与事实历史连续。
		UserFixture owner = insertUser("archive-lifecycle-owner");
		CreatedAccount created = createAccountWithOpening(owner, "archive-lifecycle-create-0001");
		assertNotNull(created.accountId());
		assertNotNull(created.openingTransactionId());
		UUID accountId = created.accountId();
		String resource = "/api/v1/accounts/" + accountId;
		String token = bearer(owner);

		assertEquals(1, count("SELECT count(*) FROM account_members "
			+ "WHERE account_id = ? AND user_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
			accountId, owner.userId()));
		assertEquals(1, count("""
			SELECT count(*) FROM account_inclusion_settings s
			JOIN account_members m ON m.id = s.membership_id
			WHERE m.account_id = ? AND m.user_id = ? AND s.included = TRUE
			  AND s.ratio = 1.000000 AND s.valid_to IS NULL
			""", accountId, owner.userId()));
		assertEquals(1, count("""
			SELECT count(*) FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'PRIMARY'
			  AND account_nature = 'ASSET' AND currency = 'CNY' AND status = 'ACTIVE'
			""", accountId));
		assertEquals(1, count("""
			SELECT count(*) FROM transactions
			WHERE id = ? AND transaction_type = 'OPENING' AND status = 'POSTED'
			""", created.openingTransactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
			created.openingTransactionId()));

		mvc.perform(get(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(accountId.toString()))
			.andExpect(jsonPath("$.data.name").value("非零归档账户"))
			.andExpect(jsonPath("$.data.accountClass").value("ASSET"))
			.andExpect(jsonPath("$.data.accountType").value("BANK"))
			.andExpect(jsonPath("$.data.currency").value("CNY"))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.version").value(1));

		String updatedName = "生命周期更新后的账户";
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"" + updatedName + "\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.name").value(updatedName))
			.andExpect(jsonPath("$.data.version").value(2));

		mvc.perform(get(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.name").value(updatedName))
			.andExpect(jsonPath("$.data.version").value(2));

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"过期版本不应写入\"}"))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(resource))
			.andExpect(jsonPath("$.data").doesNotExist());
		assertEquals(updatedName, jdbc.queryForObject(
			"SELECT name FROM accounts WHERE id = ?", String.class, accountId));
		assertEquals(2, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, accountId));

		FactCounts beforeArchive = factCounts(accountId);
		archiveWithToken(token, accountId, "archive-lifecycle-archive-0001", archiveBody(true), "\"2\"")
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"))
			.andExpect(jsonPath("$.data.version").value(3));

		assertEquals("ARCHIVED", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, accountId));
		assertEquals(3, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, accountId));
		assertNotNull(jdbc.queryForObject(
			"SELECT archived_at FROM accounts WHERE id = ?", java.sql.Timestamp.class, accountId));
		FactCounts afterArchive = factCounts(accountId);
		assertEquals(beforeArchive.transactions(), afterArchive.transactions());
		assertEquals(beforeArchive.entries(), afterArchive.entries());
		assertEquals(beforeArchive.outboxEvents(), afterArchive.outboxEvents());
		assertEquals(beforeArchive.auditLogs() + 1, afterArchive.auditLogs());

		mvc.perform(get(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
			.andExpect(jsonPath("$.data.id").value(accountId.toString()))
			.andExpect(jsonPath("$.data.name").value(updatedName))
			.andExpect(jsonPath("$.data.status").value("ARCHIVED"))
			.andExpect(jsonPath("$.data.version").value(3));
		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
		mvc.perform(get("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("accountId", accountId.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value(created.openingTransactionId().toString()));

		assertEquals(1, count("""
			SELECT count(*) FROM transactions
			WHERE id = ? AND transaction_type = 'OPENING' AND status = 'POSTED'
			""", created.openingTransactionId()));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
			created.openingTransactionId()));
		assertEquals(1, count("SELECT count(*) FROM account_members "
			+ "WHERE account_id = ? AND user_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
			accountId, owner.userId()));
		assertEquals(1, count("""
			SELECT count(*) FROM account_inclusion_settings s
			JOIN account_members m ON m.id = s.membership_id
			WHERE m.account_id = ? AND m.user_id = ? AND s.included = TRUE
			  AND s.ratio = 1.000000 AND s.valid_to IS NULL
			""", accountId, owner.userId()));
	}

	@Test
	void onlyCurrentOwnerCanArchiveAndIneligibleUsersDoNotCreateIdempotencyRecords() throws Exception {
		UserFixture owner = insertUser("archive-permission-owner");
		UserFixture editor = insertUser("archive-permission-editor");
		UserFixture viewer = insertUser("archive-permission-viewer");
		UserFixture left = insertUser("archive-permission-left");
		UserFixture removed = insertUser("archive-permission-removed");
		UserFixture creatorOnly = insertUser("archive-permission-creator");
		UserFixture stranger = insertUser("archive-permission-stranger");
		AccountSeed account = seedAccount(owner.userId(), "归档权限");
		addMembership(account.accountId(), editor.userId(), "EDITOR", "ACTIVE", null);
		addMembership(account.accountId(), viewer.userId(), "VIEWER", "ACTIVE", null);
		addMembership(account.accountId(), left.userId(), "VIEWER", "LEFT", Instant.now());
		addMembership(account.accountId(), removed.userId(), "EDITOR", "REMOVED", Instant.now());
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", creatorOnly.userId(), account.accountId());

		assertArchiveFailure(editor, account, "archive-permission-editor-01", 403, "PERMISSION_DENIED");
		assertArchiveFailure(viewer, account, "archive-permission-viewer-01", 403, "PERMISSION_DENIED");
		assertArchiveFailure(left, account, "archive-permission-left-01", 404, "RESOURCE_NOT_FOUND");
		assertArchiveFailure(removed, account, "archive-permission-removed-01", 404, "RESOURCE_NOT_FOUND");
		assertArchiveFailure(creatorOnly, account, "archive-permission-creator-01", 404, "RESOURCE_NOT_FOUND");
		assertArchiveFailure(stranger, account, "archive-permission-stranger-01", 404, "RESOURCE_NOT_FOUND");

		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));

	}

	@Test
	void strictIfMatchErrorsAreRejectedBeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("archive-if-match-owner");
		AccountSeed account = seedAccount(owner.userId(), "严格归档版本");
		String body = archiveBody(false);
		String[][] cases = {
			{"archive-if-match-missing-01"},
			{"archive-if-match-weak-01", "W/\"1\""},
			{"archive-if-match-star-01", "*"},
			{"archive-if-match-plain-01", "1"},
			{"archive-if-match-zero-01", "\"0\""},
			{"archive-if-match-negative-01", "\"-1\""},
			{"archive-if-match-text-01", "\"abc\""},
			{"archive-if-match-overflow-01", "\"2147483648\""},
			{"archive-if-match-duplicate-01", "\"1\"", "\"1\""}
		};

		for (String[] testCase : cases) {
			String key = testCase[0];
			String[] ifMatch = java.util.Arrays.copyOfRange(testCase, 1, testCase.length);
			archive(owner, account.accountId(), key, body, ifMatch)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			assertEquals(0, count("""
				SELECT count(*) FROM idempotency_records
				WHERE user_id = ? AND operation_id = 'archiveAccount' AND idempotency_key = ?
				""", owner.userId(), key));
		}
		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));

		jdbc.update("UPDATE accounts SET version = 2, updated_at = CURRENT_TIMESTAMP WHERE id = ?", account.accountId());
		String key = "archive-if-match-stale-01";
		MvcResult conflict = archive(owner, account.accountId(), key, body, "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(
				"/api/v1/accounts/" + account.accountId()))
			.andReturn();
		JsonNode conflictSummary = objectMapper.readTree(conflict.getResponse().getContentAsString())
			.at("/versionConflict");
		MvcResult replay = archive(owner, account.accountId(), key, body, "\"1\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andReturn();
		assertEquals(conflictSummary.toString(), objectMapper.readTree(replay.getResponse().getContentAsString())
			.at("/versionConflict").toString());
		jdbc.update("""
			UPDATE idempotency_records
			SET response_reference = jsonb_build_object(
				'kind', 'VERSION_CONFLICT', 'errorCode', 'VERSION_CONFLICT',
				'currentVersion', 2, 'currentEtag', '"2"', 'resourceLocation', ?)
			WHERE operation_id = 'archiveAccount' AND idempotency_key = ?
			""", "/api/v1/accounts/00000000-0000-0000-0000-000000000999", key);
		archive(owner, account.accountId(), key, body, "\"1\"")
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.versionConflict").doesNotExist());
		assertEquals("FAILED_FINAL", jdbc.queryForObject("""
			SELECT status FROM idempotency_records
			WHERE operation_id = 'archiveAccount' AND idempotency_key = ?
			""", String.class, key));
	}

	@Test
	void archiveAndLedgerWriteSerializeOnTheAccountRow() throws Exception {
		UserFixture owner = insertUser("archive-ledger-race-owner");
		AccountSeed account = seedAccount(owner.userId(), "归档账务竞态");
		UUID incomeCategory = insertCategory(owner.userId(), "INCOME");
		String token = bearer(owner);
		// 两个并发请求复用同一个设备会话，避免第二次创建会话撤销第一枚 Bearer token。
		String archiveToken = token;
		String archiveKey = "archive-ledger-race-archive";
		String ledgerKey = "archive-ledger-race-ledger";
		String ledgerBody = """
			{"type":"INCOME","businessAt":"2026-08-21T05:06:07Z","timezone":"UTC",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.accountId(), incomeCategory);
		FactCounts before = factCounts(account.accountId());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<MvcResult> archive = executor.submit(() -> {
				start.await();
				return archiveWithToken(archiveToken, account.accountId(), archiveKey, archiveBody(true), "\"1\"")
					.andReturn();
			});
			Future<MvcResult> ledger = executor.submit(() -> {
				start.await();
				return mvc.perform(post("/api/v1/transactions")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.header("Idempotency-Key", ledgerKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(ledgerBody))
					.andReturn();
			});
			start.countDown();
			MvcResult archiveResult = archive.get(15, TimeUnit.SECONDS);
			MvcResult ledgerResult = ledger.get(15, TimeUnit.SECONDS);
			assertEquals(200, archiveResult.getResponse().getStatus());
			int ledgerStatus = ledgerResult.getResponse().getStatus();
			assertTrue(ledgerStatus == 201 || ledgerStatus == 422 || ledgerStatus == 404,
				"Ledger 竞态结果必须是成功或归档后的业务/可见性拒绝，实际=" + ledgerStatus);

			if (ledgerStatus == 201) {
				JsonNode ledgerResponse = objectMapper.readTree(ledgerResult.getResponse().getContentAsString());
				assertTrue(ledgerResponse.at("/data/id").isTextual(), "成功账务响应必须包含交易 ID");
				UUID ledgerTransactionId = UUID.fromString(ledgerResponse.at("/data/id").asText());
				assertEquals("SUCCEEDED", jdbc.queryForObject("""
					SELECT status FROM idempotency_records
					WHERE user_id = ? AND operation_id = 'postTransaction' AND idempotency_key = ?
					""", String.class, owner.userId(), ledgerKey));
				// 账户侧统计只包含可见 PRIMARY 分录；再按交易 ID 固定验证收入仍产生完整复式分录。
				assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", ledgerTransactionId));
			} else {
				JsonNode ledgerProblem = objectMapper.readTree(ledgerResult.getResponse().getContentAsString());
				String code = ledgerProblem.at("/code").asText();
				assertTrue(Set.of("BUSINESS_RULE_VIOLATION", "RESOURCE_NOT_FOUND").contains(code),
					"Ledger 拒绝必须来自归档边界，实际 code=" + code);
				assertEquals(0, count("SELECT count(*) FROM transactions WHERE idempotency_record_id IN "
					+ "(SELECT id FROM idempotency_records WHERE idempotency_key = ?)", ledgerKey));
			}
			FactCounts after = factCounts(account.accountId());
			assertEquals(before.auditLogs() + 1, after.auditLogs());
			if (ledgerStatus == 201) {
				assertEquals(before.transactions() + 1, after.transactions());
				assertEquals(before.entries() + 1, after.entries());
				assertEquals(before.outboxEvents() + 1, after.outboxEvents());
			} else {
				assertEquals(before.transactions(), after.transactions());
				assertEquals(before.entries(), after.entries());
				assertEquals(before.outboxEvents(), after.outboxEvents());
			}
			assertEquals("ARCHIVED", jdbc.queryForObject(
				"SELECT status FROM accounts WHERE id = ?", String.class, account.accountId()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void archiveLockBoundaryIsObservableBeforeLedgerCanProceed() throws Exception {
		UserFixture owner = insertUser("archive-lock-barrier-owner");
		AccountSeed account = seedAccount(owner.userId(), "归档锁栅栏");
		UUID incomeCategory = insertCategory(owner.userId(), "INCOME");
		String token = bearer(owner);
		String archiveKey = "archive-lock-barrier-archive";
		String ledgerKey = "archive-lock-barrier-ledger";
		String ledgerBody = """
			{"type":"INCOME","businessAt":"2026-08-21T05:06:07Z","timezone":"UTC",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s"}
			""".formatted(account.accountId(), incomeCategory);
		FactCounts before = factCounts(account.accountId());
		CountDownLatch holderLocked = new CountDownLatch(1);
		CountDownLatch releaseHolder = new CountDownLatch(1);
		AtomicInteger holderPid = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(3);
		try {
			Future<?> holder = executor.submit(() -> transactions.required(() -> {
				holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				jdbc.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", UUID.class, account.accountId());
				holderLocked.countDown();
				awaitLatch(releaseHolder, "归档锁栅栏释放超时");
			}));
			assertTrue(holderLocked.await(10, TimeUnit.SECONDS), "测试事务未取得账户行锁");

			Future<MvcResult> archive = executor.submit(() ->
				archiveWithToken(token, account.accountId(), archiveKey, archiveBody(true), "\"1\"").andReturn());
			awaitAccountRowLockWaiters(holderPid.get(), 1);
			// 提交 Ledger 请求；真正的业务结果在释放 holder 后验证，不把 Future 提交当成已进入账户行锁。
			Future<MvcResult> ledger = executor.submit(() -> mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", ledgerKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(ledgerBody))
				.andReturn());
			// holder 已证明归档请求确实等待账户行锁；释放后验证 Ledger 必须重新读取 ARCHIVED，避免把瞬时等待队列宽度当成业务契约。
			releaseHolder.countDown();
			MvcResult archiveResult = archive.get(15, TimeUnit.SECONDS);
			assertEquals(200, archiveResult.getResponse().getStatus());
			holder.get(15, TimeUnit.SECONDS);

			MvcResult ledgerResult = ledger.get(15, TimeUnit.SECONDS);
			assertTrue(ledgerResult.getResponse().getStatus() == 422 || ledgerResult.getResponse().getStatus() == 404,
				"归档提交后 Ledger 必须拒绝，实际=" + ledgerResult.getResponse().getStatus());
			String code = objectMapper.readTree(ledgerResult.getResponse().getContentAsString()).at("/code").asText();
			assertTrue(Set.of("BUSINESS_RULE_VIOLATION", "RESOURCE_NOT_FOUND").contains(code),
				"归档提交后的 Ledger 拒绝 code 无效：" + code);
			FactCounts after = factCounts(account.accountId());
			assertEquals(before.transactions(), after.transactions());
			assertEquals(before.entries(), after.entries());
			assertEquals(before.auditLogs() + 1, after.auditLogs());
			assertEquals(before.outboxEvents(), after.outboxEvents());
			assertEquals("ARCHIVED", jdbc.queryForObject(
				"SELECT status FROM accounts WHERE id = ?", String.class, account.accountId()));
		} finally {
			releaseHolder.countDown();
			executor.shutdownNow();
		}
	}

	private ResultActions archive(UserFixture user, AccountSeed account, String key, boolean confirm) throws Exception {
		return archive(user, account.accountId(), key, archiveBody(confirm), "\"1\"");
	}

	private ResultActions archive(UserFixture user, UUID accountId, String key, boolean confirm) throws Exception {
		return archive(user, accountId, key, archiveBody(confirm), "\"1\"");
	}

	private ResultActions archive(
		UserFixture user, UUID accountId, String key, String body, String... ifMatchValues) throws Exception {
		return archiveWithToken(bearer(user), accountId, key, body, ifMatchValues);
	}

	private ResultActions archiveWithToken(
		String token, UUID accountId, String key, String body, String... ifMatchValues) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/accounts/{id}/archive", accountId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		for (String value : ifMatchValues) {
			request.header("If-Match", value);
		}
		return mvc.perform(request);
	}

	private void assertArchiveFailure(
		UserFixture user, AccountSeed account, String key, int expectedStatus, String expectedCode) throws Exception {
		archive(user, account, key, true)
			.andExpect(status().is(expectedStatus))
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value(expectedCode));
		assertEquals(0, count("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", key));
	}

	private CreatedAccount createAccountWithOpening(UserFixture user, String key) throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(user))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"accountClass":"ASSET","accountType":"BANK","name":"非零归档账户","currency":"CNY",
					 "openingBalance":{"amount":"12.34","businessAt":"2026-08-21T05:06:07Z"}}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.account.id").isNotEmpty())
			.andExpect(jsonPath("$.data.account.accountClass").value("ASSET"))
			.andExpect(jsonPath("$.data.account.accountType").value("BANK"))
			.andExpect(jsonPath("$.data.account.currency").value("CNY"))
			.andExpect(jsonPath("$.data.account.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.account.currentUserRole").value("OWNER"))
			.andExpect(jsonPath("$.data.account.inclusionRatio").value("1.000000"))
			.andExpect(jsonPath("$.data.account.version").value(1))
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return new CreatedAccount(
			UUID.fromString(body.at("/data/account/id").asString()),
			UUID.fromString(body.at("/data/openingTransactionId").asString()));
	}

	private AccountSeed seedAccount(UUID ownerId, String name) {
		UUID accountId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, institution, currency, note, status,
					 archived_at, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, '归档测试机构', 'CNY', NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, name, ownerId, ts(now), ts(now));
			UUID membershipId = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, ownerId, ts(now));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(now), ownerId, ts(now));
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", UUID.randomUUID(), accountId, "ARCHIVE_PRIMARY_" + accountId, ts(now));
		});
		return new AccountSeed(accountId, ownerId);
	}

	private void addMembership(
		UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		Instant joinedAt = Instant.now().minusSeconds(3600);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status, ts(joinedAt),
				endedAt == null ? null : ts(endedAt));
			if ("ACTIVE".equals(status)) {
				jdbc.update("""
					INSERT INTO account_inclusion_settings
						(id, membership_id, included, ratio, valid_from, created_by, created_at)
					VALUES (?, ?, TRUE, 0.500000, ?, ?, ?)
					""", UUID.randomUUID(), membershipId, ts(joinedAt), userId, ts(joinedAt));
			}
		});
	}

	private UUID insertCategory(UUID ownerId, String categoryType) {
		UUID categoryId = UUID.randomUUID();
		Instant now = Instant.now();
		String name = "归档竞态分类-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, categoryType, name, name, ts(now), ts(now));
		return categoryId;
	}

	private FactCounts factCounts(UUID accountId) {
		return new FactCounts(
			count("""
				SELECT count(DISTINCT t.id) FROM transactions t
				JOIN ledger_entries e ON e.transaction_id = t.id
				JOIN ledger_accounts la ON la.id = e.ledger_account_id
				WHERE la.visible_account_id = ?
				""", accountId),
			count("""
				SELECT count(*) FROM ledger_entries e
				JOIN ledger_accounts la ON la.id = e.ledger_account_id
				WHERE la.visible_account_id = ?
				""", accountId),
			count("SELECT count(*) FROM audit_logs WHERE resource_type = 'ACCOUNT' AND resource_id = ?", accountId),
			count("""
				SELECT count(*) FROM outbox_events
				WHERE aggregate_id = ? OR aggregate_id IN (
					SELECT DISTINCT t.id FROM transactions t
					JOIN ledger_entries e ON e.transaction_id = t.id
					JOIN ledger_accounts la ON la.id = e.ledger_account_id
					WHERE la.visible_account_id = ?)
				""", accountId, accountId));
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "archive-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '账户归档 HTTP', 'Asia/Shanghai', 'CNY',
				'zh-CN', 'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "account-archive-http", "account-archive-" + user.userId()));
		return session.accessToken();
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private void awaitAccountRowLockWaiters(int holderPid, int expectedWaiters) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (accountRowLockWaiterCount(holderPid) >= expectedWaiters) {
				return;
			}
			// 让出测试线程给请求工作线程进入真实 FOR UPDATE 等待；仍由 deadline 控制，不依赖固定 sleep 时长。
			Thread.yield();
		}
		throw new AssertionError("未在 PostgreSQL 中观察到预期账户行锁等待数：" + expectedWaiters);
	}

	private int accountRowLockWaiterCount(int holderPid) {
		return count("""
			SELECT count(*) FROM pg_stat_activity waiting
			WHERE waiting.wait_event_type = 'Lock'
			  AND waiting.query ILIKE '%FROM accounts%'
			  AND waiting.query ILIKE '%FOR UPDATE%'
			  AND ? = ANY(pg_blocking_pids(waiting.pid))
			""", holderPid);
	}

	private static void awaitLatch(CountDownLatch latch, String message) {
		try {
			if (!latch.await(15, TimeUnit.SECONDS)) {
				throw new AssertionError(message);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(message, exception);
		}
	}

	private java.sql.Timestamp ts(Instant instant) {
		return java.sql.Timestamp.from(instant);
	}

	private static String archiveBody(boolean confirm) {
		return "{\"reason\":\"账户已完成清理\",\"confirmNonZeroBalance\":" + confirm + "}";
	}

	private record UserFixture(UUID userId) {}

	private record AccountSeed(UUID accountId, UUID ownerId) {}

	private record CreatedAccount(UUID accountId, UUID openingTransactionId) {}

	private record FactCounts(int transactions, int entries, int auditLogs, int outboxEvents) {}
}
