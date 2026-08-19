package app.ziji;

import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountType;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T-LIA-001：真实 SecurityFilterChain、PostgreSQL 与统一幂等验收独立负债详情。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LiabilityDetailHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AccountCreationService accountCreation;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private TransactionRunner transactions;

	@Test
	void securityMatcherReturns401AllowsBearerWithoutCsrfAndKeepsUnknownPathDenied() throws Exception {
		UserFixture owner = insertUser("security-owner");
		Account account = createLiability(owner.userId(), AccountType.CREDIT_CARD, AccountCurrency.CNY, "安全边界");

		mvc.perform(get(path(account.id())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get(path(account.id()) + "/unknown"))
			.andExpect(status().isForbidden());

		String token = bearer(owner);
		mvc.perform(put(path(account.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-None-Match", "*").header("Idempotency-Key", "http-liability-csrf-001")
				.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("100.00", 8)))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
	}

	@Test
	void getPutPatchAndSafeReplayPreserveDetailIdentityAndAccountVersion() throws Exception {
		UserFixture owner = insertUser("lifecycle-owner");
		Account account = createLiability(owner.userId(), AccountType.OTHER, AccountCurrency.CNY, "生命周期");
		String token = bearer(owner);
		int accountVersion = accountVersion(account.id());

		mvc.perform(get(path(account.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
			.andExpect(jsonPath("$.data.accountId").value(account.id().toString()))
			.andExpect(jsonPath("$.data.interestRate").value(nullValue()))
			.andExpect(jsonPath("$.data.loanDate").value(nullValue()))
			.andExpect(jsonPath("$.data.dueDate").value(nullValue()))
			.andExpect(jsonPath("$.data.billingDay").value(nullValue()))
			.andExpect(jsonPath("$.data.repaymentDay").value(nullValue()))
			.andExpect(jsonPath("$.data.currentAmountDue").value(nullValue()))
			.andExpect(jsonPath("$.data.version").value(0));

		String createKey = "http-liability-create-01";
		String createBody = otherJson("100.00", 8, "2026-01-01", "2027-01-01");
		assertPut(token, account.id(), createKey, "If-None-Match", "*", createBody, 201, "\"1\"");
		assertPut(token, account.id(), createKey, "If-None-Match", "*", createBody, 201, "\"1\"");
		assertEquals(1, detailVersion(account.id()));
		assertEquals(1, idempotencyCount(owner.userId(), createKey));

		String replaceKey = "http-liability-replace-1";
		String replaceBody = otherJson("80.50", 9, "2026-01-01", "2027-02-01");
		assertPut(token, account.id(), replaceKey, "If-Match", "\"1\"", replaceBody, 200, "\"2\"");
		assertPut(token, account.id(), replaceKey, "If-Match", "\"1\"", replaceBody, 200, "\"2\"");
		assertEquals(2, detailVersion(account.id()));

		String patchKey = "http-liability-patch-001";
		String patchBody = "{\"billingDay\":null,\"repaymentDay\":22,\"currentAmountDue\":\"0\"}";
		assertPatch(token, account.id(), patchKey, "\"2\"", patchBody, 200, "\"3\"");
		assertPatch(token, account.id(), patchKey, "\"2\"", patchBody, 200, "\"3\"");
		assertEquals(3, detailVersion(account.id()));
		assertEquals(accountVersion, accountVersion(account.id()));

		mvc.perform(put(path(account.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-None-Match", "*").header("Idempotency-Key", createKey)
				.contentType(MediaType.APPLICATION_JSON).content(otherJson("101.00", 8, "2026-01-01", "2027-01-01")))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertEquals(3, detailVersion(account.id()));
	}

	@Test
	void activeRolesReadWhileOnlyOwnerAndEditorWriteAndHiddenFactsStay404BeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("roles-owner");
		UserFixture editor = insertUser("roles-editor");
		UserFixture viewer = insertUser("roles-viewer");
		UserFixture left = insertUser("roles-left");
		UserFixture removed = insertUser("roles-removed");
		UserFixture ended = insertUser("roles-ended");
		UserFixture stranger = insertUser("roles-stranger");
		UserFixture createdByOnly = insertUser("roles-created-by");
		Account account = createLiability(owner.userId(), AccountType.CREDIT_CARD, AccountCurrency.CNY, "角色矩阵");
		addMembership(account.id(), editor.userId(), "EDITOR", "ACTIVE");
		addMembership(account.id(), viewer.userId(), "VIEWER", "ACTIVE");
		addMembership(account.id(), left.userId(), "VIEWER", "LEFT");
		addMembership(account.id(), removed.userId(), "EDITOR", "REMOVED");
		addMembership(account.id(), ended.userId(), "VIEWER", "LEFT");
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", createdByOnly.userId(), account.id());

		for (UserFixture readable : new UserFixture[] {owner, editor, viewer}) {
			mvc.perform(get(path(account.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(readable)))
				.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"0\""));
		}
		assertPut(bearer(owner), account.id(), "http-owner-create-0001", "If-None-Match", "*",
			creditCardJson("100.00", 8), 201, "\"1\"");
		assertPatch(bearer(editor), account.id(), "http-editor-patch-001", "\"1\"",
			"{\"repaymentDay\":21}", 200, "\"2\"");

		String viewerKey = "http-viewer-write-001";
		assertProblemWithoutIdempotency(put(path(account.id()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer)).header("If-Match", "\"2\"")
			.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("90.00", 9)),
			viewer.userId(), viewerKey, 403, "PERMISSION_DENIED");

		for (UserFixture hidden : new UserFixture[] {left, removed, ended, stranger, createdByOnly}) {
			String token = bearer(hidden);
			mvc.perform(get(path(account.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
			String key = "http-hidden-" + hidden.userId();
			assertProblemWithoutIdempotency(put(path(account.id()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("90.00", 9)),
				hidden.userId(), key, 404, "RESOURCE_NOT_FOUND");
		}

		UserFixture assetOwner = insertUser("asset-owner");
		Account asset = accountCreation.createAccount(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.BANK, "非负债", null, AccountCurrency.CNY, null, assetOwner.userId()));
		String assetKey = "http-non-liability-001";
		assertProblemWithoutIdempotency(put(path(asset.id()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(assetOwner)).header("If-None-Match", "*")
			.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("10.00", 8)),
			assetOwner.userId(), assetKey, 404, "RESOURCE_NOT_FOUND");
	}

	@Test
	void validationBusinessAndVersionFailuresLeaveNoIdempotencyRecordOrSuccessEtag() throws Exception {
		UserFixture owner = insertUser("failures-owner");
		Account card = createLiability(owner.userId(), AccountType.CREDIT_CARD, AccountCurrency.CNY, "失败边界");
		String token = bearer(owner);

		assertFailure(token, owner.userId(), card.id(), "http-unknown-field-01",
			creditCardJson("100.00", 8).replace("}", ",\"unknown\":true}"), 400, "VALIDATION_ERROR");
		assertFailure(token, owner.userId(), card.id(), "http-invalid-rate-01",
			creditCardJson("100.00", 8).replace("\"0.05\"", "\"1.000000001\""), 400, "VALIDATION_ERROR");
		assertFailure(token, owner.userId(), card.id(), "http-wrong-field-0001",
			creditCardJson("100.00", 8).replace("\"loanDate\":null", "\"loanDate\":\"2026-01-01\""),
			422, "BUSINESS_RULE_VIOLATION");

		Account jpy = createLiability(owner.userId(), AccountType.OTHER, AccountCurrency.JPY, "日元精度");
		assertFailure(token, owner.userId(), jpy.id(), "http-jpy-precision-001",
			otherJson("10.01", 8, "2026-01-01", "2027-01-01"), 422, "BUSINESS_RULE_VIOLATION");
		assertFailure(token, owner.userId(), jpy.id(), "http-date-relation-01",
			otherJson("10", 8, "2027-01-01", "2026-01-01"), 422, "BUSINESS_RULE_VIOLATION");

		String createKey = "http-version-create-01";
		assertPut(token, card.id(), createKey, "If-None-Match", "*", creditCardJson("100.00", 8), 201, "\"1\"");
		assertVersionConflict(token, owner.userId(), card.id(), "http-create-conflict-1", "If-None-Match", "*", 1);
		assertVersionConflict(token, owner.userId(), card.id(), "http-stale-conflict-01", "If-Match", "\"2\"", 1);

		Account withoutDetails = createLiability(owner.userId(), AccountType.CREDIT_CARD, AccountCurrency.CNY, "无详情 PATCH");
		String missingKey = "http-patch-missing-001";
		mvc.perform(patch(path(withoutDetails.id())).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"").header("Idempotency-Key", missingKey)
				.contentType("application/merge-patch+json").content("{\"billingDay\":8}"))
			.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertEquals(0, idempotencyCount(owner.userId(), missingKey));
	}

	private void assertFailure(
		String token, UUID userId, UUID accountId, String key, String body, int statusCode, String code) throws Exception {
		mvc.perform(put(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-None-Match", "*").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(statusCode)).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value(code));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertVersionConflict(
		String token, UUID userId, UUID accountId, String key,
		String conditionName, String conditionValue, int currentVersion) throws Exception {
		mvc.perform(put(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(conditionName, conditionValue).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("90.00", 9)))
			.andExpect(status().isConflict()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(currentVersion))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"" + currentVersion + "\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(path(accountId)));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertProblemWithoutIdempotency(
		MockHttpServletRequestBuilder request, UUID userId, String key, int statusCode, String code) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().is(statusCode)).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value(code));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertPut(
		String token, UUID accountId, String key, String conditionName, String conditionValue,
		String body, int statusCode, String etag) throws Exception {
		mvc.perform(put(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(conditionName, conditionValue).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(statusCode)).andExpect(header().string(HttpHeaders.ETAG, etag))
			.andExpect(jsonPath("$.data.accountId").value(accountId.toString()))
			.andExpect(jsonPath("$.data.currentAmountDue").isString());
	}

	private void assertPatch(
		String token, UUID accountId, String key, String ifMatch, String body, int statusCode, String etag) throws Exception {
		mvc.perform(patch(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", ifMatch).header("Idempotency-Key", key)
				.contentType("application/merge-patch+json").content(body))
			.andExpect(status().is(statusCode)).andExpect(header().string(HttpHeaders.ETAG, etag))
			.andExpect(jsonPath("$.data.accountId").value(accountId.toString()));
	}

	private Account createLiability(
		UUID ownerId, AccountType type, AccountCurrency currency, String name) {
		return accountCreation.createAccount(new AccountCreationCommand(
			AccountClass.LIABILITY, type, name, null, currency, null, ownerId));
	}

	private void addMembership(UUID accountId, UUID userId, String role, String membershipStatus) {
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1, 1)
				""", membershipId, accountId, userId, role, membershipStatus, now.toString(),
				"ACTIVE".equals(membershipStatus) ? null : now.toString());
			if ("ACTIVE".equals(membershipStatus)) {
				jdbc.update("""
					INSERT INTO account_inclusion_settings
						(id, membership_id, included, ratio, valid_from, created_by, created_at)
					VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
					""", UUID.randomUUID(), membershipId, now.toString(), userId, now.toString());
			}
		});
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "liability-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '负债 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "liability-http", "liability-device-" + user.userId()));
		return session.accessToken();
	}

	private int idempotencyCount(UUID userId, String key) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
			Integer.class, userId, key);
	}

	private int detailVersion(UUID accountId) {
		return jdbc.queryForObject("SELECT version FROM liability_details WHERE account_id = ?", Integer.class, accountId);
	}

	private int accountVersion(UUID accountId) {
		return jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, accountId);
	}

	private static String path(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liability-details";
	}

	private static String creditCardJson(String amount, int billingDay) {
		return "{\"interestRate\":\"0.05\",\"loanDate\":null,\"dueDate\":null,\"billingDay\":" + billingDay
			+ ",\"repaymentDay\":20,\"currentAmountDue\":\"" + amount + "\"}";
	}

	private static String otherJson(String amount, int billingDay, String loanDate, String dueDate) {
		return "{\"interestRate\":\"0.05\",\"loanDate\":\"" + loanDate + "\",\"dueDate\":\"" + dueDate
			+ "\",\"billingDay\":" + billingDay + ",\"repaymentDay\":20,\"currentAmountDue\":\"" + amount + "\"}";
	}

	private record UserFixture(UUID userId) {
	}
}
