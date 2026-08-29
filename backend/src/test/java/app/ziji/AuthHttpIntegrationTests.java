package app.ziji;

import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.auth.interfaces.PublicAuthController;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.shared.application.TransactionRunner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 SecurityFilterChain + PostgreSQL 验收认证 HTTP、Cookie/CSRF、会话绑定和匿名幂等编排。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthHttpIntegrationTests extends PostgresIntegrationTestSupport {

	private static final String PASSWORD = "correct-password-123";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordHasher passwordHasher;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

	@Autowired
	private ChallengeCodeHasher challengeCodeHasher;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private AuthRateLimitStore rateLimitStore;

	@BeforeEach
	void clearAuthHttpFacts() {
		// 仅清理本类创建的独立认证事实，避免测试间 Cookie、会话和幂等状态串扰。
		jdbc.execute("TRUNCATE TABLE session_refresh_tokens, user_sessions");
		jdbc.update("DELETE FROM email_challenges WHERE email_normalized LIKE 'auth-http-%@example.test'");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'auth-http-%@example.test'");
	}

	@Test
	void webAndMobileLoginRefreshUseDifferentCredentialTransportsAndCsrf() throws Exception {
		UserFixture webUser = seedUser();
		MvcResult webLogin = mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(webUser.email(), "Web", "web-device")))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andReturn();
		String webRefresh = cookieValue(webLogin, "ziji_refresh");
		String webCsrf = cookieValue(webLogin, "ziji_csrf");
		assertNotNull(webRefresh);
		assertNotNull(webCsrf);
		assertCookieAttributes(webLogin, "ziji_refresh", "HttpOnly");
		assertCookieAttributes(webLogin, "ziji_csrf", "SameSite=Strict");

		MvcResult missingCsrf = mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", webCsrf)))
			.andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
			.andReturn();
		assertCookieAttributes(missingCsrf, "ziji_refresh", "Max-Age=0");
		assertCookieAttributes(missingCsrf, "ziji_csrf", "Max-Age=0");
		MvcResult wrongCsrf = mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", webCsrf))
				.header("X-CSRF-Token", "wrong-csrf-token"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
			.andReturn();
		assertCookieAttributes(wrongCsrf, "ziji_refresh", "Max-Age=0");
		assertCookieAttributes(wrongCsrf, "ziji_csrf", "Max-Age=0");
		mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", webCsrf))
				.header("X-CSRF-Token", webCsrf)
				.header("X-CSRF-Token", webCsrf))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
		mvc.perform(post("/api/v1/not-yet-implemented")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + json(webLogin).at("/data/accessToken").asString()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

		MvcResult webRefreshResult = mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", webCsrf))
				.header("X-CSRF-Token", webCsrf))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.data.refreshToken").doesNotExist())
			.andReturn();
		String rotatedRefresh = cookieValue(webRefreshResult, "ziji_refresh");
		String rotatedCsrf = cookieValue(webRefreshResult, "ziji_csrf");
		assertNotEquals(webRefresh, rotatedRefresh);

		MvcResult reuse = mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", rotatedCsrf))
				.header("X-CSRF-Token", rotatedCsrf))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
			.andReturn();
		assertCookieAttributes(reuse, "ziji_refresh", "Max-Age=0");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens "
			+ "WHERE session_id = (SELECT id FROM user_sessions WHERE user_id = ? ORDER BY issued_at DESC LIMIT 1) "
			+ "AND consumed_at IS NULL AND revoked_at IS NULL", Integer.class, webUser.userId()));

		UserFixture mobileUser = seedUser();
		MvcResult mobileLogin = mvc.perform(post("/api/v1/auth/mobile/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(mobileUser.email(), "Phone", "mobile-device")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.tokens.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.tokens.refreshToken").isNotEmpty())
			.andReturn();
		assertNull(cookieValue(mobileLogin, "ziji_refresh"));
		assertNull(cookieValue(mobileLogin, "ziji_csrf"));
		String mobileRefresh = json(mobileLogin).at("/data/tokens/refreshToken").asString();
		mvc.perform(post("/api/v1/auth/mobile/sessions/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + mobileRefresh + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.tokens.refreshToken").isNotEmpty())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void publicChallengeOperationsAreRoutedWithoutLeakingEmail() throws Exception {
		String email = uniqueEmail();
		String body = "{\"email\":\"" + email + "\",\"deviceId\":\"challenge-device\"}";
		MvcResult registration = mvc.perform(post("/api/v1/auth/registration-challenges")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.expiresIn").isNumber())
			.andReturn();
		MvcResult reset = mvc.perform(post("/api/v1/auth/password-reset-challenges")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.expiresIn").isNumber())
			.andReturn();
		assertFalse(registration.getResponse().getContentAsString().contains(email));
		assertFalse(reset.getResponse().getContentAsString().contains(email));
	}

	@Test
	void bearerTokenIsStrictAndImmediatelyInvalidAfterServerSessionRevocation() throws Exception {
		UserFixture user = seedUser();
		SessionTokenResult session = createSession(user.userId(), "Bearer", "bearer-device");

		mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(user.userId().toString()));
		mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Basic ignored"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/v1/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer one")
				.header(HttpHeaders.AUTHORIZATION, "Bearer two"))
			.andExpect(status().isUnauthorized());

		deviceSessionService.revokeCurrentDevice(user.userId(), session.sessionId());
		mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void invalidCredentialsKeepTheLoginResponseOutOfCaches() throws Exception {
		UserFixture user = seedUser();
		mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBodyWithPassword(user.email(), "wrong-password-123", "Web", "bad-web-device")))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/v1/auth/mobile/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBodyWithPassword(user.email(), "wrong-password-123", "Phone", "bad-mobile-device")))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void authenticatedSessionOperationsAreScopedToCurrentUserAndUseStableCursorPaging() throws Exception {
		UserFixture owner = seedUser();
		SessionTokenResult current = createSession(owner.userId(), "Current", "current-device");
		SessionTokenResult selected = createSession(owner.userId(), "One", "one-device");
		createSession(owner.userId(), "Two", "two-device");
		UserFixture stranger = seedUser();
		SessionTokenResult strangerSession = createSession(stranger.userId(), "Other", "other-device");

		MvcResult first = mvc.perform(get("/api/v1/users/me/sessions")
				.param("limit", "2")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.meta.hasMore").value(true))
			.andReturn();
		String cursor = json(first).at("/meta/nextCursor").asString();
		MvcResult second = mvc.perform(get("/api/v1/users/me/sessions")
				.param("limit", "2")
				.param("cursor", cursor)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isOk())
			.andReturn();
		Set<String> ids = new HashSet<>();
		json(first).at("/data").forEach(node -> ids.add(node.get("id").asString()));
		json(second).at("/data").forEach(node -> ids.add(node.get("id").asString()));
		assertEquals(3, ids.size());
		mvc.perform(get("/api/v1/users/me/sessions")
				.param("cursor", "not-a-valid-cursor")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mvc.perform(delete("/api/v1/users/me/sessions/{sessionId}", strangerSession.sessionId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL",
			Integer.class, strangerSession.sessionId()));
		mvc.perform(delete("/api/v1/users/me/sessions/{sessionId}", selected.sessionId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isNoContent());
		assertEquals("SELECTED_DEVICE", jdbc.queryForObject("SELECT revoke_reason FROM user_sessions WHERE id = ?",
			String.class, selected.sessionId()));

		mvc.perform(delete("/api/v1/auth/sessions/current")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + current.accessToken()))
			.andExpect(status().isNoContent());
		MvcResult revokeAll = mvc.perform(delete("/api/v1/users/me/sessions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + createSession(owner.userId(), "New", "new-device").accessToken()))
			.andExpect(status().isNoContent()).andReturn();
		assertCookieAttributes(revokeAll, "ziji_refresh", "Max-Age=0");
		assertTrue(jdbc.queryForObject("SELECT COUNT(*) > 0 FROM user_sessions WHERE user_id = ? "
			+ "AND revoke_reason = 'ALL_DEVICES'", Boolean.class, owner.userId()));
	}

	@Test
	void authenticatedPasswordChangeUsesBearerUserAndKeepsCurrentSession() throws Exception {
		UserFixture user = seedUser();
		SessionTokenResult session = createSession(user.userId(), "Change", "change-device");
		String newPassword = "changed-password-123";
		mvc.perform(post("/api/v1/users/me/password-change")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
				.content("{\"currentPassword\":\"wrong-password-123\",\"newPassword\":\"" + newPassword + "\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/v1/users/me/password-change")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + newPassword + "\"}"))
			.andExpect(status().isNoContent());
		String storedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, user.userId());
		assertTrue(passwordHasher.matches(newPassword, storedHash));
		assertFalse(passwordHasher.matches(PASSWORD, storedHash));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL",
			Integer.class, session.sessionId()));
		mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
			.andExpect(status().isOk());
	}

	@Test
	void publicRegisterAndResetUseAnonymousIdempotencyWithoutLeakingSecrets() throws Exception {
		String email = uniqueEmail();
		insertChallenge(email, EmailChallengePurpose.REGISTER, "123456");
		String key = "register-key-" + UUID.randomUUID();
		String body = registerBody(email, "123456", PASSWORD);

		MvcResult first = mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/users/me"))
			.andExpect(jsonPath("$.data.email").value(email))
			.andReturn();
		MvcResult replay = mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, first.getResponse().getHeader(HttpHeaders.ETAG)))
			.andReturn();
		for (int retry = 0; retry < 8; retry++) {
			mvc.perform(post("/api/v1/auth/register")
					.contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", key)
					.content(body))
				.andExpect(status().isCreated());
		}
		assertFalse(replay.getResponse().getContentAsString().contains(PASSWORD));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email_normalized = ?", Integer.class,
			EmailAddress.normalize(email).value()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
			Integer.class, key));
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content(registerBody(email, "123456", "different-password-123")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		String resetKey = "reset-key-" + UUID.randomUUID();
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, "654321");
		String reset = "{\"email\":\"" + email + "\",\"challengeCode\":\"654321\",\"newPassword\":\"reset-password-123\"}";
		mvc.perform(post("/api/v1/auth/password-reset")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", resetKey)
				.content(reset))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/auth/password-reset")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", resetKey)
				.content(reset))
			.andExpect(status().isNoContent());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
			Integer.class, resetKey));
	}

	@Test
	void invalidHeadersDoNotCreateRecordsAndDuplicateRegistrationRollsBackChallengeButCommitsFinalReplay() throws Exception {
		String invalidEmail = uniqueEmail();
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "short")
				.content(registerBody(invalidEmail, "123456", PASSWORD)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = 'short'",
			Integer.class));
		String malformedKey = "malformed-json-key-" + UUID.randomUUID();
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", malformedKey)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
			Integer.class, malformedKey));

		UserFixture existing = seedUser();
		insertChallenge(existing.email(), EmailChallengePurpose.REGISTER, "123456");
		String key = "duplicate-key-" + UUID.randomUUID();
		String body = registerBody(existing.email(), "123456", PASSWORD);
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges WHERE email_normalized = ? AND consumed_at IS NOT NULL",
			Integer.class, existing.email()));
		assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM idempotency_records WHERE idempotency_key = ?",
			String.class, key));
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ? "
			+ "AND status = 'PROCESSING'", Integer.class, key));
	}

	@Test
	void retryableRegistrationAndResetFailuresCommitOnlyIdempotencyTerminalState() throws Exception {
		String registrationEmail = uniqueEmail();
		insertChallenge(registrationEmail, EmailChallengePurpose.REGISTER, "123456");
		String registrationKey = "retryable-register-" + UUID.randomUUID();
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_auth_http_user_insert_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				RAISE EXCEPTION 'test-only registration write failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_auth_http_user_insert_for_test
			BEFORE INSERT ON users
			FOR EACH ROW EXECUTE FUNCTION reject_auth_http_user_insert_for_test()
			""");
		try {
			mvc.perform(post("/api/v1/auth/register")
					.contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", registrationKey)
					.content(registerBody(registrationEmail, "123456", PASSWORD)))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		} finally {
			jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_auth_http_user_insert_for_test ON users");
			jdbc.execute("DROP FUNCTION IF EXISTS reject_auth_http_user_insert_for_test()");
		}
		assertEquals("FAILED_RETRYABLE", jdbc.queryForObject(
			"SELECT status FROM idempotency_records WHERE idempotency_key = ?", String.class, registrationKey));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email_normalized = ?",
			Integer.class, registrationEmail));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, registrationEmail));

		UserFixture resetUser = seedUser();
		SessionTokenResult resetSession = createSession(resetUser.userId(), "Reset", "reset-device");
		String originalHash = jdbc.queryForObject(
			"SELECT password_hash FROM users WHERE id = ?", String.class, resetUser.userId());
		insertChallenge(resetUser.email(), EmailChallengePurpose.RESET_PASSWORD, "654321");
		String resetKey = "retryable-reset-" + UUID.randomUUID();
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_auth_http_password_update_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				RAISE EXCEPTION 'test-only password update failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_auth_http_password_update_for_test
			BEFORE UPDATE OF password_hash ON users
			FOR EACH ROW EXECUTE FUNCTION reject_auth_http_password_update_for_test()
			""");
		try {
			mvc.perform(post("/api/v1/auth/password-reset")
					.contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", resetKey)
					.content("{\"email\":\"" + resetUser.email()
						+ "\",\"challengeCode\":\"654321\",\"newPassword\":\"reset-password-123\"}"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		} finally {
			jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_auth_http_password_update_for_test ON users");
			jdbc.execute("DROP FUNCTION IF EXISTS reject_auth_http_password_update_for_test()");
		}
		assertEquals("FAILED_RETRYABLE", jdbc.queryForObject(
			"SELECT status FROM idempotency_records WHERE idempotency_key = ?", String.class, resetKey));
		assertEquals(originalHash, jdbc.queryForObject(
			"SELECT password_hash FROM users WHERE id = ?", String.class, resetUser.userId()));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_challenges "
			+ "WHERE email_normalized = ? AND consumed_at IS NOT NULL", Integer.class, resetUser.email()));
		assertEquals(0, jdbc.queryForObject(
			"SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NOT NULL",
			Integer.class, resetSession.sessionId()));
	}

	@Test
	void safeReplayUnavailableFailsClosedWithoutExecutingRegistrationWork() throws Exception {
		IdempotencyRecordStore safeReplayStore = new IdempotencyRecordStore() {
			@Override
			public Acquisition acquire(IdempotencyRequest request, Instant now) {
				return new Acquisition.SafeReplayUnavailable();
			}

			@Override
			public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
				throw new AssertionError("safe replay must not complete a new record");
			}

			@Override
			public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
				return 0;
			}
		};
		TransactionRunner immediateTransactions = new TransactionRunner() {
			@Override
			public <T> T required(java.util.function.Supplier<T> action) {
				return action.get();
			}

			@Override
			public void required(Runnable action) {
				action.run();
			}
		};
		IdempotencyAnonymousSubjectHasher anonymousHasher = email -> IdempotencySubject.anonymous(
			new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		UnifiedIdempotencyService safeReplayService = new UnifiedIdempotencyService(
			immediateTransactions, safeReplayStore, anonymousHasher, Clock.systemUTC());
		PublicAuthController controller = new PublicAuthController(
			null, null, null, null, null, null, safeReplayService, null, null, Clock.systemUTC());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
		request.addHeader("Idempotency-Key", "safe-replay-key-" + UUID.randomUUID());
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setHeader("X-Request-ID", "safe-replay-test");

		var result = controller.registerUser(objectMapper.readTree(registerBody(uniqueEmail(), "123456", PASSWORD)), request, response);
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
		assertEquals("INTERNAL_ERROR", ((ProblemDetail) result.getBody()).getProperties().get("code"));
	}

	@Test
	void concurrentAnonymousRegisterSerializesOneBusinessWriteAndLoginRateLimitReturnsRetryAfter() throws Exception {
		String email = uniqueEmail();
		insertChallenge(email, EmailChallengePurpose.REGISTER, "123456");
		String key = "concurrent-key-" + UUID.randomUUID();
		String body = registerBody(email, "123456", PASSWORD);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
			for (int attempt = 0; attempt < 2; attempt++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return mvc.perform(post("/api/v1/auth/register")
							.contentType(MediaType.APPLICATION_JSON)
							.header("Idempotency-Key", key)
							.content(body))
						.andReturn().getResponse().getStatus();
				}));
			}
			ready.await();
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (java.util.concurrent.Future<Integer> future : futures) {
				statuses.add(future.get());
			}
			assertTrue(statuses.stream().allMatch(value -> value == 201 || value == 409));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email_normalized = ?", Integer.class, email));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", Integer.class, key));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ? "
				+ "AND status = 'PROCESSING'", Integer.class, key));
		} finally {
			executor.shutdownNow();
		}

		UserFixture user = seedUser();
		for (int attempt = 0; attempt < 31; attempt++) {
			transactionRunner.required(() -> rateLimitStore.consumeLogin(
				user.email(), SourceAddress.parseLiteral("127.0.0.1"), Instant.now()));
		}
		mvc.perform(post("/api/v1/auth/mobile/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(user.email(), "Rate", "rate-device")))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	private UserFixture seedUser() {
		UUID userId = UUID.randomUUID();
		String email = uniqueEmail();
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '认证 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), passwordHasher.hash(PASSWORD), now.toString(), now.toString());
		return new UserFixture(userId, email);
	}

	private SessionTokenResult createSession(UUID userId, String name, String deviceId) {
		return deviceSessionService.createForAuthenticatedUser(
			new app.ziji.auth.application.CreateDeviceSessionCommand(userId, name, deviceId));
	}

	private void insertChallenge(String email, EmailChallengePurpose purpose, String code) {
		String normalized = EmailAddress.normalize(email).value();
		Instant now = Instant.now();
		transactionRunner.required(() -> challengeStore.insert(EmailChallenge.issue(
			UUID.randomUUID(), purpose, normalized, challengeCodeHasher.hash(purpose, normalized, code), now)));
	}

	private static String loginBody(String email, String deviceName, String deviceId) {
		return loginBodyWithPassword(email, PASSWORD, deviceName, deviceId);
	}

	private static String loginBodyWithPassword(String email, String password, String deviceName, String deviceId) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password
			+ "\",\"deviceName\":\"" + deviceName + "\",\"deviceId\":\"" + deviceId + "\"}";
	}

	private static String registerBody(String email, String verificationCode, String password) {
		return "{\"email\":\"" + email + "\",\"verificationCode\":\"" + verificationCode
			+ "\",\"password\":\"" + password + "\",\"nickname\":\"认证用户\",\"timezone\":\"Asia/Shanghai\","
			+ "\"baseCurrency\":\"CNY\",\"locale\":\"zh-CN\"}";
	}

	private String uniqueEmail() {
		return "auth-http-" + UUID.randomUUID() + "@example.test";
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static String cookieValue(MvcResult result, String name) {
		for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
			if (header.startsWith(name + "=")) {
				int end = header.indexOf(';');
				return header.substring(name.length() + 1, end < 0 ? header.length() : end);
			}
		}
		return null;
	}

	private static void assertCookieAttributes(MvcResult result, String name, String requiredAttribute) {
		for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
			if (header.startsWith(name + "=")) {
				String expectedPath = "ziji_csrf".equals(name) ? "/" : "/api/v1";
				assertTrue(header.contains("Secure"));
				assertTrue(header.contains("SameSite=Strict"));
				assertTrue(header.contains("Path=" + expectedPath + ";"));
				assertTrue(header.contains(requiredAttribute));
				assertFalse(header.contains("Domain="));
				if ("ziji_csrf".equals(name)) {
					assertFalse(header.contains("HttpOnly"));
				}
				return;
			}
		}
		throw new AssertionError("missing cookie " + name);
	}

	private record UserFixture(UUID userId, String email) {
	}
}
