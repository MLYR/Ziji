package app.ziji;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.user.application.UserAuthenticationException;
import app.ziji.user.application.UserProfileApplicationService;
import app.ziji.user.application.UserProfileStore;
import app.ziji.user.domain.AmountFormat;
import app.ziji.user.domain.BaseCurrency;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import app.ziji.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL 验收基线：非敏感列、条件更新并发和历史账务事实隔离。 */
@SpringBootTest
@ActiveProfiles("test")
class UserProfilePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private UserProfileStore store;

	@Autowired
	private UserProfileApplicationService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void queryReturnsOnlySafeProfileModelWithoutPasswordHash() {
		UUID userId = insertUser("safe");

		UserProfile profile = store.findById(userId).orElseThrow();

		assertEquals(userId, profile.id());
		assertEquals("safe@example.test", profile.email());
		assertEquals("Asia/Shanghai", profile.timezone().getId());
		// UserProfile 没有 password_hash、验证码或 Token 属性，查询只映射契约所需列。
		assertEquals(1, profile.version());
	}

	@Test
	void twoConcurrentUpdatesWithSameVersionHaveOneWinner() throws Exception {
		UUID userId = insertUser("concurrent");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Optional<UserProfile>>> futures = List.of(
				executor.submit(() -> updateAfter(start, userId, "甲")),
				executor.submit(() -> updateAfter(start, userId, "乙")));
			start.countDown();
			long winners = 0;
			for (Future<Optional<UserProfile>> future : futures) {
				if (future.get().isPresent()) {
					winners++;
				}
			}
			assertEquals(1, winners);
			assertEquals(2, store.findById(userId).orElseThrow().version());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void settingsUpdateDoesNotChangeExistingLedgerFact() {
		UUID userId = insertUser("ledger");
		UUID transactionId = UUID.randomUUID();
		Instant businessAt = Instant.parse("2026-08-14T02:00:00Z");
		jdbc.update("""
			INSERT INTO transactions
				(id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
			VALUES (?, 'ADJUSTMENT', 'DRAFT', ?, DATE '2026-08-14', 'Asia/Shanghai', 'MANUAL',
				?, 1, ?, ?, ?, ?)
			""", transactionId, java.sql.Timestamp.from(businessAt), transactionId,
			userId, userId, java.sql.Timestamp.from(businessAt), java.sql.Timestamp.from(businessAt));

		service.updateCurrentUser(userId, 1, new UserProfilePatch(
			Optional.of("改名"), Optional.of(ZoneId.of("UTC")), Optional.of(BaseCurrency.USD),
			Optional.empty(), Optional.of(AmountFormat.ACCOUNTING)));

		String fact = jdbc.queryForObject(
			"SELECT timezone || ':' || business_date::text FROM transactions WHERE id = ?",
			String.class, transactionId);
		assertEquals("Asia/Shanghai:2026-08-14", fact);
	}

	@Test
	void missingTargetDoesNotInsertOrBypassUserLookup() {
		UUID missingUserId = UUID.randomUUID();

		assertThrows(UserAuthenticationException.class,
			() -> service.updateCurrentUser(missingUserId, 1, new UserProfilePatch(
				Optional.of("不会写入"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, missingUserId);
		assertEquals(0, count);
	}

	private Optional<UserProfile> updateAfter(
		CountDownLatch start,
		UUID userId,
		String nickname) throws InterruptedException {
		start.await();
		return store.updateIfVersion(userId, 1, new UserProfilePatch(
			Optional.of(nickname), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
			Instant.now());
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-14T00:00:00Z");
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '原昵称', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, suffix + "@example.test", suffix + "@example.test",
			java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
		return userId;
	}
}
