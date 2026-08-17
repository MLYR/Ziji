package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 SecurityFilterChain、PostgreSQL 与统一幂等验收 createAccount。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountCreationHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private AccountLedgerInitializationPort ledgerInitialization;

	@Test
	void unauthenticatedCreateReturns401() throws Exception {
		mvc.perform(post("/api/v1/accounts")
				.header("Idempotency-Key", "account-create-unauthenticated-01")
				.contentType(MediaType.APPLICATION_JSON)
				.content(assetBody("未认证账户")))
			.andExpect(status().isUnauthorized())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void bearerCreateNeedsNoWebCsrfAndUnknownRouteRemainsDenied() throws Exception {
		UserFixture user = insertUser("security-boundary");
		String token = bearer(user);

		performCreate(user, token, "account-security-create-01", assetBody("Bearer 无 CSRF"));
		mvc.perform(post("/api/v1/accounts/unknown")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG));
	}

	@Test
	void clientAccountIdIsRejectedBeforeIdempotency() throws Exception {
		UserFixture user = insertUser("client-id");
		String key = "account-client-id-rejected-01";
		String body = """
			{"id":"%s","accountClass":"ASSET","accountType":"BANK","name":"客户端 ID","currency":"CNY"}
			""".formatted(UUID.randomUUID());

		mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(user))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(user.userId(), key));
		assertEquals(0, count("SELECT count(*) FROM accounts WHERE created_by = ?", user.userId()));
	}

	@Test
	void createsAssetInvestmentLiabilityAndAbsentOpeningWithAllRealFacts() throws Exception {
		UserFixture user = insertUser("three-classes");
		String token = bearer(user);
		CreatedResponse asset = performCreate(user, token, "account-create-asset-001", """
			{"accountClass":"ASSET","accountType":"BANK","name":"工资卡","currency":"CNY",
			 "openingBalance":{"amount":"100.00","businessAt":"2026-08-14T16:30:00Z","note":"期初现金"}}
			""");
		CreatedResponse investment = performCreate(user, token, "account-create-investment-01", """
			{"accountClass":"INVESTMENT","accountType":"FUND","name":"基金账户","currency":"JPY",
			 "openingBalance":{"amount":"1200","businessAt":"2026-08-15T15:30:00Z"}}
			""");
		CreatedResponse liability = performCreate(user, token, "account-create-liability-001", """
			{"accountClass":"LIABILITY","accountType":"CREDIT_CARD","name":"信用卡","currency":"USD",
			 "openingBalance":{"amount":"88.50","businessAt":"2026-08-14T23:30:00Z"}}
			""");
		CreatedResponse withoutOpening = performCreate(user, token, "account-create-no-opening-01", """
			{"accountClass":"ASSET","accountType":"CASH","name":"日元现金","currency":"JPY","openingBalance":null}
			""");

		assertCreatedFacts(asset, user.userId(), "ASSET", "CNY", 1,
			"D", "C", "100.00", "2026-08-15", "Asia/Shanghai");
		assertCreatedFacts(investment, user.userId(), "INVESTMENT", "JPY", 2,
			"D", "C", "1200", "2026-08-15", "Asia/Shanghai");
		assertCreatedFacts(liability, user.userId(), "LIABILITY", "USD", 1,
			"C", "D", "88.50", "2026-08-15", "Asia/Shanghai");
		assertNull(withoutOpening.openingTransactionId());
		assertBaseAccountFacts(withoutOpening.accountId(), user.userId(), "ASSET", "JPY", 1);
		assertEquals(0, openingCount(withoutOpening.accountId()));
	}

	@Test
	void sameKeyReplaysExactFirstResponseAndDifferentHashConflictsWithoutDuplicateFacts() throws Exception {
		UserFixture user = insertUser("idempotent");
		String token = bearer(user);
		String key = "account-create-idempotent-01";
		String body = """
			{"accountClass":"ASSET","accountType":"BANK","name":"幂等账户","currency":"CNY",
			 "openingBalance":{"amount":"9.99","businessAt":"2026-08-17T01:02:03Z"}}
			""";

		CreatedResponse first = performCreate(user, token, key, body);
		FactCounts firstCounts = factCounts(first.accountId(), first.openingTransactionId());
		CreatedResponse replay = performCreate(user, token, key, body);

		assertEquals(first.accountId(), replay.accountId());
		assertEquals(first.openingTransactionId(), replay.openingTransactionId());
		assertEquals(first.etag(), replay.etag());
		assertEquals(firstCounts, factCounts(first.accountId(), first.openingTransactionId()));
		assertEquals(1, idempotencyCount(user.userId(), key));
		assertEquals("SUCCEEDED", jdbc.queryForObject("""
			SELECT status FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'createAccount' AND idempotency_key = ?
			""", String.class, user.userId(), key));

		mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.replace("幂等账户", "异参账户")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertEquals(firstCounts, factCounts(first.accountId(), first.openingTransactionId()));
		assertEquals(1, idempotencyCount(user.userId(), key));
	}

	@Test
	void invalidPayloadsFailBeforeIdempotencyAndBusinessWrites() throws Exception {
		UserFixture user = insertUser("invalid");
		String token = bearer(user);
		List<String> invalidBodies = List.of(
			"{\"accountClass\":\"ASSET\",\"accountType\":\"BANK\",\"name\":\"未知字段\",\"currency\":\"CNY\",\"unknown\":true}",
			"{\"accountClass\":\"WRONG\",\"accountType\":\"BANK\",\"name\":\"非法大类\",\"currency\":\"CNY\"}",
			"{\"accountClass\":\"ASSET\",\"accountType\":\"FUND\",\"name\":\"非法组合\",\"currency\":\"CNY\"}",
			"{\"accountClass\":\"LIABILITY\",\"accountType\":\"CREDIT_CARD\",\"name\":\"额度\",\"currency\":\"CNY\",\"creditLimit\":\"1000.00\"}",
			openingBody("0", "CNY"),
			openingBody("-1.00", "CNY"),
			openingBody("1.001", "CNY"),
			openingBody("1.1", "JPY"));

		for (int index = 0; index < invalidBodies.size(); index++) {
			String key = "account-invalid-payload-" + index + "-0001";
			mvc.perform(post("/api/v1/accounts")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("Idempotency-Key", key)
					.contentType(MediaType.APPLICATION_JSON)
					.content(invalidBodies.get(index)))
				.andExpect(status().isBadRequest())
				.andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			assertEquals(0, idempotencyCount(user.userId(), key));
		}
		assertEquals(0, count("SELECT count(*) FROM accounts WHERE created_by = ?", user.userId()));
	}

	@Test
	void replayFailsClosedWhenMembershipOrInclusionNoLongerMatchesFirstResponse() throws Exception {
		for (String mutation : List.of("ROLE", "REMOVED", "INCLUDED", "RATIO")) {
			UserFixture user = insertUser("replay-" + mutation.toLowerCase());
			String token = bearer(user);
			String key = "account-replay-" + mutation.toLowerCase() + "-0001";
			String body = assetBody("重放表示-" + mutation);
			CreatedResponse created = performCreate(user, token, key, body);
			mutateInitialRepresentation(created.accountId(), user.userId(), mutation);
			FactCounts afterMutation = factCounts(created.accountId(), null);

			assertInternalErrorReplay(token, key, body);
			assertEquals(afterMutation, factCounts(created.accountId(), null));
			assertEquals(1, idempotencyCount(user.userId(), key));
		}
	}

	@Test
	void openingReplayIsNullWhenAbsentAndFailsClosedWhenMissingOrAmbiguous() throws Exception {
		UserFixture user = insertUser("opening-replay");
		String token = bearer(user);

		String absentKey = "account-opening-absent-replay-01";
		String absentBody = assetBody("无期初重放");
		CreatedResponse absent = performCreate(user, token, absentKey, absentBody);
		CreatedResponse absentReplay = performCreate(user, token, absentKey, absentBody);
		assertNull(absent.openingTransactionId());
		assertNull(absentReplay.openingTransactionId());
		assertEquals(absent.accountId(), absentReplay.accountId());

		String missingKey = "account-opening-missing-replay-01";
		String openingBody = openingBody("10.00", "CNY").replace("期初边界", "缺失期初");
		CreatedResponse missing = performCreate(user, token, missingKey, openingBody);
		jdbc.update("""
			UPDATE transactions
			SET status = 'REVERSED', entity_version = entity_version + 1, updated_at = CURRENT_TIMESTAMP
			WHERE id = ?
			""", missing.openingTransactionId());
		assertInternalErrorReplay(token, missingKey, openingBody);

		String ambiguousKey = "account-opening-ambiguous-001";
		String ambiguousBody = openingBody("20.00", "CNY").replace("期初边界", "歧义期初");
		CreatedResponse ambiguous = performCreate(user, token, ambiguousKey, ambiguousBody);
		MDC.put("requestId", "account-opening-ambiguity-injection");
		UUID secondOpening = ledgerInitialization.postOpening(
			ambiguous.accountId(), "ASSET", "CNY", user.userId(),
			new AccountOpeningBalance(new BigDecimal("1.00"), Instant.parse("2026-08-17T02:00:00Z"), "异常第二笔"),
			ZoneId.of("Asia/Shanghai"));
		assertNotEquals(ambiguous.openingTransactionId(), secondOpening);
		assertEquals(2, openingCount(ambiguous.accountId()));
		assertInternalErrorReplay(token, ambiguousKey, ambiguousBody);
		assertEquals(1, idempotencyCount(user.userId(), ambiguousKey));
	}

	private CreatedResponse performCreate(UserFixture user, String token, String key, String body) throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andReturn();
		JsonNode bodyNode = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID accountId = UUID.fromString(bodyNode.at("/data/account/id").asString());
		String openingText = bodyNode.at("/data/openingTransactionId").isNull()
			? null : bodyNode.at("/data/openingTransactionId").asString();
		return new CreatedResponse(accountId, openingText == null ? null : UUID.fromString(openingText),
			result.getResponse().getHeader(HttpHeaders.ETAG), bodyNode);
	}

	private void assertCreatedFacts(
		CreatedResponse created,
		UUID userId,
		String accountClass,
		String currency,
		int expectedLedgerCount,
		String primaryDirection,
		String equityDirection,
		String amount,
		String businessDate,
		String timezone) {
		assertNotNull(created.accountId());
		assertNotNull(created.openingTransactionId());
		assertEquals("\"1\"", created.etag());
		assertBaseAccountFacts(created.accountId(), userId, accountClass, currency, expectedLedgerCount);
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND transaction_type = 'OPENING' AND status = 'POSTED'",
			created.openingTransactionId()));
		assertEquals(businessDate, jdbc.queryForObject(
			"SELECT business_date::text FROM transactions WHERE id = ?", String.class, created.openingTransactionId()));
		assertEquals(timezone, jdbc.queryForObject(
			"SELECT timezone FROM transactions WHERE id = ?", String.class, created.openingTransactionId()));
		assertEntry(created.openingTransactionId(), "PRIMARY", null, primaryDirection, amount);
		assertEntry(created.openingTransactionId(), "SYSTEM", "EQUITY_OPENING_BALANCE", equityDirection, amount);
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE action = 'TRANSACTION_POSTED' AND resource_id = ?",
			created.openingTransactionId()));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'TransactionPosted'",
			created.openingTransactionId()));
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE action = 'TRANSACTION_POSTED' AND actor_user_id = ? AND resource_id = ?",
			userId, created.openingTransactionId()));
	}

	private void assertEntry(UUID transactionId, String ledgerRole, String code, String direction, String amount) {
		var row = code == null ? jdbc.queryForMap("""
			SELECT e.direction, e.amount
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND la.ledger_role = ?
			""", transactionId, ledgerRole) : jdbc.queryForMap("""
			SELECT e.direction, e.amount
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND la.code = ?
			""", transactionId, code);
		assertEquals(direction, row.get("direction"));
		assertEquals(0, ((BigDecimal) row.get("amount")).compareTo(new BigDecimal(amount)));
	}

	private void assertBaseAccountFacts(UUID accountId, UUID userId, String accountClass, String currency, int expectedLedgerCount) {
		assertEquals(1, count("SELECT count(*) FROM accounts WHERE id = ? AND created_by = ? AND account_class = ? AND currency = ?",
			accountId, userId, accountClass, currency));
		assertEquals(1, count("SELECT count(*) FROM account_members WHERE account_id = ? AND user_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
			accountId, userId));
		assertEquals(1, count("""
			SELECT count(*) FROM account_inclusion_settings s
			JOIN account_members m ON m.id = s.membership_id
			WHERE m.account_id = ? AND m.user_id = ? AND s.included = TRUE AND s.ratio = 1.000000 AND s.valid_to IS NULL
			""", accountId, userId));
		assertEquals(expectedLedgerCount, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", accountId));
		assertEquals(1, count("""
			SELECT count(*) FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'PRIMARY' AND status = 'ACTIVE'
			""", accountId));
		if ("INVESTMENT".equals(accountClass)) {
			assertEquals(1, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ? AND ledger_role = 'POSITION_COST'", accountId));
		} else {
			assertEquals(0, count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ? AND ledger_role = 'POSITION_COST'", accountId));
		}
	}

	private void mutateInitialRepresentation(UUID accountId, UUID userId, String mutation) {
		UUID membershipId = jdbc.queryForObject("SELECT id FROM account_members WHERE account_id = ? AND user_id = ?", UUID.class,
			accountId, userId);
		if ("ROLE".equals(mutation)) {
			addSecondOwner(accountId, userId);
			jdbc.update("UPDATE account_members SET role = 'EDITOR', version = version + 1 WHERE id = ?", membershipId);
		} else if ("REMOVED".equals(mutation)) {
			addSecondOwner(accountId, userId);
			jdbc.update("UPDATE account_members SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ?", membershipId);
		} else if ("INCLUDED".equals(mutation)) {
			jdbc.update("UPDATE account_inclusion_settings SET included = FALSE, ratio = 0 WHERE membership_id = ? AND valid_to IS NULL", membershipId);
		} else {
			jdbc.update("UPDATE account_inclusion_settings SET ratio = 0.500000 WHERE membership_id = ? AND valid_to IS NULL", membershipId);
		}
	}

	private void addSecondOwner(UUID accountId, UUID originalUserId) {
		UserFixture second = insertUser("second-owner-" + accountId);
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 2, 1)
				""", membershipId, accountId, second.userId(), java.sql.Timestamp.from(now));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 0.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, java.sql.Timestamp.from(now), second.userId(), java.sql.Timestamp.from(now));
		});
	}

	private void assertInternalErrorReplay(String token, String key, String body) throws Exception {
		mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isInternalServerError())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	private FactCounts factCounts(UUID accountId, UUID openingTransactionId) {
		int openingTransactions = openingTransactionId == null ? openingCount(accountId)
			: count("SELECT count(*) FROM transactions WHERE id = ?", openingTransactionId);
		int entries = openingTransactionId == null ? count("""
			SELECT count(*) FROM ledger_entries e
			JOIN transactions t ON t.id = e.transaction_id
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE la.visible_account_id = ?
			""", accountId) : count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", openingTransactionId);
		int audit = openingTransactionId == null ? count("SELECT count(*) FROM audit_logs WHERE account_id = ?", accountId)
			: count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", openingTransactionId);
		int outbox = openingTransactionId == null ? count("SELECT count(*) FROM outbox_events WHERE aggregate_id IN (SELECT id FROM transactions WHERE id = ?)", accountId)
			: count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", openingTransactionId);
		return new FactCounts(
			count("SELECT count(*) FROM accounts WHERE id = ?", accountId),
			count("SELECT count(*) FROM account_members WHERE account_id = ?", accountId),
			count("""
				SELECT count(*) FROM account_inclusion_settings s
				JOIN account_members m ON m.id = s.membership_id WHERE m.account_id = ?
				""", accountId),
			count("SELECT count(*) FROM ledger_accounts WHERE visible_account_id = ?", accountId),
			openingTransactions, entries, audit, outbox);
	}

	private int openingCount(UUID accountId) {
		return count("""
			SELECT count(DISTINCT t.id)
			FROM transactions t
			JOIN ledger_entries e ON e.transaction_id = t.id
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE t.transaction_type = 'OPENING' AND la.visible_account_id = ? AND la.ledger_role = 'PRIMARY'
			""", accountId);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "account-create-http", "account-create-http-" + user.userId()));
		return session.accessToken();
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "account-create-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '账户创建 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private int idempotencyCount(UUID userId, String key) {
		return count("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?", userId, key);
	}

	private int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private static String assetBody(String name) {
		return """
			{"accountClass":"ASSET","accountType":"BANK","name":"%s","currency":"CNY"}
			""".formatted(name);
	}

	private static String openingBody(String amount, String currency) {
		return """
			{"accountClass":"ASSET","accountType":"BANK","name":"期初边界","currency":"%s",
			 "openingBalance":{"amount":"%s","businessAt":"2026-08-17T01:02:03Z"}}
			""".formatted(currency, amount);
	}

	private record UserFixture(UUID userId) {
	}

	private record CreatedResponse(UUID accountId, UUID openingTransactionId, String etag, JsonNode body) {
	}

	private record FactCounts(
		int accounts,
		int memberships,
		int inclusions,
		int ledgerAccounts,
		int openingTransactions,
		int ledgerEntries,
		int auditLogs,
		int outboxEvents) {
	}
}
