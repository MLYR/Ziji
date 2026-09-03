package app.ziji;

import java.time.Instant;
import java.util.UUID;

import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.shared.application.TransactionRunner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA-AUTH-001 金标准：同一用户经真实 SecurityFilterChain + PostgreSQL 走完整认证生命周期
 * （注册 → 双端登录 → 资料/设备读取 → Web 刷新轮换 → 旧 Token 重用撤销 → 重置密码 → 新旧密码登录 →
 * 跨用户设备 404 防枚举），并核对数据库事实终态与安全事件日志，证明 T-AUTH-001～007/T-SEC-001～003 的串联语义一致。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthLifecycleGoldenSamplePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final String PASSWORD = "correct-password-123";
	private static final String NEW_PASSWORD = "rotated-password-456";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PasswordHasher passwordHasher;

	@Autowired
	private ChallengeCodeHasher challengeCodeHasher;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

	@Autowired
	private TransactionRunner transactionRunner;

	private String email;

	@BeforeEach
	void clearGoldenFacts() {
		jdbc.execute("TRUNCATE TABLE session_refresh_tokens, user_sessions");
		jdbc.update("DELETE FROM email_challenges WHERE email_normalized LIKE 'golden-%@example.test'");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'golden-%@example.test'");
		email = "golden-" + UUID.randomUUID() + "@example.test";
	}

	@Test
	void registrationThroughResetLifecycleKeepsAuditableSecurityFacts(CapturedOutput output) throws Exception {
		// ① 注册：签发验证码后走公开注册接口，用户 ACTIVE 且邮箱已验证。
		insertChallenge(email, EmailChallengePurpose.REGISTER, "123456");
		mvc.perform(post("/api/v1/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", UUID.randomUUID())
				.content(registerBody(email, "123456", PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.email").value(email));
		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM users WHERE email_normalized = ?", String.class, EmailAddress.normalize(email).value()));
		assertNotNull(jdbc.queryForObject(
			"SELECT email_verified_at FROM users WHERE email_normalized = ?", java.sql.Timestamp.class,
			EmailAddress.normalize(email).value()));

		// ② Web 与 Mobile 双端登录：传输方式分离（Web Cookie / Mobile 响应体）。
		MvcResult webLogin = mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, "Browser", "golden-web-device")))
			.andExpect(status().isCreated())
			.andReturn();
		String webRefresh = cookieValue(webLogin, "ziji_refresh");
		String webCsrf = cookieValue(webLogin, "ziji_csrf");
		String webAccess = json(webLogin).at("/data/accessToken").asString();
		assertNotNull(webRefresh);
		assertNotNull(webCsrf);

		MvcResult mobileLogin = mvc.perform(post("/api/v1/auth/mobile/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, "Phone", "golden-mobile-device")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.tokens.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.data.tokens.refreshToken").isNotEmpty())
			.andReturn();
		assertNull(cookieValue(mobileLogin, "ziji_refresh"));
		assertNull(cookieValue(mobileLogin, "ziji_csrf"));

		// ③ 资料与设备列表：Bearer 主体可见本人两个设备。
		mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + webAccess))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("金标准用户"));
		mvc.perform(get("/api/v1/users/me/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + webAccess))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2));

		// ④ Web 刷新轮换：refresh/CSRF Cookie 均轮换，旧值不可再用于正常刷新。
		MvcResult rotated = mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh), new Cookie("ziji_csrf", webCsrf))
				.header("X-CSRF-Token", webCsrf))
			.andExpect(status().isOk())
			.andReturn();
		String rotatedRefresh = cookieValue(rotated, "ziji_refresh");
		assertFalse(webRefresh.equals(rotatedRefresh), "刷新后 Refresh Token 必须轮换");

		// ⑤ 旧 Refresh Token 重用：401 且所属会话被撤销（T-SEC-001 安全事件）。
		mvc.perform(post("/api/v1/auth/web/sessions/refresh")
				.cookie(new Cookie("ziji_refresh", webRefresh),
					new Cookie("ziji_csrf", cookieValue(rotated, "ziji_csrf")))
				.header("X-CSRF-Token", cookieValue(rotated, "ziji_csrf")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM user_sessions
			WHERE user_id = (SELECT id FROM users WHERE email_normalized = ?) AND revoked_at IS NOT NULL
			""", Integer.class, EmailAddress.normalize(email).value()));
		assertTrue(logs(output).contains("AUTH_SECURITY_EVENT action=REFRESH_TOKEN_REUSE_REVOKED"),
			"重用撤销必须记录安全事件日志");
		assertFalse(logs(output).contains(webRefresh), "安全事件日志不得包含 Token 或摘要");

		// ⑥ 重置密码：挑战一次性；全部会话 Token 失效；旧密码登录失败、新密码成功。
		insertChallenge(email, EmailChallengePurpose.RESET_PASSWORD, "654321");
		mvc.perform(post("/api/v1/auth/password-reset")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", UUID.randomUUID())
				.content("{\"email\":\"" + email + "\",\"challengeCode\":\"654321\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
			.andExpect(status().isNoContent());
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM session_refresh_tokens
			JOIN user_sessions ON user_sessions.id = session_refresh_tokens.session_id
			WHERE user_sessions.user_id = (SELECT id FROM users WHERE email_normalized = ?)
			  AND session_refresh_tokens.revoked_at IS NULL
			  AND session_refresh_tokens.consumed_at IS NULL
			""", Integer.class, EmailAddress.normalize(email).value()));
		mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBodyWithPassword(email, PASSWORD, "Browser", "golden-legacy-device")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBodyWithPassword(email, NEW_PASSWORD, "Browser", "golden-legacy-device")))
			.andExpect(status().isCreated());

		// ⑦ 跨用户防枚举：无关用户不能撤销或看到他人的会话（T-SEC-003）。
		String otherEmail = "golden-other-" + UUID.randomUUID() + "@example.test";
		seedUser(otherEmail);
		MvcResult otherLogin = mvc.perform(post("/api/v1/auth/web/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(otherEmail, "Browser", "golden-other-device")))
			.andExpect(status().isCreated())
			.andReturn();
		UUID holderSessionId = jdbc.queryForObject("""
			SELECT id FROM user_sessions
			WHERE user_id = (SELECT id FROM users WHERE email_normalized = ?)
			  AND revoked_at IS NULL
			LIMIT 1
			""", UUID.class, EmailAddress.normalize(email).value());
		mvc.perform(delete("/api/v1/users/me/sessions/" + holderSessionId)
				.header(HttpHeaders.AUTHORIZATION,
					"Bearer " + json(otherLogin).at("/data/accessToken").asString()))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/users/me/sessions")
				.header(HttpHeaders.AUTHORIZATION,
					"Bearer " + json(otherLogin).at("/data/accessToken").asString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[?(@.id == '" + holderSessionId + "')]").isEmpty());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE id = ? AND revoked_at IS NULL",
			Integer.class, holderSessionId), "他人会话不得被越权撤销");
	}

	private void insertChallenge(String challengeEmail, EmailChallengePurpose purpose, String code) {
		String normalized = EmailAddress.normalize(challengeEmail).value();
		Instant now = Instant.now();
		transactionRunner.required(() -> challengeStore.insert(EmailChallenge.issue(
			UUID.randomUUID(), purpose, normalized, challengeCodeHasher.hash(purpose, normalized, code), now)));
	}

	private UUID seedUser(String seedEmail) {
		UUID userId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, '无关用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, seedEmail, seedEmail, now.toString(),
			passwordHasher.hash(PASSWORD), now.toString(), now.toString());
		return userId;
	}

	private static String registerBody(String registerEmail, String code, String password) {
		return "{\"email\":\"" + registerEmail + "\",\"verificationCode\":\"" + code
			+ "\",\"password\":\"" + password + "\",\"nickname\":\"金标准用户\",\"timezone\":\"Asia/Shanghai\","
			+ "\"baseCurrency\":\"CNY\",\"locale\":\"zh-CN\"}";
	}

	private static String loginBody(String loginEmail, String deviceName, String deviceId) {
		return loginBodyWithPassword(loginEmail, PASSWORD, deviceName, deviceId);
	}

	private static String loginBodyWithPassword(String loginEmail, String password, String deviceName, String deviceId) {
		return "{\"email\":\"" + loginEmail + "\",\"password\":\"" + password
			+ "\",\"deviceName\":\"" + deviceName + "\",\"deviceId\":\"" + deviceId + "\"}";
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static String cookieValue(MvcResult result, String name) {
		MockHttpServletResponse response = result.getResponse();
		for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
			if (header.startsWith(name + "=")) {
				int end = header.indexOf(';');
				return header.substring(name.length() + 1, end < 0 ? header.length() : end);
			}
		}
		return null;
	}

	private String logs(CapturedOutput output) {
		return output.getOut() + output.getErr();
	}
}
