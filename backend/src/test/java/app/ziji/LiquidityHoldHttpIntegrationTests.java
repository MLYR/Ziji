package app.ziji;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BUG-API-006：真实 SecurityFilterChain 与 PostgreSQL 验收 LiquidityHold 授权、防枚举和幂等前置。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LiquidityHoldHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private TransactionRunner transactions;

	@Test
	void activeRoleMatrixUsesMembershipFactsAndHidesInactiveOrUnrelatedAccountsBeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("hold-owner");
		UserFixture editor = insertUser("hold-editor");
		UserFixture viewer = insertUser("hold-viewer");
		UserFixture left = insertUser("hold-left");
		UserFixture removed = insertUser("hold-removed");
		UserFixture ended = insertUser("hold-ended");
		UserFixture stranger = insertUser("hold-stranger");
		UserFixture createdByOnly = insertUser("hold-created-by-only");
		AccountFixture account = seedAccount(owner.userId(), "授权矩阵");
		addMembership(account.accountId(), editor.userId(), "EDITOR", "ACTIVE");
		addMembership(account.accountId(), viewer.userId(), "VIEWER", "ACTIVE");
		addMembership(account.accountId(), left.userId(), "VIEWER", "LEFT");
		addMembership(account.accountId(), removed.userId(), "EDITOR", "REMOVED");
		// 历史成员周期在冻结机器基线中必须以非 ACTIVE 状态和 ended_at 表达。
		addMembership(account.accountId(), ended.userId(), "VIEWER", "LEFT");
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", createdByOnly.userId(), account.accountId());

		assertAllowedAllRoutes(owner, account.accountId(), "owner", seedHold(account.accountId(), owner.userId()),
			seedHold(account.accountId(), owner.userId()));
		assertAllowedAllRoutes(editor, account.accountId(), "editor", seedHold(account.accountId(), owner.userId()),
			seedHold(account.accountId(), owner.userId()));
		assertViewerCanReadButCannotWrite(viewer, account.accountId(), seedHold(account.accountId(), owner.userId()));
		assertInvisibleAllRoutes(left, account.accountId(), seedHold(account.accountId(), owner.userId()), "left");
		assertInvisibleAllRoutes(removed, account.accountId(), seedHold(account.accountId(), owner.userId()), "removed");
		assertInvisibleAllRoutes(ended, account.accountId(), seedHold(account.accountId(), owner.userId()), "ended");
		assertInvisibleAllRoutes(stranger, account.accountId(), seedHold(account.accountId(), owner.userId()), "stranger");
		assertInvisibleAllRoutes(createdByOnly, account.accountId(), seedHold(account.accountId(), owner.userId()), "created-by");
	}

	@Test
	void holdOutsideUrlAccountReturnsNotFoundBeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("hold-account-mismatch-owner");
		AccountFixture first = seedAccount(owner.userId(), "归属账户一");
		AccountFixture second = seedAccount(owner.userId(), "归属账户二");
		UUID holdId = seedHold(first.accountId(), owner.userId());
		String token = bearer(owner);

		assertNotFoundWithoutIdempotency(post(path(second.accountId()) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(commandJson()), owner.userId(), "mismatch-revise-key-001");
		assertNotFoundWithoutIdempotency(post(path(second.accountId()) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("If-Match", "\"1\""), owner.userId(), "mismatch-release-key-01");
	}

	@Test
	void reviseAndReleaseIfMatchFailuresDoNotWriteIdempotencyRecords() throws Exception {
		UserFixture owner = insertUser("hold-if-match-owner");
		AccountFixture account = seedAccount(owner.userId(), "If-Match");
		String token = bearer(owner);
		List<String> invalidValues = List.of("W/\"1\"", "*", "1", "\"0\"", "\"-1\"", "\"abc\"", "\"2147483648\"");
		for (String value : invalidValues) {
			assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", value).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "invalid-revise-" + UUID.randomUUID());
			assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", value), "invalid-release-" + UUID.randomUUID());
		}
		assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "missing-revise-key-001");
		assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token), "missing-release-key-01");
		assertDuplicateIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "duplicate-revise-key");
		assertDuplicateIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token), "duplicate-release-key");

		UUID staleHoldId = seedHold(account.accountId(), owner.userId());
		String staleKey = "stale-if-match-key-001";
		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", staleHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", staleKey)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		assertEquals(0, idempotencyCount(owner.userId(), staleKey));
		UUID staleReleaseHoldId = seedHold(account.accountId(), owner.userId());
		String staleReleaseKey = "stale-release-key-001";
		mvc.perform(post(path(account.accountId()) + "/{holdId}/release", staleReleaseHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", staleReleaseKey)
				.header("If-Match", "\"2\""))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		assertEquals(0, idempotencyCount(owner.userId(), staleReleaseKey));
	}

	private void assertAllowedAllRoutes(
		UserFixture user,
		UUID accountId,
		String role,
		UUID revisionHoldId,
		UUID releaseHoldId) throws Exception {
		String token = bearer(user);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray());
		mvc.perform(post(path(accountId))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-create-key-0001")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		mvc.perform(post(path(accountId) + "/{holdId}/revisions", revisionHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-revise-key-0001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		mvc.perform(post(path(accountId) + "/{holdId}/release", releaseHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-release-key-001")
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
	}

	private void assertViewerCanReadButCannotWrite(UserFixture viewer, UUID accountId, UUID holdId) throws Exception {
		String token = bearer(viewer);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
		assertForbiddenWithoutIdempotency(post(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), viewer.userId(), "viewer-create-key-01");
		assertForbiddenWithoutIdempotency(post(path(accountId) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), viewer.userId(), "viewer-revise-key-01");
		assertForbiddenWithoutIdempotency(post(path(accountId) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\""), viewer.userId(), "viewer-release-key-1");
	}

	private void assertInvisibleAllRoutes(UserFixture user, UUID accountId, UUID holdId, String label) throws Exception {
		String token = bearer(user);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertNotFoundWithoutIdempotency(post(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), user.userId(), label + "-create-key-001");
		assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), user.userId(), label + "-revise-key-001");
		assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\""), user.userId(), label + "-release-key-001");
	}

	private void assertForbiddenWithoutIdempotency(
		MockHttpServletRequestBuilder request,
		UUID userId,
		String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertNotFoundWithoutIdempotency(
		MockHttpServletRequestBuilder request,
		UUID userId,
		String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertInvalidIfMatch(UUID userId, MockHttpServletRequestBuilder request, String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertDuplicateIfMatch(UUID userId, MockHttpServletRequestBuilder request, String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key).header("If-Match", "\"1\"").header("If-Match", "\"1\""))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private int idempotencyCount(UUID userId, String key) {
		return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
			Integer.class, userId, key);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "liquidity-http", "liquidity-http-device-" + user.userId()));
		return session.accessToken();
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "liquidity-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '流动性 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private AccountFixture seedAccount(UUID ownerId, String name) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, institution, currency, note, status,
					 archived_at, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, '测试机构', 'CNY', NULL, 'ACTIVE', NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
				""", accountId, name, ownerId, now.toString(), now.toString());
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', CAST(? AS timestamptz), 1, 1)
				""", membershipId, accountId, ownerId, now.toString());
			insertCurrentInclusion(membershipId, ownerId, now);
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', CAST(? AS timestamptz))
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, now.toString());
		});
		return new AccountFixture(accountId);
	}

	private void addMembership(UUID accountId, UUID userId, String role, String status) {
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status,
				java.sql.Timestamp.from(now), "ACTIVE".equals(status) ? null : java.sql.Timestamp.from(now));
			if ("ACTIVE".equals(status)) {
				insertCurrentInclusion(membershipId, userId, now);
			}
		});
	}

	private void insertCurrentInclusion(UUID membershipId, UUID userId, Instant now) {
		jdbc.update("""
			INSERT INTO account_inclusion_settings
				(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""", UUID.randomUUID(), membershipId, now.toString(), userId, now.toString());
	}

	private UUID seedHold(UUID accountId, UUID createdBy) {
		UUID holdId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> jdbc.update("""
			INSERT INTO liquidity_holds
				(id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				 root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at, updated_at, version)
			VALUES (?, ?, 'FROZEN', 10.00, 'CNY', CAST(? AS timestamptz), NULL, NULL, 'MANUAL', '测试冻结',
				?, NULL, 1, NULL, NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", holdId, accountId, now.minusSeconds(60).toString(), holdId, createdBy, now.toString(), now.toString()));
		return holdId;
	}

	private static String path(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liquidity-holds";
	}

	private static String commandJson() {
		Instant effectiveAt = Instant.now().minusSeconds(30);
		return "{\"type\":\"FROZEN\",\"amount\":\"10.00\",\"currency\":\"CNY\",\"effectiveAt\":\""
			+ effectiveAt + "\",\"expiresAt\":null,\"reason\":\"HTTP 验收\"}";
	}

	private record UserFixture(UUID userId) {}

	private record AccountFixture(UUID accountId) {}
}
