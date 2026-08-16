package app.ziji.sync.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.accountmember.application.AccountRecipientReadPort;
import app.ziji.ledger.application.LedgerTransactionSyncReadPort;
import app.ziji.shared.application.TransactionRunner;

/** 只消费 SYNC 持久订阅命中的 Ledger 最小 outbox，并写入按发生时可见性定向的 change_log。 */
public final class SyncOutboxConsumer {

	public static final String CONSUMER_NAME = "SYNC";
	private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
	private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);

	private final SyncOutboxStore outbox;
	private final ChangeLogStore changeLogs;
	private final LedgerTransactionSyncReadPort ledgerReads;
	private final AccountRecipientReadPort recipients;
	private final TransactionRunner transactions;
	private final Clock clock;

	public SyncOutboxConsumer(
		SyncOutboxStore outbox,
		ChangeLogStore changeLogs,
		LedgerTransactionSyncReadPort ledgerReads,
		AccountRecipientReadPort recipients,
		TransactionRunner transactions,
		Clock clock) {
		if (outbox == null || changeLogs == null || ledgerReads == null || recipients == null
			|| transactions == null || clock == null) {
			throw new IllegalArgumentException("同步消费者依赖不能为空。");
		}
		this.outbox = outbox;
		this.changeLogs = changeLogs;
		this.ledgerReads = ledgerReads;
		this.recipients = recipients;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** 处理一条当前 SYNC subscription 可领取事件；没有可领取 receipt 时返回 false。 */
	public boolean consumeNext() {
		Instant now = clock.instant();
		Optional<SyncOutboxClaim> claimed = transactions.required(
			() -> outbox.claimNext(CONSUMER_NAME, now, now.plus(CLAIM_LEASE)));
		if (claimed.isEmpty()) {
			return false;
		}
		SyncOutboxClaim claim = claimed.get();
		try {
			List<ChangeLogWrite> changes = project(claim.event());
			transactions.required(() -> {
				changeLogs.appendIfAbsent(changes);
				outbox.markSucceeded(claim, clock.instant());
			});
		} catch (FinalOutboxFailure failure) {
			transactions.required(() -> outbox.markFinal(claim, clock.instant(), failure.errorCode));
		} catch (RuntimeException failure) {
			transactions.required(() -> outbox.markRetryable(
				claim, clock.instant(), clock.instant().plus(RETRY_BACKOFF), "SYNC_PROCESSING_FAILED"));
		}
		return true;
	}

	/** 应用启动恢复时清空已有、已到期可领取的 SYNC receipt；不创建轮询线程。 */
	public int consumeAvailable() {
		int consumed = 0;
		while (consumeNext()) {
			consumed++;
		}
		return consumed;
	}

	private List<ChangeLogWrite> project(SyncOutboxEvent event) {
		if (!event.payloadJsonValid()) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID_JSON");
		}
		if (!"Transaction".equals(event.aggregateType())
			|| !Set.of("TransactionPosted", "TransactionReversed").contains(event.eventType())) {
			throw finalFailure("OUTBOX_EVENT_UNSUPPORTED");
		}
		if (event.payloadVersion() != 1 || positiveInt(event.payload(), "schemaVersion") != 1) {
			throw finalFailure("OUTBOX_SCHEMA_INVALID");
		}
		return "TransactionPosted".equals(event.eventType()) ? postedChanges(event) : reversedChanges(event);
	}

	private List<ChangeLogWrite> postedChanges(SyncOutboxEvent event) {
		Map<String, Object> payload = event.payload();
		UUID transactionId = uuid(payload, "transactionId");
		UUID rootTransactionId = uuid(payload, "rootTransactionId");
		int versionNo = positiveInt(payload, "versionNo");
		int entityVersion = positiveInt(payload, "entityVersion");
		String operation = text(payload, "operationKind");
		if (!Set.of("INITIAL", "REVISION").contains(operation)) {
			throw finalFailure("OUTBOX_OPERATION_INVALID");
		}
		if (!event.aggregateId().equals(transactionId)) {
			throw finalFailure("OUTBOX_TARGET_MISMATCH");
		}
		LedgerTransactionSyncReadPort.Snapshot transaction = requireSnapshot(transactionId);
		validatePostedTarget(event, transaction, transactionId, rootTransactionId, versionNo, entityVersion, operation);
		return changesFor(List.of(transaction), transactionId, entityVersion, "UPSERT", payload, event.occurredAt());
	}

	private List<ChangeLogWrite> reversedChanges(SyncOutboxEvent event) {
		Map<String, Object> payload = event.payload();
		UUID reversalId = uuid(payload, "transactionId");
		UUID rootTransactionId = uuid(payload, "rootTransactionId");
		int reversalEntityVersion = positiveInt(payload, "entityVersion");
		UUID originalId = uuid(payload, "reversalOfTransactionId");
		int originalVersionNo = positiveInt(payload, "reversalOfVersionNo");
		int originalEntityVersionBefore = positiveInt(payload, "reversalOfEntityVersionBefore");
		int originalEntityVersionAfter = positiveInt(payload, "reversalOfEntityVersionAfter");
		String operation = text(payload, "operationKind");
		if (originalEntityVersionAfter != originalEntityVersionBefore + 1
			|| !Set.of("REVISION", "VOID").contains(operation)) {
			throw finalFailure("OUTBOX_VERSION_INVALID");
		}
		LedgerTransactionSyncReadPort.Snapshot reversal = requireSnapshot(reversalId);
		LedgerTransactionSyncReadPort.Snapshot original = requireSnapshot(originalId);
		if (!event.aggregateId().equals(reversalId) || !reversal.transactionId().equals(reversalId)
			|| !reversal.rootTransactionId().equals(rootTransactionId) || reversal.entityVersion() != reversalEntityVersion
			|| !"POSTED".equals(reversal.status()) || !originalId.equals(reversal.reversalOfId())
			|| !original.rootTransactionId().equals(rootTransactionId) || original.versionNo() != originalVersionNo
			|| original.entityVersion() != originalEntityVersionAfter) {
			throw finalFailure("OUTBOX_TARGET_MISMATCH");
		}
		if ("VOID".equals(operation)) {
			if (!"REVERSED".equals(original.status()) || hasReplacementFields(payload)) {
				throw finalFailure("OUTBOX_RELATION_INVALID");
			}
			return changesFor(List.of(original), originalId, originalEntityVersionAfter, "TOMBSTONE", payload,
				event.occurredAt());
		}
		UUID replacementId = uuid(payload, "replacementTransactionId");
		int replacementVersionNo = positiveInt(payload, "replacementVersionNo");
		int replacementEntityVersion = positiveInt(payload, "replacementEntityVersion");
		LedgerTransactionSyncReadPort.Snapshot replacement = requireSnapshot(replacementId);
		if (!"SUPERSEDED".equals(original.status()) || !replacement.rootTransactionId().equals(rootTransactionId)
			|| !originalId.equals(replacement.previousVersionId()) || replacement.versionNo() != replacementVersionNo
			|| replacement.entityVersion() != replacementEntityVersion || !"POSTED".equals(replacement.status())) {
			throw finalFailure("OUTBOX_RELATION_INVALID");
		}
		List<ChangeLogWrite> originalChanges = changesFor(
			List.of(original, replacement), originalId, originalEntityVersionAfter, "UPSERT", payload, event.occurredAt());
		List<ChangeLogWrite> replacementChanges = changesFor(
			List.of(original, replacement), replacementId, replacementEntityVersion, "UPSERT", payload, event.occurredAt());
		List<ChangeLogWrite> all = new ArrayList<>(originalChanges.size() + replacementChanges.size());
		all.addAll(originalChanges);
		all.addAll(replacementChanges);
		return all;
	}

	private void validatePostedTarget(
		SyncOutboxEvent event,
		LedgerTransactionSyncReadPort.Snapshot transaction,
		UUID transactionId,
		UUID rootTransactionId,
		int versionNo,
		int entityVersion,
		String operation) {
		if (!event.aggregateId().equals(transactionId) || !transaction.transactionId().equals(transactionId)
			|| !transaction.rootTransactionId().equals(rootTransactionId) || transaction.versionNo() != versionNo
			|| transaction.entityVersion() != entityVersion || !"POSTED".equals(transaction.status())) {
			throw finalFailure("OUTBOX_TARGET_MISMATCH");
		}
		if ("INITIAL".equals(operation) && (transaction.previousVersionId() != null || hasReplacementFields(event.payload()))) {
			throw finalFailure("OUTBOX_RELATION_INVALID");
		}
		if ("REVISION".equals(operation)) {
			UUID replacementId = uuid(event.payload(), "replacementTransactionId");
			if (!replacementId.equals(transactionId)
				|| positiveInt(event.payload(), "replacementVersionNo") != transaction.versionNo()
				|| positiveInt(event.payload(), "replacementEntityVersion") != transaction.entityVersion()
				|| transaction.previousVersionId() == null) {
				throw finalFailure("OUTBOX_RELATION_INVALID");
			}
		}
	}

	private List<ChangeLogWrite> changesFor(
		List<LedgerTransactionSyncReadPort.Snapshot> snapshots,
		UUID entityId,
		int entityVersion,
		String changeType,
		Map<String, Object> payload,
		Instant occurredAt) {
		Set<UUID> accountIds = new LinkedHashSet<>();
		for (LedgerTransactionSyncReadPort.Snapshot snapshot : snapshots) {
			accountIds.addAll(snapshot.accountIds());
		}
		if (accountIds.isEmpty()) {
			throw finalFailure("OUTBOX_TARGET_MISMATCH");
		}
		Set<UUID> userIds = new LinkedHashSet<>();
		for (UUID accountId : accountIds) {
			userIds.addAll(recipients.listRecipientUserIdsAt(accountId, occurredAt));
		}
		UUID accountId = accountIds.size() == 1 ? accountIds.iterator().next() : null;
		List<ChangeLogWrite> changes = new ArrayList<>(userIds.size());
		for (UUID userId : userIds) {
			changes.add(new ChangeLogWrite("TRANSACTION", entityId, entityVersion, changeType,
				userId, accountId, occurredAt, payload));
		}
		return changes;
	}

	private LedgerTransactionSyncReadPort.Snapshot requireSnapshot(UUID transactionId) {
		return ledgerReads.findForSync(transactionId)
			.orElseThrow(() -> finalFailure("OUTBOX_TARGET_MISSING"));
	}

	private static boolean hasReplacementFields(Map<String, Object> payload) {
		return payload.containsKey("replacementTransactionId") || payload.containsKey("replacementVersionNo")
			|| payload.containsKey("replacementEntityVersion");
	}

	private static UUID uuid(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (!(value instanceof String text)) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException exception) {
			throw finalFailure("OUTBOX_PAYLOAD_INVALID");
		}
	}

	private static int positiveInt(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Integer integer && integer > 0) {
			return integer;
		}
		if (value instanceof Long longValue && longValue >= 1 && longValue <= Integer.MAX_VALUE) {
			return longValue.intValue();
		}
		if (value instanceof Short shortValue && shortValue > 0) {
			return shortValue.intValue();
		}
		if (value instanceof Byte byteValue && byteValue > 0) {
			return byteValue.intValue();
		}
		if (value instanceof BigInteger bigInteger
			&& bigInteger.compareTo(BigInteger.ONE) >= 0
			&& bigInteger.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
			return bigInteger.intValue();
		}
		if (value instanceof BigDecimal bigDecimal && bigDecimal.scale() <= 0
			&& bigDecimal.compareTo(BigDecimal.ONE) >= 0
			&& bigDecimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
			return bigDecimal.intValue();
		}
		throw finalFailure("OUTBOX_PAYLOAD_INVALID");
	}

	private static String text(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw finalFailure("OUTBOX_PAYLOAD_INVALID");
	}

	private static FinalOutboxFailure finalFailure(String errorCode) {
		return new FinalOutboxFailure(errorCode);
	}

	private static final class FinalOutboxFailure extends RuntimeException {
		private final String errorCode;

		private FinalOutboxFailure(String errorCode) {
			this.errorCode = errorCode;
		}
	}
}
