package app.ziji;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeIssueCommand;
import app.ziji.auth.application.EmailChallengeIssueResult;
import app.ziji.auth.application.EmailChallengeOutbox;
import app.ziji.auth.application.EmailChallengeVerificationCommand;
import app.ziji.auth.application.EmailChallengeVerificationResult;
import app.ziji.auth.domain.AuthRateLimitWindow;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.auth.infrastructure.AesGcmEnvelopeEncryptor;
import app.ziji.auth.infrastructure.AuthInfrastructureException;
import app.ziji.auth.infrastructure.EnvelopeKey;
import app.ziji.auth.infrastructure.HmacChallengeCodeHasher;
import app.ziji.auth.infrastructure.PostgresAuthRateLimitStore;
import app.ziji.auth.infrastructure.PostgresEmailChallengeOutbox;
import app.ziji.auth.infrastructure.PostgresEmailChallengeStore;
import app.ziji.shared.application.TransactionRunner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证挑战的真实 PostgreSQL 验收基线；验证跨实例共享事实、事务提交和行级并发语义。
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailChallengePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final SourceAddress SOURCE = SourceAddress.parseLiteral("192.0.2.10");
	private static final String EMAIL = "user@example.com";
	private static final byte[] TEST_KEK =
		"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private PostgresAuthRateLimitStore rateLimitStore;

	@Autowired
	private PostgresEmailChallengeStore challengeStore;

	@Autowired
	private PostgresEmailChallengeOutbox outbox;

	@Autowired
	private HmacChallengeCodeHasher codeHasher;

	@Autowired
	private AesGcmEnvelopeEncryptor envelopeEncryptor;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void clearAuthFacts() {
		// TRUNCATE 不触发 V008 的七天 DELETE 保留触发器，只清理本测试写入的认证事实。
		jdbc.update("TRUNCATE TABLE outbox_events, email_challenges, auth_rate_limit_buckets");
	}

	@Test
	void concurrentUpsertsNeverAllowMoreThanEmailQuota() throws Exception {
		EmailChallengeApplicationService service = serviceAt(NOW, outbox);

		List<EmailChallengeIssueResult> results = runConcurrently(8,
			() -> service.issue(issueCommand(EmailChallengePurpose.REGISTER, EMAIL, "device-1")));

		long accepted = results.stream().filter(EmailChallengeIssueResult::accepted).count();
		assertTrue(accepted <= 1);
		assertEquals(8, jdbc.queryForObject("""
			SELECT MAX(request_count)
			FROM auth_rate_limit_buckets
			WHERE purpose = 'REGISTER' AND dimension = 'EMAIL' AND window_code = 'EMAIL_60S'
			""", Integer.class));
	}

	@Test
	void rejectedRequestCommitsAllBucketCountersAndWritesNoSecondFact() {
		EmailChallengeApplicationService service = serviceAt(NOW, outbox);

		EmailChallengeIssueResult first = service.issue(
			issueCommand(EmailChallengePurpose.REGISTER, EMAIL, "device-1"));
		EmailChallengeIssueResult second = service.issue(
			issueCommand(EmailChallengePurpose.REGISTER, EMAIL, "device-1"));

		assertTrue(first.accepted());
		assertFalse(second.accepted());
		assertEquals(AuthRateLimitWindow.EMAIL_60S.retryAfterSeconds(NOW),
			second.retryAfterSeconds());
		assertEquals(14, jdbc.queryForObject(
			"SELECT COUNT(*) FROM auth_rate_limit_buckets", Integer.class));
		assertEquals(2, jdbc.queryForObject(
			"SELECT MIN(request_count) FROM auth_rate_limit_buckets", Integer.class));
		assertEquals(2, jdbc.queryForObject(
			"SELECT MAX(request_count) FROM auth_rate_limit_buckets", Integer.class));
		assertEquals(1, jdbc.queryForObject(
			"SELECT COUNT(*) FROM email_challenges", Integer.class));
		assertEquals(1, jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox_events", Integer.class));
	}

	@Test
	void registerAndResetPasswordUseSeparateBucketsAndChallenges() {
		EmailChallengeApplicationService service = serviceAt(NOW, outbox);

		assertTrue(service.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());
		assertTrue(service.issue(issueCommand(
			EmailChallengePurpose.RESET_PASSWORD, EMAIL, "device-1")).accepted());

		assertEquals(2, jdbc.queryForObject("""
			SELECT COUNT(*) FROM email_challenges
			WHERE email_normalized = ? AND invalidated_at IS NULL AND consumed_at IS NULL
			""", Integer.class, EMAIL));
		List<Map<String, Object>> registerEmailBuckets = jdbc.queryForList("""
			SELECT hash_key_version, request_count
			FROM auth_rate_limit_buckets
			WHERE purpose = 'REGISTER' AND dimension = 'EMAIL' AND window_code = 'EMAIL_60S'
			ORDER BY hash_key_version
			""");
		List<Map<String, Object>> resetEmailBuckets = jdbc.queryForList("""
			SELECT hash_key_version, request_count
			FROM auth_rate_limit_buckets
			WHERE purpose = 'RESET_PASSWORD' AND dimension = 'EMAIL' AND window_code = 'EMAIL_60S'
			ORDER BY hash_key_version
			""");

		// 双密钥轮换期间两个版本都计数，但 REGISTER 与 RESET_PASSWORD 仍保持用途隔离。
		assertEquals(2, registerEmailBuckets.size());
		assertEquals(List.of(1, 2), registerEmailBuckets.stream()
			.map(row -> ((Number) row.get("hash_key_version")).intValue()).toList());
		assertEquals(List.of(1, 1), registerEmailBuckets.stream()
			.map(row -> ((Number) row.get("request_count")).intValue()).toList());
		assertEquals(2, resetEmailBuckets.size());
		assertEquals(List.of(1, 2), resetEmailBuckets.stream()
			.map(row -> ((Number) row.get("hash_key_version")).intValue()).toList());
		assertEquals(List.of(1, 1), resetEmailBuckets.stream()
			.map(row -> ((Number) row.get("request_count")).intValue()).toList());
	}

	@Test
	void missingDeviceIdStillConsumesTheSharedIpDerivedDeviceQuota() {
		EmailChallengeIssueResult last = null;
		for (int request = 0; request <= 10; request++) {
			last = serviceAt(NOW, outbox).issue(issueCommand(
				EmailChallengePurpose.REGISTER, "user-" + request + "@example.com", null));
		}

		assertNotNull(last);
		assertFalse(last.accepted());
		assertEquals(AuthRateLimitWindow.DEVICE_1H.retryAfterSeconds(NOW),
			last.retryAfterSeconds());
		assertEquals(11, jdbc.queryForObject("""
			SELECT MAX(request_count) FROM auth_rate_limit_buckets
			WHERE purpose = 'REGISTER' AND dimension = 'DEVICE' AND window_code = 'DEVICE_1H'
			""", Integer.class));
	}

	@Test
	void successfulSecondIssueReplacesOldChallengeAtomicallyWithItsOutboxEvent() {
		EmailChallengeApplicationService firstService = serviceAt(NOW, outbox);
		assertTrue(firstService.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());
		UUID oldId = challengeId();

		EmailChallengeApplicationService secondService = serviceAt(NOW.plusSeconds(60), outbox);
		assertTrue(secondService.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());

		assertEquals("REPLACED", jdbc.queryForObject(
			"SELECT invalidation_reason FROM email_challenges WHERE id = ?", String.class, oldId));
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM email_challenges
			WHERE email_normalized = ? AND purpose = 'REGISTER'
			AND consumed_at IS NULL AND invalidated_at IS NULL
			""", Integer.class, EMAIL));
		assertEquals(2, jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox_events WHERE event_type = 'EmailChallengeIssued'", Integer.class));
	}

	@Test
	void outboxFailureRollsBackOldChallengeReplacementAndNewChallenge() {
		EmailChallengeApplicationService firstService = serviceAt(NOW, outbox);
		assertTrue(firstService.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());
		UUID oldId = challengeId();
		EmailChallengeOutbox failingOutbox = event -> {
			throw new AuthInfrastructureException("测试 outbox 写入失败。");
		};

		assertThrows(AuthInfrastructureException.class, () -> serviceAt(
			NOW.plusSeconds(60), failingOutbox).issue(
				issueCommand(EmailChallengePurpose.REGISTER, EMAIL, "device-1")));

		assertEquals(1, jdbc.queryForObject(
			"SELECT COUNT(*) FROM email_challenges", Integer.class));
		assertEquals("ACTIVE", jdbc.queryForObject(
			"SELECT CASE WHEN invalidated_at IS NULL THEN 'ACTIVE' ELSE invalidation_reason END "
			+ "FROM email_challenges WHERE id = ?", String.class, oldId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT COUNT(*) FROM outbox_events", Integer.class));
		assertEquals(1, jdbc.queryForObject("""
			SELECT request_count FROM auth_rate_limit_buckets
			WHERE purpose = 'REGISTER' AND dimension = 'EMAIL' AND window_code = 'EMAIL_60S'
			ORDER BY hash_key_version DESC LIMIT 1
			""", Integer.class));
	}

	@Test
	void concurrentVerificationConsumesChallengeOnlyOnce() throws Exception {
		EmailChallengeApplicationService service = serviceAt(NOW, outbox);
		assertTrue(service.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());

		List<EmailChallengeVerificationResult> results = runConcurrently(2,
			() -> service.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, EMAIL, "123456")));

		assertEquals(1, results.stream()
			.filter(result -> result == EmailChallengeVerificationResult.VALID).count());
		assertEquals(1, results.stream()
			.filter(result -> result == EmailChallengeVerificationResult.INVALID).count());
		assertEquals(1, jdbc.queryForObject("""
			SELECT COUNT(*) FROM email_challenges WHERE consumed_at IS NOT NULL
			""", Integer.class));
	}

	@Test
	void fifthWrongAttemptIsPersistedAsMaxAttempts() {
		EmailChallengeApplicationService service = serviceAt(NOW, outbox);
		assertTrue(service.issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());

		for (int attempt = 0; attempt < 5; attempt++) {
			assertEquals(EmailChallengeVerificationResult.INVALID, service.verify(
				new EmailChallengeVerificationCommand(
					EmailChallengePurpose.REGISTER, EMAIL, "999999")));
		}

		Map<String, Object> row = jdbc.queryForMap("""
			SELECT attempt_count, invalidation_reason FROM email_challenges
			WHERE email_normalized = ? AND purpose = 'REGISTER'
			""", EMAIL);
		assertEquals(5, row.get("attempt_count"));
		assertEquals("MAX_ATTEMPTS", row.get("invalidation_reason"));
	}

	@Test
	void expiredChallengeIsMarkedExpiredAtTenMinuteBoundary() {
		assertTrue(serviceAt(NOW, outbox).issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-1")).accepted());
		EmailChallengeApplicationService expiredService = serviceAt(NOW.plusSeconds(600), outbox);

		assertEquals(EmailChallengeVerificationResult.INVALID, expiredService.verify(
			new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, EMAIL, "123456")));
		assertEquals("EXPIRED", jdbc.queryForObject(
			"SELECT invalidation_reason FROM email_challenges", String.class));
	}

	@Test
	void retryAfterUsesLongestOverLimitWindow() {
		EmailChallengeIssueResult last = null;
		for (int request = 0; request <= 10; request++) {
			last = serviceAt(Instant.ofEpochSecond(request * 60L), outbox).issue(
				issueCommand(EmailChallengePurpose.REGISTER, EMAIL, "device-1"));
		}

		assertNotNull(last);
		assertFalse(last.accepted());
		// 第 11 次请求同时超过小时/日窗口，Retry-After 应取 24 小时窗口剩余的 85800 秒。
		assertEquals(85_800, last.retryAfterSeconds());
	}

	@Test
	void databaseStoresOnlyHashedSubjectsAndEncryptedVerificationCode() throws Exception {
		assertTrue(serviceAt(NOW, outbox).issue(issueCommand(
			EmailChallengePurpose.REGISTER, EMAIL, "device-secret")).accepted());

		String payload = jdbc.queryForObject(
			"SELECT payload::text FROM outbox_events", String.class);
		assertFalse(payload.contains("\"verificationCode\":\"123456\""));
		assertFalse(payload.contains("device-secret"));
		JsonNode root = objectMapper.readTree(payload);
		assertTrue(root.path("verificationCode").isObject());
		assertFalse(root.has("event"));
		UUID challengeId = jdbc.queryForObject(
			"SELECT aggregate_id FROM outbox_events", UUID.class);
		assertEquals("123456", decrypt(
			root.path("verificationCode"), new EnvelopeKey(1, TEST_KEK), challengeId,
			EmailChallengePurpose.REGISTER));

		List<byte[]> hashes = jdbc.query("""
			SELECT subject_hash FROM auth_rate_limit_buckets
			""", (resultSet, rowNumber) -> resultSet.getBytes(1));
		assertTrue(!hashes.isEmpty() && hashes.stream().allMatch(hash -> hash.length == 32));
		assertEquals(0, jdbc.queryForObject("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = 'auth_rate_limit_buckets'
			AND column_name IN ('email', 'ip', 'device_id')
			""", Integer.class));
	}

	private EmailChallengeApplicationService serviceAt(Instant now, EmailChallengeOutbox eventOutbox) {
		return new EmailChallengeApplicationService(
			transactionRunner, challengeStore, rateLimitStore, () -> "123456", codeHasher,
			envelopeEncryptor, eventOutbox, Clock.fixed(now, ZoneOffset.UTC), UUID::randomUUID);
	}

	private static EmailChallengeIssueCommand issueCommand(
		EmailChallengePurpose purpose,
		String email,
		String deviceId) {
		return new EmailChallengeIssueCommand(purpose, email, deviceId, SOURCE);
	}

	private UUID challengeId() {
		return jdbc.queryForObject("SELECT id FROM email_challenges", UUID.class);
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

	private static String decrypt(
		JsonNode envelope,
		EnvelopeKey key,
		UUID challengeId,
		EmailChallengePurpose purpose) throws Exception {
		Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
		unwrap.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.secretCopy(), "AES"),
			new GCMParameterSpec(128, decode(envelope.path("wrappedDataKeyNonce").asText())));
		unwrap.updateAAD(encode("ziji-email-challenge-kek-v1",
			ByteBuffer.allocate(Integer.BYTES).putInt(key.version()).array()));
		byte[] dataKey = unwrap.doFinal(decode(envelope.path("wrappedDataKey").asText()));

		Cipher payload = Cipher.getInstance("AES/GCM/NoPadding");
		payload.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
			new GCMParameterSpec(128, decode(envelope.path("nonce").asText())));
		payload.updateAAD(encode("ziji-email-challenge-envelope-v1", uuidBytes(challengeId),
			purpose.name().getBytes(StandardCharsets.UTF_8)));
		return new String(payload.doFinal(decode(envelope.path("ciphertext").asText())),
			StandardCharsets.UTF_8);
	}

	private static byte[] decode(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	private static byte[] uuidBytes(UUID value) {
		return ByteBuffer.allocate(Long.BYTES * 2)
			.putLong(value.getMostSignificantBits())
			.putLong(value.getLeastSignificantBits())
			.array();
	}

	private static byte[] encode(String domain, byte[]... parts) {
		byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
		int length = Integer.BYTES + domainBytes.length;
		for (byte[] part : parts) {
			length += Integer.BYTES + part.length;
		}
		ByteBuffer buffer = ByteBuffer.allocate(length)
			.putInt(domainBytes.length)
			.put(domainBytes);
		for (byte[] part : parts) {
			buffer.putInt(part.length).put(part);
		}
		return buffer.array();
	}
}
