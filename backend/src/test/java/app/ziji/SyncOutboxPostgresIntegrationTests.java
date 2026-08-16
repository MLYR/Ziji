package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.accountmember.application.AccountRecipientReadPort;
import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.sync.application.SyncOutboxConsumer;
import app.ziji.sync.application.SyncOutboxClaim;
import app.ziji.sync.application.SyncOutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 真实 PostgreSQL 验证 outbox 抢占、定向 change_log、唯一幂等和失败重试。 */
@SpringBootTest
@ActiveProfiles("test")
class SyncOutboxPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private Instant now;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private LedgerCommandApplicationService ledger;

	@Autowired
	private SyncOutboxConsumer consumer;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private SyncOutboxStore syncOutbox;

	@Autowired
	private AccountRecipientReadPort recipients;

	@Autowired
	private ApplicationContext applicationContext;

	@BeforeEach
	void clearSyncFacts() {
		jdbc.update("TRUNCATE TABLE outbox_consumer_receipts, change_log, outbox_events");
		// 定向测试使用真实当前时刻；仅在时钟早于 V015 起点时才向后取一个安全边界。
		Instant subscribedFrom = syncSubscriptionStart();
		Instant current = Instant.now();
		now = current.isAfter(subscribedFrom) ? current : subscribedFrom.plusMillis(1);
	}

	@Test
	void postedOutboxIsTargetedAndRepeatedDeliveryIsIdempotent() {
		Fixture fixture = fixture();
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_subscriptions WHERE consumer_name = 'SYNC' "
			+ "AND aggregate_type = 'Transaction' AND event_type = 'TransactionPosted' AND required_for_cleanup"));
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_subscriptions WHERE consumer_name = 'SYNC' "
			+ "AND aggregate_type = 'Transaction' AND event_type = 'TransactionReversed' AND required_for_cleanup"));
		Transaction transaction = ledger.postExpense(new ExpenseCommand(
			fixture.userId, fixture.accountId, fixture.expenseLedgerId, fixture.categoryId,
			new Money(new BigDecimal("12.00"), CurrencyCode.CNY), now, now.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
			"Asia/Shanghai", "同步测试", "同步测试"));
		int transactionsBefore = count("SELECT count(*) FROM transactions");
		int entriesBefore = count("SELECT count(*) FROM ledger_entries");
		int auditsBefore = count("SELECT count(*) FROM audit_logs");

		assertTrue(consumer.consumeNext());
		assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions"));
		assertEquals(entriesBefore, count("SELECT count(*) FROM ledger_entries"));
		assertEquals(auditsBefore, count("SELECT count(*) FROM audit_logs"));
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
			+ "AND outbox_event_id IN (SELECT id FROM outbox_events WHERE aggregate_id = ?) AND status = 'SUCCEEDED'",
			transaction.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NULL",
			transaction.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM change_log WHERE entity_id = ? AND recipient_user_id = ?",
			transaction.transactionId(), fixture.userId));
		jdbc.update("""
			INSERT INTO outbox_consumer_receipts (
				consumer_name, outbox_event_id, status, attempt_count, next_attempt_at, created_at, updated_at)
			SELECT 'BALANCE', id, 'PENDING', 0, occurred_at, occurred_at, occurred_at
			FROM outbox_events WHERE aggregate_id = ? LIMIT 1
			""", transaction.transactionId());
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
			+ "AND status = 'SUCCEEDED' AND outbox_event_id IN (SELECT id FROM outbox_events WHERE aggregate_id = ?)",
			transaction.transactionId()));
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'BALANCE' "
			+ "AND status = 'PENDING' AND outbox_event_id IN (SELECT id FROM outbox_events WHERE aggregate_id = ?)",
			transaction.transactionId()));
		long firstSequence = jdbc.queryForObject("SELECT sequence FROM change_log WHERE entity_id = ?",
			Long.class, transaction.transactionId());

		UUID duplicateOutbox = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			SELECT ?, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, NULL, 0, occurred_at
			FROM outbox_events WHERE aggregate_id = ? LIMIT 1
			""", duplicateOutbox, transaction.transactionId());
		assertTrue(consumer.consumeNext());
		assertEquals(1, count("SELECT count(*) FROM change_log WHERE entity_id = ? AND recipient_user_id = ?",
			transaction.transactionId(), fixture.userId));
		assertTrue(jdbc.queryForObject("SELECT sequence FROM change_log WHERE entity_id = ?", Long.class,
			transaction.transactionId()) >= firstSequence);

		Transaction second = ledger.postExpense(new ExpenseCommand(
			fixture.userId, fixture.accountId, fixture.expenseLedgerId, fixture.categoryId,
			new Money(new BigDecimal("3.00"), CurrencyCode.CNY), now, now.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
			"Asia/Shanghai", "同步测试2", "同步测试2"));
		assertTrue(consumer.consumeNext());
		long secondSequence = jdbc.queryForObject("SELECT sequence FROM change_log WHERE entity_id = ?", Long.class,
			second.transactionId());
		assertTrue(secondSequence > firstSequence);
	}

	@Test
	void syncSubscriptionBoundaryDoesNotImplicitlyBackfillHistory() {
		Instant subscribedFrom = syncSubscriptionStart();
		UUID beforeId = insertPostedEvent(UUID.randomUUID(), UUID.randomUUID(), subscribedFrom.minusMillis(1));
		UUID atId = insertPostedEvent(UUID.randomUUID(), UUID.randomUUID(), subscribedFrom);
		UUID afterId = insertPostedEvent(UUID.randomUUID(), UUID.randomUUID(), subscribedFrom.plusMillis(1));

		// 读取持久化起点并以起点后的时刻 claim，验证边界由订阅事实而非固定日期决定。
		transactions.required(() -> syncOutbox.claimNext("SYNC", subscribedFrom.plusSeconds(1),
			subscribedFrom.plusSeconds(31)));

		assertEquals(0, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
			+ "AND outbox_event_id = ?", beforeId));
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
			+ "AND outbox_event_id = ?", atId));
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
			+ "AND outbox_event_id = ?", afterId));
		Timestamp createdAt = jdbc.queryForObject("SELECT created_at FROM outbox_consumer_subscriptions "
			+ "WHERE consumer_name = 'SYNC' AND aggregate_type = 'Transaction' "
			+ "AND event_type = 'TransactionPosted'", Timestamp.class);
		assertEquals(subscribedFrom, createdAt.toInstant());
		assertTrue(createdAt.toInstant().isAfter(Instant.EPOCH));
	}

	@Test
	void malformedKnownEventIsFinalizedWithoutChangeLog() {
		Fixture fixture = fixture();
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			VALUES (?, 'Transaction', ?, 'TransactionPosted', CAST(? AS jsonb), 1, ?, NULL, 0, ?)
			""", eventId, fixture.accountId,
			"{\"schemaVersion\":1,\"transactionId\":\"" + UUID.randomUUID()
				+ "\",\"rootTransactionId\":\"" + fixture.accountId
				+ "\",\"versionNo\":1,\"entityVersion\":1,\"operationKind\":\"INITIAL\"}",
			Timestamp.from(now), Timestamp.from(now));
		assertTrue(consumer.consumeNext());
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT r.status, r.error_code, e.published_at
			FROM outbox_consumer_receipts r
			JOIN outbox_events e ON e.id = r.outbox_event_id
			WHERE r.consumer_name = 'SYNC' AND r.outbox_event_id = ?
			""", eventId);
		assertEquals("FAILED_FINAL", row.get("status"));
		assertEquals("OUTBOX_TARGET_MISMATCH", row.get("error_code"));
		assertEquals(null, row.get("published_at"));
	}

	@Test
	void nonObjectJsonPayloadIsFinalizedWithoutChangeLogAndCannotReplay() {
		Fixture fixture = fixture();
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			VALUES (?, 'Transaction', ?, 'TransactionPosted', '[]'::jsonb, 1, ?, NULL, 0, ?)
			""", eventId, fixture.accountId, Timestamp.from(now), Timestamp.from(now));

		assertTrue(consumer.consumeNext());
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT r.status, r.error_code, e.published_at
			FROM outbox_consumer_receipts r
			JOIN outbox_events e ON e.id = r.outbox_event_id
			WHERE r.consumer_name = 'SYNC' AND r.outbox_event_id = ?
			""", eventId);
		assertEquals("FAILED_FINAL", row.get("status"));
		assertEquals("OUTBOX_PAYLOAD_INVALID_JSON", row.get("error_code"));
		assertEquals(null, row.get("published_at"));
		assertEquals(0, count("SELECT count(*) FROM change_log WHERE changed_at = ?", Timestamp.from(now)));
		assertFalse(consumer.consumeNext());
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE outbox_event_id = ? "
			+ "AND status = 'FAILED_FINAL'", eventId));
	}

	@Test
	void emailChallengeEventIsNotClaimedBySync() {
		Fixture fixture = fixture();
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			VALUES (?, 'EmailChallenge', ?, 'EmailChallengeIssued', '{}'::jsonb, 1, ?, NULL, 0, ?)
			""", eventId, fixture.userId, Timestamp.from(now), Timestamp.from(now));

		assertTrue(!consumer.consumeNext());
		assertEquals(0, count("SELECT count(*) FROM outbox_consumer_receipts WHERE outbox_event_id = ?", eventId));
		assertEquals(0, count("SELECT count(*) FROM change_log WHERE changed_at = ?", Timestamp.from(now)));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE id = ? AND published_at IS NULL", eventId));
	}

	@Test
	void concurrentClaimsHaveOneWinnerAndLeaseExpiryAllowsSameConsumerReclaim() throws Exception {
		Fixture fixture = fixture();
		UUID eventId = insertPostedEvent(fixture.accountId, fixture.accountId, now);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Optional<SyncOutboxClaim>> first = executor.submit(() -> transactions.required(
				() -> syncOutbox.claimNext("SYNC", now, now.plusSeconds(30))));
			Future<Optional<SyncOutboxClaim>> second = executor.submit(() -> transactions.required(
				() -> syncOutbox.claimNext("SYNC", now, now.plusSeconds(30))));
			Optional<SyncOutboxClaim> firstClaim = first.get();
			Optional<SyncOutboxClaim> secondClaim = second.get();
			assertEquals(1, (firstClaim.isPresent() ? 1 : 0) + (secondClaim.isPresent() ? 1 : 0));
			assertTrue(firstClaim.orElseGet(secondClaim::get).claimToken() != null);
			assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE consumer_name = 'SYNC' "
				+ "AND outbox_event_id = ? AND status = 'PROCESSING'", eventId));
			assertFalse(transactions.required(() -> syncOutbox.claimNext("SYNC", now.plusSeconds(1), now.plusSeconds(31))).isPresent());
			Optional<SyncOutboxClaim> reclaimed = transactions.required(
				() -> syncOutbox.claimNext("SYNC", now.plusSeconds(31), now.plusSeconds(61)));
			assertTrue(reclaimed.isPresent());
			assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM outbox_consumer_receipts "
				+ "WHERE consumer_name = 'SYNC' AND outbox_event_id = ? AND status = 'PROCESSING'", Integer.class, eventId));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void occurredAtUsesHistoricalMembershipAndInclusionWindow() {
		Fixture fixture = fixture();
		Transaction transaction = ledger.postExpense(new ExpenseCommand(
			fixture.userId, fixture.accountId, fixture.expenseLedgerId, fixture.categoryId,
			new Money(new BigDecimal("4.00"), CurrencyCode.CNY), now, now.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
			"Asia/Shanghai", "历史可见性", "历史可见性"));
		transactions.required(() -> {
			UUID backupOwner = UUID.randomUUID();
			insertUser(backupOwner);
			UUID backupMembership = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", backupMembership, fixture.accountId, backupOwner, timestamp());
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1, ?, ?, ?)
				""", UUID.randomUUID(), backupMembership, timestamp(), backupOwner, timestamp());
			jdbc.update("UPDATE account_members SET status = 'LEFT', ended_at = ? WHERE account_id = ? AND user_id = ?",
				Timestamp.from(now.plusSeconds(20)), fixture.accountId, fixture.userId);
			jdbc.update("""
				UPDATE account_inclusion_settings SET valid_to = ?
				WHERE membership_id IN (SELECT id FROM account_members WHERE account_id = ? AND user_id = ?)
				""", Timestamp.from(now.plusSeconds(20)), fixture.accountId, fixture.userId);
		jdbc.update("UPDATE outbox_events SET occurred_at = ?, next_attempt_at = ? WHERE aggregate_id = ?",
			Timestamp.from(now), Timestamp.from(now), transaction.transactionId());
		});

		assertTrue(consumer.consumeNext());
		assertEquals(1, count("SELECT count(*) FROM change_log WHERE entity_id = ? AND recipient_user_id = ?",
			transaction.transactionId(), fixture.userId));
	}

	@Test
	void recipientRequiresIncludedAndHalfOpenMembershipAndInclusionWindows() {
		Fixture fixture = fixture();
		assertEquals(List.of(fixture.userId), recipients.listRecipientUserIdsAt(fixture.accountId, now));

		UUID membershipId = jdbc.queryForObject("SELECT id FROM account_members WHERE account_id = ? AND user_id = ?",
			UUID.class, fixture.accountId, fixture.userId);
		jdbc.update("UPDATE account_inclusion_settings SET included = FALSE, ratio = 0 WHERE membership_id = ?", membershipId);
		assertTrue(recipients.listRecipientUserIdsAt(fixture.accountId, now).isEmpty());
		jdbc.update("UPDATE account_inclusion_settings SET included = TRUE, ratio = 1 WHERE membership_id = ?", membershipId);
		assertEquals(List.of(fixture.userId), recipients.listRecipientUserIdsAt(fixture.accountId, now));

		Instant inclusionEnd = now.plusSeconds(10);
		jdbc.update("UPDATE account_inclusion_settings SET valid_to = ? WHERE membership_id = ?",
			Timestamp.from(inclusionEnd), membershipId);
		assertTrue(recipients.listRecipientUserIdsAt(fixture.accountId, inclusionEnd).isEmpty());
		Instant nextInclusion = now.plusSeconds(20);
		jdbc.update("""
			INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1, ?, ?, ?)
			""", UUID.randomUUID(), membershipId, Timestamp.from(nextInclusion), fixture.userId, timestamp());
		assertTrue(recipients.listRecipientUserIdsAt(fixture.accountId, now.plusSeconds(19)).isEmpty());
		assertEquals(List.of(fixture.userId), recipients.listRecipientUserIdsAt(fixture.accountId, nextInclusion));

		transactions.required(() -> {
			UUID backupOwner = UUID.randomUUID();
			insertUser(backupOwner);
			UUID backupMembership = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", backupMembership, fixture.accountId, backupOwner, timestamp());
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1, ?, ?, ?)
				""", UUID.randomUUID(), backupMembership, timestamp(), backupOwner, timestamp());
			jdbc.update("UPDATE account_members SET status = 'LEFT', ended_at = ? WHERE id = ?",
				Timestamp.from(now.plusSeconds(30)), membershipId);
		});
		List<UUID> beforeMembershipEnd = recipients.listRecipientUserIdsAt(fixture.accountId, now.plusSeconds(29));
		assertTrue(beforeMembershipEnd.contains(fixture.userId));
		List<UUID> atMembershipEnd = recipients.listRecipientUserIdsAt(fixture.accountId, now.plusSeconds(30));
		assertFalse(atMembershipEnd.contains(fixture.userId));
	}

	@Test
	void startupRecoveryRunnerConsumesDueAndExpiredReceiptsAndStopsWhenEmpty() throws Exception {
		Fixture fixture = fixture();
		ApplicationRunner runner = applicationContext.getBean("syncOutboxStartupRecovery", ApplicationRunner.class);
		ApplicationArguments arguments = new org.springframework.boot.DefaultApplicationArguments();
		Transaction dueTransaction = ledger.postExpense(new ExpenseCommand(
			fixture.userId, fixture.accountId, fixture.expenseLedgerId, fixture.categoryId,
			new Money(new BigDecimal("2.00"), CurrencyCode.CNY), now, now.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
			"Asia/Shanghai", "启动恢复", "启动恢复"));
		runner.run(arguments);
		assertEquals(1, count("SELECT count(*) FROM change_log WHERE entity_id = ?", dueTransaction.transactionId()));
		int afterDue = count("SELECT count(*) FROM change_log");
		runner.run(arguments);
		assertEquals(afterDue, count("SELECT count(*) FROM change_log"));

		Transaction leaseTransaction = ledger.postExpense(new ExpenseCommand(
			fixture.userId, fixture.accountId, fixture.expenseLedgerId, fixture.categoryId,
			new Money(new BigDecimal("1.00"), CurrencyCode.CNY), now, now.atZone(ZoneOffset.ofHours(8)).toLocalDate(),
			"Asia/Shanghai", "租约恢复", "租约恢复"));
		UUID leaseEventId = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?",
			UUID.class, leaseTransaction.transactionId());
		UUID oldClaim = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_consumer_receipts (
				consumer_name, outbox_event_id, status, claim_token, lease_expires_at, attempt_count,
				next_attempt_at, created_at, updated_at)
			VALUES ('SYNC', ?, 'PROCESSING', ?, ?, 1, ?, ?, ?)
			""", leaseEventId, oldClaim, Timestamp.from(now.minusSeconds(1)), Timestamp.from(now),
			Timestamp.from(now), Timestamp.from(now));
		runner.run(arguments);
		assertEquals(1, count("SELECT count(*) FROM outbox_consumer_receipts WHERE outbox_event_id = ? "
			+ "AND status = 'SUCCEEDED'", leaseEventId));
		assertTrue(applicationContext.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
	}

	private Fixture fixture() {
		Fixture fixture = new Fixture();
		transactions.required(() -> {
			insertUser(fixture.userId);
			insertAccount(fixture);
			insertCategory(fixture.userId, fixture.accountId, fixture.categoryId);
			insertSystemLedger(fixture.userId, fixture.expenseLedgerId);
		});
		return fixture;
	}

	private void insertUser(UUID userId) {
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash,
				password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'sync-test', 1, '同步测试', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, userId + "@sync.test", userId + "@sync.test", timestamp(), timestamp(), timestamp());
	}

	private void insertAccount(Fixture fixture) {
		jdbc.update("""
			INSERT INTO accounts (id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'ASSET', 'BANK', '同步账户', 'CNY', 'ACTIVE', ?, ?, ?, 1)
			""", fixture.accountId, fixture.userId, timestamp(), timestamp());
		UUID membership = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
			VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
			""", membership, fixture.accountId, fixture.userId, timestamp());
		jdbc.update("""
			INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1, ?, ?, ?)
			""", UUID.randomUUID(), membership, timestamp(), fixture.userId, timestamp());
		jdbc.update("""
			INSERT INTO ledger_accounts (id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
			""", fixture.assetLedgerId, fixture.accountId, "SYNC_PRIMARY_" + fixture.accountId, timestamp());
	}

	private void insertCategory(UUID userId, UUID accountId, UUID categoryId) {
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, account_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, 'EXPENSE', '同步分类', 'sync-category', 'ACTIVE', ?, ?, 1)
			""", categoryId, userId, accountId, timestamp(), timestamp());
	}

	private void insertSystemLedger(UUID userId, UUID ledgerId) {
		jdbc.update("""
			INSERT INTO ledger_accounts (id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', 'EXPENSE', 'CNY', 'ACTIVE', ?)
			""", ledgerId, userId, "SYNC_EXPENSE_" + userId, timestamp());
	}

	private UUID insertPostedEvent(UUID aggregateId, UUID rootTransactionId, Instant occurredAt) {
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, payload_version,
				occurred_at, published_at, attempt_count, next_attempt_at)
			VALUES (?, 'Transaction', ?, 'TransactionPosted', CAST(? AS jsonb), 1, ?, NULL, 0, ?)
			""", eventId, aggregateId,
			"{\"schemaVersion\":1,\"transactionId\":\"" + aggregateId
				+ "\",\"rootTransactionId\":\"" + rootTransactionId
				+ "\",\"versionNo\":1,\"entityVersion\":1,\"operationKind\":\"INITIAL\"}",
			Timestamp.from(occurredAt), Timestamp.from(occurredAt));
		return eventId;
	}

	private int count(String sql, Object... args) {
		Integer count = jdbc.queryForObject(sql, Integer.class, args);
		return count == null ? 0 : count;
	}

	private Instant syncSubscriptionStart() {
		Timestamp subscribedFrom = jdbc.queryForObject("SELECT subscribed_from FROM outbox_consumer_subscriptions "
			+ "WHERE consumer_name = 'SYNC' AND aggregate_type = 'Transaction' "
			+ "AND event_type = 'TransactionPosted'", Timestamp.class);
		return subscribedFrom.toInstant();
	}

	private Timestamp timestamp() {
		return Timestamp.from(now);
	}

	private static final class Fixture {
		private final UUID userId = UUID.randomUUID();
		private final UUID accountId = UUID.randomUUID();
		private final UUID categoryId = UUID.randomUUID();
		private final UUID assetLedgerId = UUID.randomUUID();
		private final UUID expenseLedgerId = UUID.randomUUID();
	}
}
