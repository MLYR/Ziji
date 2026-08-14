package app.ziji;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;

import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.AuthRateLimitSubjects;
import app.ziji.auth.application.InvalidCredentialsException;
import app.ziji.auth.application.LoginRateLimitedException;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordLoginApplicationService;
import app.ziji.auth.application.PasswordLoginCommand;
import app.ziji.auth.application.PasswordLoginResult;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.LoginRateLimitWindow;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredentialLookupPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码登录真实 PostgreSQL 验收基线：凭据查询只返回安全字段、四状态与不存在邮箱、登录四桶与作用域隔离、
 * 登录 HMAC 独立域、密钥轮换、拒绝计数提交、最长 Retry-After 与并发不丢计数，以及不写 Token/Cookie/失败计数。
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordLoginPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final SourceAddress SOURCE = SourceAddress.parseLiteral("192.0.2.40");
	private static final String EMAIL = "login-user@example.test";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private AuthRateLimitStore rateLimitStore;

	@Autowired
	private UserCredentialLookupPort credentialLookupPort;

	@Autowired
	private PasswordHasher passwordHasher;

	@BeforeEach
	void clearLoginFacts() {
		// TRUNCATE 不触发 V008 的七天 DELETE 保留触发器，只清理本测试写入的限流事实。
		jdbc.update("TRUNCATE TABLE auth_rate_limit_buckets");
		jdbc.update("DELETE FROM users WHERE email_normalized LIKE 'login-%@example.test'");
	}

	@Test
	void credentialLookupReturnsOnlySafeAuthenticationFields() {
		seedUser("login-active@example.test", "ACTIVE", hash("Correct-Pass-1"), 1);

		PasswordLoginResult result = realService().login(command("login-active@example.test", "Correct-Pass-1"));

		assertEquals("ACTIVE", result.status().name());
		// 公开 DTO 只暴露 userId、passwordHash、passwordHashVersion、status 四个字段。
		assertEquals(List.of("passwordHash", "passwordHashVersion", "status", "userId"),
			Arrays.stream(app.ziji.user.application.UserCredential.class.getDeclaredFields())
				.map(field -> field.getName())
				.sorted()
				.toList());
	}

	@Test
	void activeAndClosingAuthenticateWhileLockedClosedAndAbsentFailUniformly() {
		seedUser("login-active@example.test", "ACTIVE", hash("Pass-Active-1"), 1);
		seedUser("login-closing@example.test", "CLOSING", hash("Pass-Closing-1"), 1);
		seedUser("login-locked@example.test", "LOCKED", hash("Pass-Locked-1"), 1);
		seedUser("login-closed@example.test", "CLOSED", hash("Pass-Closed-1"), 1);

		PasswordLoginApplicationService service = realService();

		assertEquals("ACTIVE", service.login(command("login-active@example.test", "Pass-Active-1")).status().name());
		assertEquals("CLOSING", service.login(command("login-closing@example.test", "Pass-Closing-1")).status().name());

		InvalidCredentialsException locked = assertThrows(InvalidCredentialsException.class,
			() -> service.login(command("login-locked@example.test", "Pass-Locked-1")));
		InvalidCredentialsException closed = assertThrows(InvalidCredentialsException.class,
			() -> service.login(command("login-closed@example.test", "Pass-Closed-1")));
		InvalidCredentialsException absent = assertThrows(InvalidCredentialsException.class,
			() -> service.login(command("login-absent@example.test", "Any-Pass-1")));

		assertEquals("INVALID_CREDENTIALS", locked.code());
		assertEquals("INVALID_CREDENTIALS", closed.code());
		assertEquals("INVALID_CREDENTIALS", absent.code());
		assertEquals(locked.getMessage(), closed.getMessage());
		assertEquals(locked.getMessage(), absent.getMessage());
	}

	@Test
	void loginWritesFourLoginBucketsUnderLoginScopeWithoutDeviceBucket() {
		PasswordLoginApplicationService service = noopService();
		assertThrows(InvalidCredentialsException.class,
			() -> service.login(command(EMAIL, "Any-Pass-1")));

		List<Map<String, Object>> buckets = jdbc.queryForList("""
			SELECT DISTINCT action, purpose, policy_code, dimension, window_code
			FROM auth_rate_limit_buckets
			ORDER BY window_code
			""");
		List<String> windowCodes = buckets.stream().map(row -> (String) row.get("window_code")).toList();
		assertEquals(List.of("LOGIN_EMAIL_15M", "LOGIN_EMAIL_24H", "LOGIN_IP_10M", "LOGIN_IP_24H"), windowCodes);
		for (Map<String, Object> row : buckets) {
			assertEquals("LOGIN_PASSWORD", row.get("action"));
			assertEquals("LOGIN", row.get("purpose"));
			assertEquals("AUTH_LOGIN_V1", row.get("policy_code"));
			assertNotEquals("DEVICE", row.get("dimension"));
		}
		assertEquals(8, jdbc.queryForObject(
			"SELECT COUNT(*) FROM auth_rate_limit_buckets", Integer.class));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM auth_rate_limit_buckets WHERE dimension = 'DEVICE'
			""", Integer.class));
		assertTrue(jdbc.query("SELECT subject_hash FROM auth_rate_limit_buckets",
			(rs, i) -> rs.getBytes(1)).stream().allMatch(hash -> hash.length == 32));
	}

	@Test
	void loginHmacDomainDiffersFromChallengeDomain() {
		AuthRateLimitSubjects subjects = AuthRateLimitSubjects.of(EMAIL, null, SOURCE);
		transactionRunner.required(() -> {
			rateLimitStore.consume(EmailChallengePurpose.REGISTER, subjects, NOW);
			rateLimitStore.consumeLogin(EMAIL, SOURCE, NOW);
		});

		byte[] challengeEmailHash = jdbc.queryForObject("""
			SELECT subject_hash FROM auth_rate_limit_buckets
			WHERE action = 'SEND_EMAIL_CHALLENGE' AND purpose = 'REGISTER'
				AND dimension = 'EMAIL' AND window_code = 'EMAIL_60S' AND hash_key_version = 2
			""", (rs, i) -> rs.getBytes(1));
		byte[] loginEmailHash = jdbc.queryForObject("""
			SELECT subject_hash FROM auth_rate_limit_buckets
			WHERE action = 'LOGIN_PASSWORD' AND purpose = 'LOGIN'
				AND dimension = 'EMAIL' AND window_code = 'LOGIN_EMAIL_15M' AND hash_key_version = 2
			""", (rs, i) -> rs.getBytes(1));

		assertFalse(Arrays.equals(challengeEmailHash, loginEmailHash));
	}

	@Test
	void deniedLoginCommitsCountsAndReturnsLongestRetryAfterAcrossExceededWindows() {
		PasswordLoginApplicationService service = noopService();

		LoginRateLimitedException denied = null;
		for (int request = 0; request < 31; request++) {
			try {
				service.login(command(EMAIL, "Any-Pass-1"));
			} catch (InvalidCredentialsException expectedBeforeLimit) {
				// 前 30 次通过限流后因账号不存在失败；第 31 次应由限流拒绝。
			} catch (LoginRateLimitedException rateLimited) {
				denied = rateLimited;
			}
		}

		assertFalse(denied == null);
		int expected = Math.max(
			LoginRateLimitWindow.IP_10M.retryAfterSeconds(NOW),
			LoginRateLimitWindow.EMAIL_15M.retryAfterSeconds(NOW));
		assertEquals(expected, denied.retryAfterSeconds());
		// 被拒绝请求同样提交计数：IP 短窗与 EMAIL 短窗都累计到 31。
		assertEquals(31, bucketCount("LOGIN_IP_10M", "IP"));
		assertEquals(31, bucketCount("LOGIN_EMAIL_15M", "EMAIL"));
	}

	@Test
	void keyRotationWritesBothCurrentAndPreviousKeyVersions() {
		PasswordLoginApplicationService service = noopService();
		assertThrows(InvalidCredentialsException.class,
			() -> service.login(command(EMAIL, "Any-Pass-1")));

		List<Integer> versions = jdbc.queryForList("""
			SELECT DISTINCT hash_key_version FROM auth_rate_limit_buckets ORDER BY hash_key_version
			""", Integer.class);
		assertEquals(List.of(1, 2), versions);
	}

	@Test
	void concurrentLoginRateLimitDoesNotLoseCounts() throws Exception {
		PasswordLoginApplicationService service = noopService();

		List<Boolean> results = runConcurrently(8, () -> {
			try {
				service.login(command(EMAIL, "Any-Pass-1"));
				return true;
			} catch (InvalidCredentialsException expected) {
				return false;
			}
		});

		assertEquals(8, results.size());
		assertEquals(8, bucketCount("LOGIN_EMAIL_15M", "EMAIL"));
		assertEquals(8, bucketCount("LOGIN_IP_10M", "IP"));
	}

	@Test
	void loginDoesNotModifyUserStatusOrWriteTokenCookieOrFailureFacts() {
		seedUser(EMAIL, "ACTIVE", hash("Correct-Pass-1"), 1);

		assertThrows(InvalidCredentialsException.class,
			() -> realService().login(command(EMAIL, "Wrong-Pass-9")));

		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT status FROM users WHERE email_normalized = ?", String.class, EMAIL));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM session_refresh_tokens", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = 'users'
			AND column_name IN ('login_failed_count', 'failed_attempts', 'locked_at')
			""", Integer.class));
	}

	private int bucketCount(String windowCode, String dimension) {
		return jdbc.queryForObject("""
			SELECT MAX(request_count) FROM auth_rate_limit_buckets
			WHERE window_code = ? AND dimension = ?
			""", Integer.class, windowCode, dimension);
	}

	private PasswordLoginApplicationService realService() {
		return new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher);
	}

	private PasswordLoginApplicationService noopService() {
		return new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, noopHasher());
	}

	private static PasswordHasher noopHasher() {
		return new PasswordHasher() {
			@Override
			public String hash(String password) {
				return "$argon2id$fake$dummy";
			}

			@Override
			public boolean supports(int hashVersion, String encodedHash) {
				return hashVersion == 1 && "$argon2id$fake$dummy".equals(encodedHash);
			}

			@Override
			public boolean matches(String password, String encodedHash) {
				return false;
			}
		};
	}

	private void seedUser(String email, String status, String passwordHash, int hashVersion) {
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), ?, ?, '登录测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""",
			UUID.randomUUID(), email, email, NOW.toString(), passwordHash, hashVersion,
			status, NOW.toString(), NOW.toString());
	}

	private String hash(String password) {
		return passwordHasher.hash(password);
	}

	private static PasswordLoginCommand command(String email, String password) {
		return new PasswordLoginCommand(email, password, SOURCE, NOW);
	}

	private static <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			List<java.util.concurrent.Future<T>> futures = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				futures.add(executor.submit(task));
			}
			List<T> results = new ArrayList<>();
			for (java.util.concurrent.Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}
}
