package app.ziji.sync.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.accountmember.application.AccountRecipientReadPort;
import app.ziji.ledger.application.LedgerTransactionSyncReadPort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

class SyncOutboxConsumerTests {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-15T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
	private static final UUID TRANSACTION_ID = UUID.randomUUID();
	private static final UUID ACCOUNT_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();

	@Test
	void postedEventCreatesTargetedUpsertAndCompletesOnlyCurrentReceipt() {
		Fixture fixture = fixture(postedEvent(), false);

		assertTrue(fixture.consumer.consumeNext());
		assertEquals(1, fixture.changes.changes.size());
		assertEquals(USER_ID, fixture.changes.changes.getFirst().recipientUserId());
		assertEquals("UPSERT", fixture.changes.changes.getFirst().changeType());
		assertEquals(1, fixture.outbox.succeeded.size());
		assertTrue(fixture.outbox.retryable.isEmpty());
		assertTrue(fixture.outbox.finalFailures.isEmpty());
		assertEquals(OCCURRED_AT, fixture.recipients.occurredAt);
	}

	@Test
	void unsupportedEventIsFinalizedWithoutChangeLog() {
		SyncOutboxEvent event = new SyncOutboxEvent(
			UUID.randomUUID(), UUID.randomUUID(), "EmailChallenge", "EmailChallengeIssued", 1, Map.of(),
			OCCURRED_AT, 1);
		Fixture fixture = fixture(event, false);

		assertTrue(fixture.consumer.consumeNext());
		assertTrue(fixture.changes.changes.isEmpty());
		assertEquals(1, fixture.outbox.finalFailures.size());
		assertTrue(fixture.outbox.succeeded.isEmpty());
	}

	@Test
	void targetMismatchIsFinalizedWithoutWritingChangeLog() {
		Fixture fixture = fixture(postedEvent(), true);

		assertTrue(fixture.consumer.consumeNext());
		assertTrue(fixture.changes.changes.isEmpty());
		assertEquals(1, fixture.outbox.finalFailures.size());
	}

	@Test
	void processingFailureUsesRetryableReceiptWithoutSuccess() {
		Fixture fixture = fixture(postedEvent(), false);
		fixture.changes.fail = true;

		assertTrue(fixture.consumer.consumeNext());
		assertTrue(fixture.outbox.succeeded.isEmpty());
		assertEquals(1, fixture.outbox.retryable.size());
		assertTrue(fixture.outbox.finalFailures.isEmpty());
	}

	@Test
	void nonIntegerVersionFieldsAreFinalizedWithoutWritingChangeLog() {
		Map<String, Object> payload = Map.of(
			"schemaVersion", 1.0,
			"transactionId", TRANSACTION_ID.toString(),
			"rootTransactionId", TRANSACTION_ID.toString(),
			"versionNo", 1,
			"entityVersion", 1,
			"operationKind", "INITIAL");
		Fixture fixture = fixture(new SyncOutboxEvent(
			UUID.randomUUID(), TRANSACTION_ID, "Transaction", "TransactionPosted", 1, payload, OCCURRED_AT, 0), false);

		assertTrue(fixture.consumer.consumeNext());
		assertTrue(fixture.changes.changes.isEmpty());
		assertEquals(List.of("OUTBOX_PAYLOAD_INVALID"), fixture.outbox.finalFailureCodes);
	}

	@Test
	void outOfRangeIntegerFieldsAreFinalizedWithoutWritingChangeLog() {
		Map<String, Object> payload = Map.of(
			"schemaVersion", 1,
			"transactionId", TRANSACTION_ID.toString(),
			"rootTransactionId", TRANSACTION_ID.toString(),
			"versionNo", Long.valueOf(Integer.MAX_VALUE) + 1,
			"entityVersion", 1,
			"operationKind", "INITIAL");
		Fixture fixture = fixture(new SyncOutboxEvent(
			UUID.randomUUID(), TRANSACTION_ID, "Transaction", "TransactionPosted", 1, payload, OCCURRED_AT, 0), false);

		assertTrue(fixture.consumer.consumeNext());
		assertTrue(fixture.changes.changes.isEmpty());
		assertEquals(List.of("OUTBOX_PAYLOAD_INVALID"), fixture.outbox.finalFailureCodes);
	}

	@Test
	void zeroNegativeAndOversizedVersionFieldsAreFinalizedWithoutWritingChangeLog() {
		List<Map.Entry<String, Object>> invalidValues = List.of(
			Map.entry("schemaVersion", 0),
			Map.entry("versionNo", -1),
			Map.entry("entityVersion", 0),
			Map.entry("versionNo", Long.MAX_VALUE),
			Map.entry("entityVersion", new BigInteger("999999999999999999999999")));
		for (Map.Entry<String, Object> invalidValue : invalidValues) {
			Map<String, Object> payload = new HashMap<>(postedEvent().payload());
			payload.put(invalidValue.getKey(), invalidValue.getValue());
			Fixture fixture = fixture(new SyncOutboxEvent(
				UUID.randomUUID(), TRANSACTION_ID, "Transaction", "TransactionPosted", 1, payload, OCCURRED_AT, 0), false);

			assertTrue(fixture.consumer.consumeNext());
			assertTrue(fixture.changes.changes.isEmpty());
			assertEquals(List.of("OUTBOX_PAYLOAD_INVALID"), fixture.outbox.finalFailureCodes);
		}
	}

	@Test
	void reversalAndReplacementPositiveVersionsProduceFactsAndInvalidReplacementVersionFinalizes() {
		UUID rootId = UUID.randomUUID();
		UUID originalId = UUID.randomUUID();
		UUID reversalId = UUID.randomUUID();
		UUID replacementId = UUID.randomUUID();
		LedgerTransactionSyncReadPort reads = revisionSnapshots(rootId, originalId, reversalId, replacementId);
		Map<String, Object> payload = new HashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("transactionId", reversalId.toString());
		payload.put("rootTransactionId", rootId.toString());
		payload.put("entityVersion", 1);
		payload.put("reversalOfTransactionId", originalId.toString());
		payload.put("reversalOfVersionNo", 1);
		payload.put("reversalOfEntityVersionBefore", 1);
		payload.put("reversalOfEntityVersionAfter", 2);
		payload.put("operationKind", "REVISION");
		payload.put("replacementTransactionId", replacementId.toString());
		payload.put("replacementVersionNo", 2);
		payload.put("replacementEntityVersion", 1);
		SyncOutboxEvent event = new SyncOutboxEvent(
			UUID.randomUUID(), reversalId, "Transaction", "TransactionReversed", 1, payload, OCCURRED_AT, 0);
		FakeOutbox outbox = new FakeOutbox(event);
		FakeChangeLog changes = new FakeChangeLog();
		SyncOutboxConsumer consumer = new SyncOutboxConsumer(
			outbox, changes, reads, new FakeRecipients(), new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals(2, changes.changes.size());
		assertTrue(outbox.succeeded.size() == 1);

		payload.put("replacementVersionNo", 0);
		FakeOutbox invalidOutbox = new FakeOutbox(new SyncOutboxEvent(
			UUID.randomUUID(), reversalId, "Transaction", "TransactionReversed", 1, payload, OCCURRED_AT, 0));
		FakeChangeLog invalidChanges = new FakeChangeLog();
		SyncOutboxConsumer invalidConsumer = new SyncOutboxConsumer(
			invalidOutbox, invalidChanges, reads, new FakeRecipients(), new DirectTransactionRunner(),
			Clock.fixed(NOW, ZoneOffset.UTC));
		assertTrue(invalidConsumer.consumeNext());
		assertTrue(invalidChanges.changes.isEmpty());
		assertEquals(List.of("OUTBOX_PAYLOAD_INVALID"), invalidOutbox.finalFailureCodes);
	}

	@Test
	void reversedEventsFailClosedWhenReversalSnapshotViolatesDomainRelations() {
		UUID rootId = UUID.randomUUID();
		UUID originalId = UUID.randomUUID();
		UUID reversalId = UUID.randomUUID();
		Map<String, Object> payload = new HashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("transactionId", reversalId.toString());
		payload.put("rootTransactionId", rootId.toString());
		payload.put("entityVersion", 1);
		payload.put("reversalOfTransactionId", originalId.toString());
		payload.put("reversalOfVersionNo", 1);
		payload.put("reversalOfEntityVersionBefore", 1);
		payload.put("reversalOfEntityVersionAfter", 2);
		payload.put("operationKind", "VOID");

		// 冲正交易按领域规则必须以自身为 root；root 指向版本链时快照与领域不一致，必须 final 失败。
		LedgerTransactionSyncReadPort wrongRoot = transactionId -> Optional.of(new LedgerTransactionSyncReadPort.Snapshot(
			reversalId, rootId, null, originalId, 1, 1, "POSTED", List.of(ACCOUNT_ID)));
		FakeOutbox wrongRootOutbox = new FakeOutbox(new SyncOutboxEvent(
			UUID.randomUUID(), reversalId, "Transaction", "TransactionReversed", 1, payload, OCCURRED_AT, 0));
		FakeChangeLog wrongRootChanges = new FakeChangeLog();
		assertTrue(new SyncOutboxConsumer(wrongRootOutbox, wrongRootChanges, wrongRoot, new FakeRecipients(),
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC)).consumeNext());
		assertTrue(wrongRootChanges.changes.isEmpty());
		assertEquals(List.of("OUTBOX_TARGET_MISMATCH"), wrongRootOutbox.finalFailureCodes);

		// 事件聚合不是冲正交易自身时同样 final 失败。
		LedgerTransactionSyncReadPort correctRoot = transactionId -> Optional.of(new LedgerTransactionSyncReadPort.Snapshot(
			reversalId, reversalId, null, originalId, 1, 1, "POSTED", List.of(ACCOUNT_ID)));
		FakeOutbox wrongAggregateOutbox = new FakeOutbox(new SyncOutboxEvent(
			UUID.randomUUID(), originalId, "Transaction", "TransactionReversed", 1, payload, OCCURRED_AT, 0));
		FakeChangeLog wrongAggregateChanges = new FakeChangeLog();
		assertTrue(new SyncOutboxConsumer(wrongAggregateOutbox, wrongAggregateChanges, correctRoot, new FakeRecipients(),
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC)).consumeNext());
		assertTrue(wrongAggregateChanges.changes.isEmpty());
		assertEquals(List.of("OUTBOX_TARGET_MISMATCH"), wrongAggregateOutbox.finalFailureCodes);
	}

	@Test
	void multipleAccountsUseNullChangeLogAccountId() {
		FakeOutbox outbox = new FakeOutbox(postedEvent());
		FakeChangeLog changes = new FakeChangeLog();
		SyncOutboxConsumer consumer = new SyncOutboxConsumer(
			outbox, changes, new FakeLedgerReads(false, List.of(ACCOUNT_ID, UUID.randomUUID())), new FakeRecipients(),
			new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(consumer.consumeNext());
		assertEquals(1, changes.changes.size());
		assertEquals(null, changes.changes.getFirst().accountId());
	}

	private static SyncOutboxEvent postedEvent() {
		return new SyncOutboxEvent(UUID.randomUUID(), TRANSACTION_ID, "Transaction", "TransactionPosted", 1, Map.of(
			"schemaVersion", 1,
			"transactionId", TRANSACTION_ID.toString(),
			"rootTransactionId", TRANSACTION_ID.toString(),
			"versionNo", 1,
			"entityVersion", 1,
			"operationKind", "INITIAL"), OCCURRED_AT, 0);
	}

	private static LedgerTransactionSyncReadPort revisionSnapshots(
		UUID rootId, UUID originalId, UUID reversalId, UUID replacementId) {
		Map<UUID, LedgerTransactionSyncReadPort.Snapshot> snapshots = Map.of(
			originalId, new LedgerTransactionSyncReadPort.Snapshot(
				originalId, rootId, null, null, 1, 2, "SUPERSEDED", List.of(ACCOUNT_ID)),
			reversalId, new LedgerTransactionSyncReadPort.Snapshot(
				// 冲正交易按领域规则以自身为 root，与 LedgerTransactionFactory.createReversal 一致。
				reversalId, reversalId, null, originalId, 1, 1, "POSTED", List.of(ACCOUNT_ID)),
			replacementId, new LedgerTransactionSyncReadPort.Snapshot(
				replacementId, rootId, originalId, null, 2, 1, "POSTED", List.of(ACCOUNT_ID)));
		return transactionId -> Optional.ofNullable(snapshots.get(transactionId));
	}

	private static Fixture fixture(SyncOutboxEvent event, boolean missingLedger) {
		FakeOutbox outbox = new FakeOutbox(event);
		FakeChangeLog changes = new FakeChangeLog();
		FakeLedgerReads ledgerReads = new FakeLedgerReads(missingLedger);
		FakeRecipients recipients = new FakeRecipients();
		SyncOutboxConsumer consumer = new SyncOutboxConsumer(
			outbox, changes, ledgerReads, recipients, new DirectTransactionRunner(), Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(consumer, outbox, changes, recipients);
	}

	private record Fixture(SyncOutboxConsumer consumer, FakeOutbox outbox, FakeChangeLog changes, FakeRecipients recipients) {
	}

	private static final class FakeOutbox implements SyncOutboxStore {
		private SyncOutboxEvent pending;
		private final List<UUID> succeeded = new ArrayList<>();
		private final List<UUID> retryable = new ArrayList<>();
		private final List<UUID> finalFailures = new ArrayList<>();
		private final List<String> finalFailureCodes = new ArrayList<>();

		private FakeOutbox(SyncOutboxEvent pending) {
			this.pending = pending;
		}

		@Override
		public Optional<SyncOutboxClaim> claimNext(String consumerName, Instant now, Instant leaseUntil) {
			if (pending == null) {
				return Optional.empty();
			}
			SyncOutboxClaim claim = new SyncOutboxClaim(consumerName, pending, UUID.randomUUID());
			pending = null;
			return Optional.of(claim);
		}

		@Override
		public void markSucceeded(SyncOutboxClaim claim, Instant completedAt) {
			succeeded.add(claim.event().id());
		}

		@Override
		public void markRetryable(SyncOutboxClaim claim, Instant failedAt, Instant nextAttemptAt, String errorCode) {
			retryable.add(claim.event().id());
		}

		@Override
		public void markFinal(SyncOutboxClaim claim, Instant failedAt, String errorCode) {
			finalFailures.add(claim.event().id());
			finalFailureCodes.add(errorCode);
		}
	}

	private static final class FakeChangeLog implements ChangeLogStore {
		private final List<ChangeLogWrite> changes = new ArrayList<>();
		private boolean fail;

		@Override
		public void appendIfAbsent(List<ChangeLogWrite> writes) {
			if (fail) {
				throw new IllegalStateException("injected");
			}
			changes.addAll(writes);
		}
	}

	private static final class FakeLedgerReads implements LedgerTransactionSyncReadPort {
		private final boolean missing;
		private final List<UUID> accountIds;

		private FakeLedgerReads(boolean missing) {
			this(missing, List.of(ACCOUNT_ID));
		}

		private FakeLedgerReads(boolean missing, List<UUID> accountIds) {
			this.missing = missing;
			this.accountIds = accountIds;
		}

		@Override
		public Optional<Snapshot> findForSync(UUID transactionId) {
			return missing ? Optional.empty() : Optional.of(new Snapshot(
				transactionId, TRANSACTION_ID, null, null, 1, 1, "POSTED", accountIds));
		}
	}

	private static final class FakeRecipients implements AccountRecipientReadPort {
		private Instant occurredAt;

		@Override
		public List<UUID> listRecipientUserIdsAt(UUID accountId, Instant occurredAt) {
			this.occurredAt = occurredAt;
			return List.of(USER_ID);
		}
	}

	private static final class DirectTransactionRunner implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}
}
