package app.ziji;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.ziji.auth.infrastructure.AuthHmacKey;
import app.ziji.auth.infrastructure.HmacIdempotencyAnonymousSubjectHasher;
import app.ziji.auth.infrastructure.IdempotencyHmacKeyRing;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyInfrastructureException;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V009 真实 PostgreSQL 验收：事务、两实例行锁、租约恢复、失败重放与安全清理均不能由内存锁替代。 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	// 使用对齐 PostgreSQL 微秒精度的类加载时钟，避免绝对日期和边界舍入污染共享清理夹具。
	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionRunner transactionRunner;

	@Autowired
	private IdempotencyRecordStore recordStore;

	@Autowired
	private IdempotencyAnonymousSubjectHasher anonymousSubjectHasher;

	@AfterEach
	void removeFailureTrigger() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_idempotency_completion_for_test ON idempotency_records");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_idempotency_completion_for_test()");
	}

	@Test
	void oneTwoAndTenSameKeyRetriesExecuteOnceAndScopeDimensionsRemainIsolated() {
		UUID firstUser = seedUser("retry-first");
		UUID secondUser = seedUser("retry-second");
		String key = key();
		AtomicInteger workCalls = new AtomicInteger();
		UnifiedIdempotencyService service = serviceAt(NOW);

		IdempotencyExecution<String> first = service.executeAuthenticated(
			firstUser, 1, "postTransaction", key, hash('a'), () -> completed(workCalls));
		assertEquals(IdempotencyExecution.Status.EXECUTED, first.status());
		for (int retry = 0; retry < 9; retry++) {
			IdempotencyExecution<String> replay = service.executeAuthenticated(
				firstUser, 1, "postTransaction", key, hash('a'), () -> completed(workCalls));
			assertEquals(IdempotencyExecution.Status.REPLAYED, replay.status());
			assertEquals(201, replay.response().responseStatus());
		}

		assertEquals(1, workCalls.get());
		assertEquals(1, count("""
			SELECT COUNT(*) FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'postTransaction' AND idempotency_key = ?
			""", firstUser, key));
		assertEquals(IdempotencyExecution.Status.EXECUTED, service.executeAuthenticated(
			secondUser, 1, "postTransaction", key, hash('a'), () -> completed(new AtomicInteger())).status());
		assertEquals(IdempotencyExecution.Status.EXECUTED, service.executeAuthenticated(
			firstUser, 2, "postTransaction", key, hash('a'), () -> completed(new AtomicInteger())).status());
		assertEquals(IdempotencyExecution.Status.EXECUTED, service.executeAuthenticated(
			firstUser, 1, "reverseTransaction", key, hash('a'), () -> completed(new AtomicInteger())).status());
		assertEquals(IdempotencyExecution.Status.KEY_REUSED, service.executeAuthenticated(
			firstUser, 1, "postTransaction", key, hash('b'), () -> completed(new AtomicInteger())).status());
		assertEquals(4, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", key));
	}

	@Test
	void anonymousEmailUsesCurrentAndPreviousHmacVersionsWithoutCreatingASecondRecordAfterRotation() {
		String key = key();
		AtomicInteger workCalls = new AtomicInteger();
		IdempotencyAnonymousSubjectHasher beforeRotation = hasher(2, testCurrentKey(), 1, testPreviousKey());
		IdempotencyAnonymousSubjectHasher afterRotation = hasher(3, key((byte) 3), 2, testCurrentKey());

		IdempotencyExecution<String> initial = serviceAt(NOW, beforeRotation).executeAnonymous(
			"  ＵＳＥＲ@EXAMPLE.TEST ", 1, "registerUser", key, hash('c'), () -> completed(workCalls));
		IdempotencyExecution<String> replay = serviceAt(NOW.plusSeconds(1), afterRotation).executeAnonymous(
			"user@example.test", 1, "registerUser", key, hash('c'), () -> completed(workCalls));

		assertEquals(IdempotencyExecution.Status.EXECUTED, initial.status());
		assertEquals(IdempotencyExecution.Status.REPLAYED, replay.status());
		assertEquals(1, workCalls.get());
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", key));
		assertEquals(2, jdbc.queryForObject("""
			SELECT anonymous_subject_hash_key_version FROM idempotency_records WHERE idempotency_key = ?
			""", Integer.class, key));
		assertFalse(replay.toString().contains("user@example.test"));
	}

	@Test
	void processingLeaseRetryableBackoffAndFinalFailureFollowTheFrozenLifecycle() {
		UUID userId = seedUser("lifecycle");
		String processingKey = key();
		insertProcessing(UUID.randomUUID(), userId, processingKey, hash('d'), NOW, NOW.plusSeconds(30));
		assertEquals(IdempotencyExecution.Status.REQUEST_IN_PROGRESS, serviceAt(NOW).executeAuthenticated(
			userId, 1, "postTransaction", processingKey, hash('d'), () -> completed(new AtomicInteger())).status());
		assertEquals(IdempotencyExecution.Status.EXECUTED, serviceAt(NOW.plusSeconds(30)).executeAuthenticated(
			userId, 1, "postTransaction", processingKey, hash('d'), () -> completed(new AtomicInteger())).status());

		String retryableKey = key();
		insertRetryable(UUID.randomUUID(), userId, retryableKey, hash('e'), NOW);
		assertEquals(IdempotencyExecution.Status.REQUEST_IN_PROGRESS, serviceAt(NOW.plusSeconds(4)).executeAuthenticated(
			userId, 1, "postTransaction", retryableKey, hash('e'), () -> completed(new AtomicInteger())).status());
		assertEquals(IdempotencyExecution.Status.EXECUTED, serviceAt(NOW.plusSeconds(5)).executeAuthenticated(
			userId, 1, "postTransaction", retryableKey, hash('e'), () -> completed(new AtomicInteger())).status());

		String finalKey = key();
		IdempotencyExecution<String> finalFailure = serviceAt(NOW).executeAuthenticated(
			userId, 1, "postTransaction", finalKey, hash('f'),
			() -> IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinal(422, "BUSINESS_RULE")));
		IdempotencyExecution<String> finalReplay = serviceAt(NOW.plusSeconds(1)).executeAuthenticated(
			userId, 1, "postTransaction", finalKey, hash('f'), () -> completed(new AtomicInteger()));
		assertEquals(IdempotencyExecution.Status.EXECUTED, finalFailure.status());
		assertEquals(IdempotencyExecution.Status.REPLAYED, finalReplay.status());
		assertEquals(IdempotencyResponse.Status.FAILED_FINAL, finalReplay.response().status());
	}

	@Test
	void versionConflictReferenceIsPersistedAndReplayedExactlyWithoutCurrentLookup() {
		UUID userId = seedUser("version-conflict");
		String key = key();
		String location = "/api/v1/transactions/4f6ba6c8-0a3c-4bd2-9313-d11850b3f73f";
		AtomicInteger workCalls = new AtomicInteger();

		IdempotencyExecution<String> initial = serviceAt(NOW).executeAuthenticated(
			userId, 1, "applySyncOperations", key, hash('a'), () -> {
				workCalls.incrementAndGet();
				return IdempotencyWorkResult.completed(null,
					IdempotencyResponse.failedFinalVersionConflict(409, 7, location));
			});
		IdempotencyExecution<String> replay = serviceAt(NOW.plusSeconds(1)).executeAuthenticated(
			userId, 1, "applySyncOperations", key, hash('a'), () -> completed(workCalls));

		assertEquals(IdempotencyExecution.Status.EXECUTED, initial.status());
		assertEquals(IdempotencyExecution.Status.REPLAYED, replay.status());
		assertEquals(1, workCalls.get());
		IdempotencyResponse.VersionConflictReference conflict = assertInstanceOf(
			IdempotencyResponse.VersionConflictReference.class, replay.response().reference());
		assertEquals(7, conflict.currentVersion());
		assertEquals("\"7\"", conflict.currentEtag());
		assertEquals(location, conflict.resourceLocation());
		assertEquals(IdempotencyExecution.Status.KEY_REUSED, serviceAt(NOW.plusSeconds(2)).executeAuthenticated(
			userId, 1, "applySyncOperations", key, hash('b'), () -> completed(workCalls)).status());
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", key));
	}

	@Test
	void twoIndependentServiceInstancesSerializeTheSameRequestAndLeaveOneTerminalRecord() throws Exception {
		UUID userId = seedUser("two-instance");
		String key = key();
		AtomicInteger workCalls = new AtomicInteger();
		CountDownLatch firstEnteredWork = new CountDownLatch(1);
		CountDownLatch allowFirstCompletion = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<IdempotencyExecution<String>> first = executor.submit(() -> serviceAt(NOW).executeAuthenticated(
				userId, 1, "postTransaction", key, hash('a'), () -> {
					workCalls.incrementAndGet();
					firstEnteredWork.countDown();
					await(allowFirstCompletion);
					return IdempotencyWorkResult.completed("created", IdempotencyResponse.succeededEmpty(201));
				}));
			assertTrue(firstEnteredWork.await(5, TimeUnit.SECONDS));
			Future<IdempotencyExecution<String>> second = executor.submit(() -> {
				secondStarted.countDown();
				return serviceAt(NOW).executeAuthenticated(
					userId, 1, "postTransaction", key, hash('a'), () -> completed(workCalls));
			});
			assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
			allowFirstCompletion.countDown();

			assertEquals(IdempotencyExecution.Status.EXECUTED, first.get(5, TimeUnit.SECONDS).status());
			assertEquals(IdempotencyExecution.Status.REPLAYED, second.get(5, TimeUnit.SECONDS).status());
		} finally {
			allowFirstCompletion.countDown();
			executor.shutdownNow();
		}
		assertEquals(1, workCalls.get());
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", key));
	}

	@Test
	void rowLockWaitsAtMostFiveSecondsAndReturnsInProgressWithoutParallelWork() throws Exception {
		UUID userId = seedUser("lock-timeout");
		String key = key();
		CountDownLatch firstEnteredWork = new CountDownLatch(1);
		CountDownLatch allowFirstCompletion = new CountDownLatch(1);
		AtomicInteger workCalls = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<IdempotencyExecution<String>> first = executor.submit(() -> serviceAt(NOW).executeAuthenticated(
				userId, 1, "postTransaction", key, hash('b'), () -> {
					workCalls.incrementAndGet();
					firstEnteredWork.countDown();
					await(allowFirstCompletion);
					return IdempotencyWorkResult.completed("created", IdempotencyResponse.succeededEmpty(201));
				}));
			assertTrue(firstEnteredWork.await(5, TimeUnit.SECONDS));
			Future<IdempotencyExecution<String>> second = executor.submit(() -> serviceAt(NOW).executeAuthenticated(
				userId, 1, "postTransaction", key, hash('b'), () -> completed(workCalls)));

			Instant waitStartedAt = Instant.now();
			IdempotencyExecution<String> waiting = second.get(8, TimeUnit.SECONDS);
			assertTrue(Duration.between(waitStartedAt, Instant.now()).compareTo(Duration.ofSeconds(7)) < 0);
			assertEquals(IdempotencyExecution.Status.REQUEST_IN_PROGRESS, waiting.status());
			assertEquals(5, waiting.retryAfterSeconds());
			assertEquals(1, workCalls.get());
			allowFirstCompletion.countDown();
			assertEquals(IdempotencyExecution.Status.EXECUTED, first.get(5, TimeUnit.SECONDS).status());
		} finally {
			allowFirstCompletion.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void businessAndDatabaseFailuresRollbackProcessingAndDoNotLeaveOrphanRecords() {
		UUID userId = seedUser("rollback");
		String workFailureKey = key();
		String transientEmail = "idempotency-rollback-" + UUID.randomUUID() + "@example.test";
		assertThrows(IllegalStateException.class, () -> serviceAt(NOW).executeAuthenticated(
			userId, 1, "postTransaction", workFailureKey, hash('c'), () -> {
				insertUser(UUID.randomUUID(), transientEmail, NOW);
				throw new IllegalStateException("planned rollback");
			}));
		assertEquals(0, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", workFailureKey));
		assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email_normalized = ?", transientEmail));

		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_idempotency_completion_for_test()
			RETURNS trigger LANGUAGE plpgsql AS $$
			BEGIN
				RAISE EXCEPTION 'test-only idempotency completion failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_idempotency_completion_for_test
			BEFORE UPDATE ON idempotency_records
			FOR EACH ROW EXECUTE FUNCTION reject_idempotency_completion_for_test()
			""");
		String databaseFailureKey = key();
		assertThrows(IdempotencyInfrastructureException.class, () -> serviceAt(NOW).executeAuthenticated(
			userId, 1, "postTransaction", databaseFailureKey, hash('d'), () -> completed(new AtomicInteger())));
		assertEquals(0, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", databaseFailureKey));
	}

	@Test
	void cleanupDeletesOnlyExpiredUnreferencedTerminalRecordsAndLeavesBusinessReferencesIntact() {
		Instant createdAt = Instant.now().minus(Duration.ofDays(8));
		UUID userId = seedUser("cleanup");
		UnifiedIdempotencyService oldService = serviceAt(createdAt);
		// 11 条独立候选锁定批量上限：第一次只删 10 条，第二次再删剩余 1 条。
		String[] eligibleKeys = new String[11];
		// 请求 Hash 使用合法十六进制样本，候选由随机幂等 Key 隔离。
		for (int index = 0; index < eligibleKeys.length; index++) {
			eligibleKeys[index] = key();
			String eligibleKey = eligibleKeys[index];
			oldService.executeAuthenticated(userId, 1, "postTransaction", eligibleKey,
				hash('e'), () -> completed(new AtomicInteger()));
		}

		String transactionKey = key();
		oldService.executeAuthenticated(userId, 1, "postTransaction", transactionKey, hash('f'), () -> completed(new AtomicInteger()));
		UUID transactionRecordId = recordId(userId, transactionKey);
		insertTransactionReference(userId, transactionRecordId, createdAt);

		String syncKey = key();
		oldService.executeAuthenticated(userId, 1, "postTransaction", syncKey, hash('a'), () -> completed(new AtomicInteger()));
		UUID syncRecordId = recordId(userId, syncKey);
		insertSyncReference(userId, syncRecordId, createdAt);

		// 负向样本覆盖到期 PROCESSING 与未到期终态，避免共享容器导致的计数修正削弱清理资格证据。
		String unexpiredKey = key();
		serviceAt(Instant.now()).executeAuthenticated(userId, 1, "postTransaction", unexpiredKey, hash('b'),
			() -> completed(new AtomicInteger()));
		String processingKey = key();
		insertProcessing(UUID.randomUUID(), userId, processingKey, hash('f'), createdAt, createdAt.plusSeconds(30));

		int deleted = serviceAt(Instant.now()).deleteExpiredTerminalRecords(10);
		assertEquals(10, deleted);
		int remainingEligible = 0;
		for (String eligibleKey : eligibleKeys) {
			remainingEligible += count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", eligibleKey);
		}
		assertEquals(1, remainingEligible);
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE id = ?", transactionRecordId));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE id = ?", syncRecordId));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", unexpiredKey));
		assertEquals(1, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", processingKey));

		int remainderDeleted = serviceAt(Instant.now()).deleteExpiredTerminalRecords(10);
		assertEquals(1, remainderDeleted);
		for (String eligibleKey : eligibleKeys) {
			assertEquals(0, count("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", eligibleKey));
		}

		assertEquals(IdempotencyExecution.Status.EXECUTED, serviceAt(Instant.now()).executeAuthenticated(
			userId, 1, "postTransaction", eligibleKeys[0], hash('b'), () -> completed(new AtomicInteger())).status());
	}

	private UnifiedIdempotencyService serviceAt(Instant now) {
		return serviceAt(now, anonymousSubjectHasher);
	}

	private UnifiedIdempotencyService serviceAt(Instant now, IdempotencyAnonymousSubjectHasher hasher) {
		return new UnifiedIdempotencyService(
			transactionRunner, recordStore, hasher, Clock.fixed(now, ZoneOffset.UTC));
	}

	private UUID seedUser(String suffix) {
		UUID id = UUID.randomUUID();
		insertUser(id, "idempotency-" + suffix + "-" + id + "@example.test", NOW);
		return id;
	}

	private void insertUser(UUID id, String email, Instant now) {
		jdbc.update("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, CAST(? AS timestamptz), '$argon2id$test', 1, '幂等测试',
				'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', CAST(? AS timestamptz),
				CAST(? AS timestamptz), 1)
			""", id, email, email, now.toString(), now.toString(), now.toString());
	}

	private void insertProcessing(UUID id, UUID userId, String key, String requestHash, Instant startedAt, Instant leaseExpiresAt) {
		jdbc.update("""
			INSERT INTO idempotency_records (
				id, user_id, api_major_version, operation_id, idempotency_key, request_hash, status,
				response_status, response_reference, resource_type, resource_id, created_at, completed_at,
				processing_started_at, processing_lease_expires_at, retry_after_at, expires_at
			) VALUES (?, ?, 1, 'postTransaction', ?, ?, 'PROCESSING', NULL, NULL, NULL, NULL,
				CAST(? AS timestamptz), NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL,
				CAST(? AS timestamptz))
			""", id, userId, key, requestHash, startedAt.toString(), startedAt.toString(), leaseExpiresAt.toString(),
			startedAt.plus(Duration.ofDays(7)).toString());
	}

	private void insertRetryable(UUID id, UUID userId, String key, String requestHash, Instant completedAt) {
		jdbc.update("""
			INSERT INTO idempotency_records (
				id, user_id, api_major_version, operation_id, idempotency_key, request_hash, status,
				response_status, response_reference, resource_type, resource_id, created_at, completed_at,
				processing_started_at, processing_lease_expires_at, retry_after_at, expires_at
			) VALUES (?, ?, 1, 'postTransaction', ?, ?, 'FAILED_RETRYABLE', 503,
				CAST('{"kind":"PROBLEM","errorCode":"TEMPORARY_FAILURE","retryAfterSeconds":5}' AS jsonb),
				NULL, NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL,
				CAST(? AS timestamptz), CAST(? AS timestamptz))
			""", id, userId, key, requestHash, completedAt.toString(), completedAt.toString(),
			completedAt.plusSeconds(5).toString(), completedAt.plus(Duration.ofDays(7)).toString());
	}

	private void insertTransactionReference(UUID userId, UUID idempotencyRecordId, Instant now) {
		UUID transactionId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO transactions (
				id, transaction_type, status, business_at, business_date, timezone, source,
				idempotency_record_id, root_transaction_id, version_no, created_by, updated_by, created_at, updated_at
			) VALUES (?, 'EXPENSE', 'DRAFT', CAST(? AS timestamptz), ?, 'Asia/Shanghai', 'MANUAL',
				?, ?, 1, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz))
			""", transactionId, now.toString(), java.sql.Date.valueOf(now.atOffset(ZoneOffset.UTC).toLocalDate()),
			idempotencyRecordId, transactionId, userId, userId, now.toString(), now.toString());
	}

	private void insertSyncReference(UUID userId, UUID idempotencyRecordId, Instant now) {
		jdbc.update("""
			INSERT INTO sync_operations (
				id, user_id, device_id, idempotency_record_id, entity_type, entity_id,
				operation_type, status, processed_at
			) VALUES (?, ?, 'idempotency-test-device', ?, 'TRANSACTION', ?, 'CREATE', 'APPLIED',
				CAST(? AS timestamptz))
			""", UUID.randomUUID(), userId, idempotencyRecordId, UUID.randomUUID(), now.toString());
	}

	private UUID recordId(UUID userId, String key) {
		return jdbc.queryForObject("""
			SELECT id FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?
			""", UUID.class, userId, key);
	}

	private int count(String sql, Object... values) {
		return jdbc.queryForObject(sql, Integer.class, values);
	}

	private static IdempotencyWorkResult<String> completed(AtomicInteger workCalls) {
		workCalls.incrementAndGet();
		return IdempotencyWorkResult.completed("created", IdempotencyResponse.succeededEmpty(201));
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("测试等待被中断。", exception);
		}
	}

	private static IdempotencyAnonymousSubjectHasher hasher(
		int currentVersion,
		byte[] currentKey,
		int previousVersion,
		byte[] previousKey) {
		return new HmacIdempotencyAnonymousSubjectHasher(new IdempotencyHmacKeyRing(
			new AuthHmacKey(currentVersion, currentKey), new AuthHmacKey(previousVersion, previousKey), Duration.ofDays(7)));
	}

	private static byte[] testCurrentKey() {
		return Base64.getDecoder().decode("aWRlbXBvdGVuY3ktY3VycmVudC1rZXktMDEyMzQ1Njc4OQ==");
	}

	private static byte[] testPreviousKey() {
		return Base64.getDecoder().decode("aWRlbXBvdGVuY3ktcHJldmlvdXMta2V5LTk4NzY1NDMyMTA=");
	}

	private static byte[] key(byte value) {
		byte[] key = new byte[32];
		java.util.Arrays.fill(key, value);
		return key;
	}

	private static String key() {
		return "idempotency-test-" + UUID.randomUUID();
	}

	private static String hash(char value) {
		return String.valueOf(value).repeat(64);
	}
}
