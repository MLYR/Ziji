package app.ziji;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import app.ziji.ledger.application.BalanceProjectionRebuildResult;
import app.ziji.ledger.application.BalanceProjectionService;
import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.RevisePostedTransactionCommand;
import app.ziji.ledger.application.TransactionRevisionDetails;
import app.ziji.ledger.application.TransactionRevisionResult;
import app.ziji.ledger.application.TransactionVoidResult;
import app.ziji.ledger.application.VoidPostedTransactionCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerDomainException;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionStatus;
import app.ziji.ledger.domain.TransactionType;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL 属性验收：以固定种子覆盖账务事实、冲正、幂等和余额重建不变量。 */
@SpringBootTest
@ActiveProfiles("test")
class LedgerInvariantPropertiesPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-25T04:00:00Z");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 25);
	private static final List<Long> FIXED_SEEDS = List.of(
		2026082501L, 2026082502L, 2026082503L, 2026082504L,
		2026082505L, 2026082506L, 2026082507L, 2026082508L,
		2026082509L, 2026082510L, 2026082511L, 2026082512L);

	@Autowired
	private PostingService postingService;

	@Autowired
	private LedgerCommandApplicationService ledger;

	@Autowired
	private BalanceProjectionService projections;

	@Autowired
	private UnifiedIdempotencyService idempotency;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void randomizedPerCurrencyBalancesRejectMinimumMutationsWithoutPartialFacts() {
		for (long seed : FIXED_SEEDS) {
			runWithSeed(seed, diagnostics -> {
				Random random = new Random(seed);
				int operationCount = operationCount(random);
				for (int operationIndex = 0; operationIndex < operationCount; operationIndex++) {
					List<CurrencyCode> currencies = randomCurrencies(random);
					List<LedgerEntry> balanced = balancedEntries(random, currencies, operationIndex);
					Transaction legal = draft(UUID.randomUUID(), balanced, operationIndex);
					LedgerEntry balanceContext = legal.entries().getFirst();
					diagnostics.recordOperation(operationIndex, "valid-per-currency=" + currencies);
					diagnostics.expectation(balanceKey(balanceContext), BigDecimal.ZERO, BigDecimal.ZERO);
					assertDoesNotThrow(() -> postingService.validate(legal));

					List<LedgerEntry> mutated = minimumAmountMutation(legal.entries());
					Transaction invalid = draft(UUID.randomUUID(), mutated, operationIndex);
					LedgerEntry mutationContext = mutated.get(1);
					diagnostics.mutation(describeEntries(legal.entries()), describeEntries(mutated));
					diagnostics.expectation(balanceKey(mutationContext), BigDecimal.ZERO,
						perCurrencyNet(mutated, mutationContext.currency()));
					assertThrows(LedgerDomainException.class, () -> postingService.validate(invalid));

					if (operationIndex == 0) {
						int persistenceOperation = operationIndex;
						CurrencyCode currency = currencies.getFirst();
						UUID userId = insertUser("balance-" + seed);
						UUID debitLedgerId = insertSystemLedger(userId, "BALANCE_DEBIT_" + seed, currency);
						UUID creditLedgerId = insertSystemLedger(userId, "BALANCE_CREDIT_" + seed, currency);
						Money amount = moneyFromMinor(1 + random.nextLong(99_999L), currency);
						diagnostics.expectation(new BalanceKey(debitLedgerId, currency,
							BUSINESS_DATE.plusDays(persistenceOperation % 4)), BigDecimal.ZERO, BigDecimal.ZERO);
						persistRawBalancedTransaction(userId, debitLedgerId, creditLedgerId, amount, currency, persistenceOperation);

						UUID mutatedTransactionId = UUID.randomUUID();
						diagnostics.expectation(new BalanceKey(creditLedgerId, currency,
							BUSINESS_DATE.plusDays(persistenceOperation % 4)), BigDecimal.ZERO, minimumUnit(currency).negate());
						assertThrows(DataAccessException.class, () -> transactions.required(() -> {
							insertRawDraft(mutatedTransactionId, userId, persistenceOperation);
							insertRawEntry(mutatedTransactionId, debitLedgerId, 1, LedgerDirection.DEBIT, amount, currency, persistenceOperation);
							insertRawEntry(mutatedTransactionId, creditLedgerId, 2, LedgerDirection.CREDIT,
								new Money(amount.amount().add(minimumUnit(currency)), currency), currency, persistenceOperation);
							jdbc.update("UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
								timestamp(persistenceOperation), timestamp(persistenceOperation), mutatedTransactionId);
						}));
						require(count("SELECT count(*) FROM transactions WHERE id = ?", mutatedTransactionId) == 0,
							"最小金额变异被拒绝后不得留下 Transaction");
						require(count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", mutatedTransactionId) == 0,
							"最小金额变异被拒绝后不得留下 LedgerEntry");
					}
				}
			});
		}
	}

	@Test
	void randomizedRevisionsAndVoidsRetainImmutablePostedFacts() {
		for (long seed : FIXED_SEEDS) {
			runWithSeed(seed, diagnostics -> {
				SequenceResult sequence = executeLegalSequence(seed, diagnostics);
				sequence.reference().assertMatchesPostedEntries(sequence.fixture().userId(), diagnostics);
				for (UUID transactionId : sequence.factIds()) {
					require(count("SELECT count(*) FROM transactions WHERE id = ?", transactionId) == 1,
						"冲正链中的原 Transaction 不得被物理删除");
					require(count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId) == 2,
						"冲正链中的原 LedgerEntry 不得被物理删除");
				}
			});
		}
	}

	@Test
	void randomizedUnifiedIdempotencyExecutesBusinessWorkExactlyOnce() {
		for (long seed : FIXED_SEEDS) {
				runWithSeed(seed, diagnostics -> {
				Random random = new Random(seed);
				Fixture fixture = fixture("idempotency-" + seed);
				int retries = 2 + random.nextInt(9);
				Money amount = moneyFromMinor(1 + random.nextLong(99_999L), CurrencyCode.CNY);
				UUID transactionId = UUID.randomUUID();
				String key = "qa-led-002-" + UUID.randomUUID();
				String canonicalPayload = canonicalPayload(fixture, amount, transactionId);
				String requestHash = sha256(canonicalPayload);
				String changedHash = sha256(canonicalPayload.replace(amount.amount().toPlainString(),
					amount.amount().add(minimumUnit(CurrencyCode.CNY)).toPlainString()));
				AtomicInteger workCalls = new AtomicInteger();
				diagnostics.recordOperation(0, "idempotency-first keyDigest=" + shortDigest(key));
				diagnostics.expectation(new BalanceKey(fixture.assetLedgerId(), CurrencyCode.CNY, BUSINESS_DATE),
					BigDecimal.ZERO, BigDecimal.ZERO);

				IdempotencyExecution<Transaction> first = executeIdempotentExpense(
					fixture, transactionId, amount, key, requestHash, workCalls);
				require(first.status() == IdempotencyExecution.Status.EXECUTED,
					"首次同作用域请求必须执行一次业务工作");
				IdempotencyTerminalSnapshot firstTerminal = idempotencyTerminalSnapshot(fixture.userId(), key);
				require(requestHash.equals(firstTerminal.requestHash()), "首次终态必须保存规范化请求 Hash");
				for (int retry = 1; retry < retries; retry++) {
					diagnostics.recordOperation(retry, "idempotency-replay=" + retry);
					IdempotencyExecution<Transaction> replay = executeIdempotentExpense(
						fixture, transactionId, amount, key, requestHash, workCalls);
					require(replay.status() == IdempotencyExecution.Status.REPLAYED,
						"同 Key、同 Hash 的重试必须安全重放");
				}
				require(workCalls.get() == 1, "同 Key、同 Hash 的业务工作只能执行一次");

				diagnostics.recordOperation(retries, "idempotency-key-reused keyDigest=" + shortDigest(key));
				IdempotencyExecution<Transaction> reused = idempotency.executeAuthenticated(
					fixture.userId(), 1, "postTransaction", key, changedHash,
					() -> { throw new AssertionError("同 Key、异 Hash 不得执行账务工作"); });
				require(reused.status() == IdempotencyExecution.Status.KEY_REUSED,
					"同 Key、异 Hash 必须返回 KEY_REUSED");
				require(workCalls.get() == 1, "同 Key、异 Hash 不得产生第二次业务工作");
				require(firstTerminal.equals(idempotencyTerminalSnapshot(fixture.userId(), key)),
					"同 Key、异 Hash 不得改写首次幂等终态或安全重放引用");
				assertIdempotencyFacts(fixture, transactionId, key, diagnostics);

				UUID rollbackTransactionId = UUID.randomUUID();
				String rollbackKey = "qa-led-002-rollback-" + UUID.randomUUID();
				String rollbackHash = sha256(canonicalPayload(fixture, amount, rollbackTransactionId));
				AtomicInteger rollbackWorkCalls = new AtomicInteger();
				diagnostics.recordOperation(retries + 1,
					"idempotency-rollback-after-ledger-write keyDigest=" + shortDigest(rollbackKey));
				assertThrows(IllegalStateException.class, () -> idempotency.executeAuthenticated(
					fixture.userId(), 1, "postTransaction", rollbackKey, rollbackHash, () -> {
						rollbackWorkCalls.incrementAndGet();
						postExpense(fixture, amount, 0, rollbackTransactionId);
						throw new IllegalStateException("受控账务回滚验证");
					}));
				require(rollbackWorkCalls.get() == 1, "受控失败前必须恰好写入一次账务工作");
				assertRolledBackIdempotencyFacts(fixture, rollbackTransactionId, rollbackKey, diagnostics);

				diagnostics.recordOperation(retries + 2,
					"idempotency-retry-after-rollback keyDigest=" + shortDigest(rollbackKey));
				IdempotencyExecution<Transaction> recovered = executeIdempotentExpense(
					fixture, rollbackTransactionId, amount, rollbackKey, rollbackHash, rollbackWorkCalls);
				require(recovered.status() == IdempotencyExecution.Status.EXECUTED,
					"回滚后相同作用域必须可以重新取得并执行");
				require(rollbackWorkCalls.get() == 2, "回滚后的重试必须只新增一次业务执行");
				assertIdempotencyFacts(fixture, rollbackTransactionId, rollbackKey, diagnostics);
			});
		}
	}

	@Test
	void randomizedSequencesRebuildSnapshotsFromIndependentReferenceModel() {
		List<SequenceResult> sequences = new ArrayList<>();
		for (long seed : FIXED_SEEDS) {
			runWithSeed(seed, diagnostics -> sequences.add(executeLegalSequence(seed, diagnostics)));
		}

		BalanceProjectionRebuildResult first = rebuildWithDiagnostics(sequences, "首次重建");
		Map<BalanceKey, BigDecimal> firstSnapshots = new LinkedHashMap<>();
		for (SequenceResult sequence : sequences) {
			Map<BalanceKey, BigDecimal> snapshots = snapshotsFor(sequence.fixture());
			assertRebuildMatches(sequence, snapshots, "首次余额快照");
			firstSnapshots.putAll(snapshots);
		}
		assertRebuildDifferenceZero(first, sequences, firstSnapshots, "首次重建");

		for (SequenceResult sequence : sequences) {
			jdbc.update("DELETE FROM account_balance_snapshots WHERE ledger_account_id IN (?, ?)",
				sequence.fixture().assetLedgerId(), sequence.fixture().expenseLedgerId());
		}
		BalanceProjectionRebuildResult afterDelete = rebuildWithDiagnostics(sequences, "删除快照后重建");
		Map<BalanceKey, BigDecimal> rebuiltSnapshots = allSnapshotsFor(sequences);
		assertRebuildDifferenceZero(afterDelete, sequences, rebuiltSnapshots, "删除快照后重建");
		assertSnapshotMapsEqual(firstSnapshots, rebuiltSnapshots, sequences, "删除后重建的快照");

		BalanceProjectionRebuildResult repeated = rebuildWithDiagnostics(sequences, "重复重建");
		Map<BalanceKey, BigDecimal> repeatedSnapshots = allSnapshotsFor(sequences);
		assertRebuildDifferenceZero(repeated, sequences, repeatedSnapshots, "重复重建");
		assertSnapshotMapsEqual(rebuiltSnapshots, repeatedSnapshots, sequences, "重复重建的快照");
	}

	private SequenceResult executeLegalSequence(long seed, SequenceDiagnostics diagnostics) {
		Random random = new Random(seed);
		Fixture fixture = fixture("sequence-" + seed);
		ReferenceModel reference = new ReferenceModel();
		List<CurrentVersion> current = new ArrayList<>();
		Set<UUID> factIds = new LinkedHashSet<>();
		int operationCount = operationCount(random);

		for (int operationIndex = 0; operationIndex < operationCount; operationIndex++) {
			if (current.isEmpty() || random.nextInt(3) == 0) {
				Money amount = moneyFromMinor(1 + random.nextLong(99_999L), CurrencyCode.CNY);
				BalanceKey context = new BalanceKey(fixture.assetLedgerId(), CurrencyCode.CNY,
					BUSINESS_DATE.plusDays(operationIndex % 4));
				BigDecimal before = reference.balanceFor(context);
				diagnostics.recordOperation(operationIndex, "post-expense CNY=" + amount.amount().toPlainString());
				diagnostics.expectation(context, before.subtract(amount.amount()), before);
				Transaction posted = postExpense(fixture, amount, operationIndex);
				reference.apply(posted);
				current.add(new CurrentVersion(posted));
				factIds.add(posted.transactionId());
				assertPersisted(posted, diagnostics);
			} else if (random.nextInt(5) == 0) {
				CurrentVersion target = current.remove(random.nextInt(current.size()));
				LedgerEntry source = target.transaction().entries().getFirst();
				BalanceKey context = balanceKey(source);
				BigDecimal before = reference.balanceFor(context);
				diagnostics.recordOperation(operationIndex, "void version=" + target.transaction().versionNo());
				diagnostics.expectation(context, before.subtract(signedAmount(source)), before);
				TransactionVoidResult voided = ledger.voidPostedTransaction(new VoidPostedTransactionCommand(
					fixture.userId(), target.transaction().transactionId(), 1, "属性作废-" + operationIndex));
				verifyReversal(target.transaction(), voided.reversal(), "REVERSED", diagnostics);
				reference.apply(voided.reversal());
				factIds.add(voided.reversal().transactionId());
				assertPersisted(target.transaction(), diagnostics);
				assertPersisted(voided.reversal(), diagnostics);
			} else {
				int targetIndex = random.nextInt(current.size());
				CurrentVersion target = current.get(targetIndex);
				Money replacementAmount = moneyFromMinor(1 + random.nextLong(99_999L), CurrencyCode.CNY);
				LedgerEntry source = target.transaction().entries().getFirst();
				BalanceKey context = balanceKey(source);
				BigDecimal before = reference.balanceFor(context);
				diagnostics.recordOperation(operationIndex, "revise v" + target.transaction().versionNo() + " CNY="
					+ replacementAmount.amount().toPlainString());
				diagnostics.expectation(context, before.subtract(signedAmount(source)).add(replacementAmount.amount()), before);
				TransactionRevisionResult revision = ledger.revisePostedTransaction(new RevisePostedTransactionCommand(
					fixture.userId(), target.transaction().transactionId(), 1,
					NOW.plusSeconds(operationIndex), target.transaction().businessDate(), "Asia/Shanghai", null,
					"属性修订", "属性修订", "属性修订-" + operationIndex,
					new TransactionRevisionDetails.Expense(
						replacementAmount, fixture.expenseLedgerId(), fixture.categoryId())));
				verifyReversal(target.transaction(), revision.reversal(), "SUPERSEDED", diagnostics);
				verifyReplacement(target.transaction(), revision.replacement(), diagnostics);
				reference.apply(revision.reversal());
				reference.apply(revision.replacement());
				current.set(targetIndex, new CurrentVersion(revision.replacement()));
				factIds.add(revision.reversal().transactionId());
				factIds.add(revision.replacement().transactionId());
				assertPersisted(target.transaction(), diagnostics);
				assertPersisted(revision.reversal(), diagnostics);
				assertPersisted(revision.replacement(), diagnostics);
			}
			reference.assertMatchesPostedEntries(fixture.userId(), diagnostics);
		}
		return new SequenceResult(seed, fixture, reference, factIds, diagnostics);
	}

	private void assertRebuildMatches(SequenceResult sequence, Map<BalanceKey, BigDecimal> snapshots, String source) {
		try {
			sequence.reference().assertMatchesPostedEntries(sequence.fixture().userId(), sequence.diagnostics());
			sequence.reference().assertMatches(snapshots, sequence.diagnostics(), source);
		} catch (AssertionError error) {
			throw new AssertionError(sequence.diagnostics().describe() + "; assertion=" + error.getMessage());
		}
	}

	private BalanceProjectionRebuildResult rebuildWithDiagnostics(List<SequenceResult> sequences, String source) {
		SequenceResult sequence = sequences.getFirst();
		Map.Entry<BalanceKey, BigDecimal> context = sequence.reference().balances().entrySet().iterator().next();
		sequence.diagnostics().expectation(context.getKey(), context.getValue(), null);
		try {
			return projections.rebuildAll();
		} catch (RuntimeException error) {
			throw new AssertionError(sequence.diagnostics().describe() + "; assertion=" + source
				+ " unexpected=" + safeRuntimeSummary(error));
		}
	}

	private static void assertRebuildDifferenceZero(
		BalanceProjectionRebuildResult result,
		List<SequenceResult> sequences,
		Map<BalanceKey, BigDecimal> snapshots,
		String source) {
		if (result.differenceCount() != 0) {
			Map<BalanceKey, BigDecimal> expected = expectedSnapshotsFor(sequences);
			BalanceKey mismatch = firstMismatch(expected, snapshots);
			if (mismatch != null) {
				SequenceResult sequence = sequenceFor(mismatch, sequences);
				SequenceDiagnostics diagnostics = sequence.diagnostics();
				diagnostics.expectation(mismatch, expected.get(mismatch), snapshots.get(mismatch));
				throw new AssertionError(diagnostics.describe() + "; assertion=" + source + "差异="
					+ result.differenceCount() + "，首个可比较余额键不一致");
			}
			SequenceResult sequence = sequences.getFirst();
			SequenceDiagnostics diagnostics = sequence.diagnostics();
			Map.Entry<BalanceKey, BigDecimal> context = expected.entrySet().iterator().next();
			diagnostics.expectation(context.getKey(), context.getValue(), snapshots.get(context.getKey()));
			throw new AssertionError(diagnostics.describe() + "; assertion=" + source + "投影层差异="
				+ result.differenceCount() + "，具体余额键不可得");
		}
	}

	private static Map<BalanceKey, BigDecimal> expectedSnapshotsFor(List<SequenceResult> sequences) {
		Map<BalanceKey, BigDecimal> expected = new LinkedHashMap<>();
		for (SequenceResult sequence : sequences) {
			expected.putAll(sequence.reference().balances());
		}
		return expected;
	}

	private static SequenceResult sequenceFor(BalanceKey key, List<SequenceResult> sequences) {
		return sequences.stream().filter(candidate ->
			candidate.fixture().assetLedgerId().equals(key.ledgerAccountId())
				|| candidate.fixture().expenseLedgerId().equals(key.ledgerAccountId())).findFirst().orElse(sequences.getFirst());
	}

	private static void assertSnapshotMapsEqual(
		Map<BalanceKey, BigDecimal> expected,
		Map<BalanceKey, BigDecimal> actual,
		List<SequenceResult> sequences,
		String source) {
		Set<BalanceKey> keys = new LinkedHashSet<>(expected.keySet());
		keys.addAll(actual.keySet());
		for (BalanceKey key : keys) {
			BigDecimal expectedAmount = expected.get(key);
			BigDecimal actualAmount = actual.get(key);
			if (expectedAmount == null || actualAmount == null || expectedAmount.compareTo(actualAmount) != 0) {
				SequenceResult sequence = sequenceFor(key, sequences);
				sequence.diagnostics().expectation(key, expectedAmount, actualAmount);
				throw new AssertionError(sequence.diagnostics().describe() + "; assertion=" + source
					+ "的键、行数或金额必须与首次重建一致");
			}
		}
	}

	private IdempotencyExecution<Transaction> executeIdempotentExpense(
		Fixture fixture,
		UUID transactionId,
		Money amount,
		String key,
		String requestHash,
		AtomicInteger workCalls) {
		return idempotency.executeAuthenticated(fixture.userId(), 1, "postTransaction", key, requestHash, () -> {
			workCalls.incrementAndGet();
			Transaction transaction = postExpense(fixture, amount, 0, transactionId);
			return IdempotencyWorkResult.completed(transaction, IdempotencyResponse.succeededResource(
				201, "Transaction", transaction.transactionId(), new IdempotencyResponse.ResourceReference(
					"/api/v1/transactions/" + transaction.transactionId(), "\"1\"", 1L)));
		});
	}

	private void assertIdempotencyFacts(Fixture fixture, UUID transactionId, String key, SequenceDiagnostics diagnostics) {
		diagnostics.expectation(new BalanceKey(fixture.assetLedgerId(), CurrencyCode.CNY, BUSINESS_DATE),
			BigDecimal.ZERO, BigDecimal.ZERO);
		require(count("SELECT count(*) FROM transactions WHERE id = ?", transactionId) == 1,
			"幂等重放后必须只有一条目标 Transaction");
		require(count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId) == 2,
			"幂等重放后必须只有一套 LedgerEntry");
		require(count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId) == 1,
			"幂等重放后必须只有一条 Ledger audit");
		require(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId) == 1,
			"幂等重放后必须只有一组 Ledger outbox 事实");
		require(count("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'postTransaction' AND idempotency_key = ?
				AND status = 'SUCCEEDED'
			""", fixture.userId(), key) == 1, "作用域内必须只有首次成功终态");
		require(count("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'postTransaction' AND idempotency_key = ?
				AND status = 'PROCESSING'
			""", fixture.userId(), key) == 0, "失败或冲突路径不得留下孤儿 PROCESSING");
	}

	private IdempotencyTerminalSnapshot idempotencyTerminalSnapshot(UUID userId, String key) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT request_hash, status, response_status, response_reference::text AS response_reference,
				resource_type, resource_id
			FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'postTransaction' AND idempotency_key = ?
			""", userId, key);
		return new IdempotencyTerminalSnapshot(
			(String) row.get("request_hash"),
			(String) row.get("status"),
			((Number) row.get("response_status")).intValue(),
			(String) row.get("response_reference"),
			(String) row.get("resource_type"),
			(UUID) row.get("resource_id"));
	}

	private void assertRolledBackIdempotencyFacts(
		Fixture fixture,
		UUID transactionId,
		String key,
		SequenceDiagnostics diagnostics) {
		diagnostics.expectation(new BalanceKey(fixture.assetLedgerId(), CurrencyCode.CNY, BUSINESS_DATE),
			BigDecimal.ZERO, BigDecimal.ZERO);
		require(count("SELECT count(*) FROM transactions WHERE id = ?", transactionId) == 0,
			"受控失败后不得留下 Transaction");
		require(count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transactionId) == 0,
			"受控失败后不得留下 LedgerEntry");
		require(count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", transactionId) == 0,
			"受控失败后不得留下 Ledger audit");
		require(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", transactionId) == 0,
			"受控失败后不得留下 Ledger outbox");
		require(count("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND api_major_version = 1 AND operation_id = 'postTransaction' AND idempotency_key = ?
			""", fixture.userId(), key) == 0, "受控失败后不得留下幂等 PROCESSING 或终态记录");
	}

	private void verifyReversal(
		Transaction original,
		Transaction reversal,
		String expectedOriginalStatus,
		SequenceDiagnostics diagnostics) {
		require(reversal.type() == TransactionType.REVERSAL, "冲正必须是独立 REVERSAL Transaction");
		require(reversal.status() == TransactionStatus.POSTED, "冲正必须是已确认事实");
		require(original.transactionId().equals(reversal.reversalOfId()), "冲正必须指向目标原交易");
		require(original.entries().size() == reversal.entries().size(), "冲正分录数量必须与原分录相同");
		for (int index = 0; index < original.entries().size(); index++) {
			LedgerEntry source = original.entries().get(index);
			LedgerEntry reversed = reversal.entries().get(index);
			diagnostics.expectation(balanceKey(source), source.amount().amount(), reversed.amount().amount());
			require(source.ledgerAccountId().equals(reversed.ledgerAccountId()), "冲正必须保留原账务科目");
			require(source.amount().amount().compareTo(reversed.amount().amount()) == 0, "冲正金额必须与原分录相同");
			require(source.direction() != reversed.direction(), "冲正分录方向必须与原分录相反");
		}
		String actualStatus = jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class,
			original.transactionId());
		require(expectedOriginalStatus.equals(actualStatus), "原 Transaction 状态必须通过冲正关闭");
	}

	private void verifyReplacement(Transaction original, Transaction replacement, SequenceDiagnostics diagnostics) {
		diagnostics.expectation(balanceKey(original.entries().getFirst()), BigDecimal.ZERO, BigDecimal.ZERO);
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT root_transaction_id, previous_version_id, version_no, status
			FROM transactions WHERE id = ?
			""", replacement.transactionId());
		require(original.rootTransactionId().equals(row.get("root_transaction_id")), "替代版本必须保留 root Transaction");
		require(original.transactionId().equals(row.get("previous_version_id")), "替代版本必须连续指向前一版本");
		require(((Number) row.get("version_no")).intValue() == original.versionNo() + 1, "版本号必须连续");
		require("POSTED".equals(row.get("status")), "替代版本必须为 POSTED");
	}

	private void assertPersisted(Transaction transaction, SequenceDiagnostics diagnostics) {
		diagnostics.expectation(balanceKey(transaction.entries().getFirst()), BigDecimal.ZERO, BigDecimal.ZERO);
		require(count("SELECT count(*) FROM transactions WHERE id = ?", transaction.transactionId()) == 1,
			"已确认账务事实不得被物理删除");
		require(count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", transaction.transactionId()) == 2,
			"已确认分录不得被物理删除");
	}

	private List<LedgerEntry> balancedEntries(Random random, List<CurrencyCode> currencies, int operationIndex) {
		UUID transactionId = UUID.randomUUID();
		List<LedgerEntry> entries = new ArrayList<>();
		int sequence = 1;
		for (CurrencyCode currency : currencies) {
			Money amount = moneyFromMinor(1 + random.nextLong(99_999L), currency);
			entries.add(new LedgerEntry(UUID.randomUUID(), transactionId, UUID.randomUUID(), sequence++,
				LedgerDirection.DEBIT, amount, BUSINESS_DATE.plusDays(operationIndex % 4)));
			entries.add(new LedgerEntry(UUID.randomUUID(), transactionId, UUID.randomUUID(), sequence++,
				LedgerDirection.CREDIT, amount, BUSINESS_DATE.plusDays(operationIndex % 4)));
		}
		return entries;
	}

	private List<LedgerEntry> minimumAmountMutation(List<LedgerEntry> original) {
		List<LedgerEntry> mutated = new ArrayList<>(original);
		LedgerEntry target = original.get(1);
		mutated.set(1, new LedgerEntry(UUID.randomUUID(), target.transactionId(), target.ledgerAccountId(), target.sequenceNo(),
			target.direction(), new Money(target.amount().amount().add(minimumUnit(target.currency())), target.currency()),
			target.businessDate()));
		return mutated;
	}

	private Transaction draft(UUID transactionId, List<LedgerEntry> sourceEntries, int operationIndex) {
		List<LedgerEntry> entries = new ArrayList<>(sourceEntries.size());
		for (LedgerEntry entry : sourceEntries) {
			entries.add(new LedgerEntry(entry.entryId(), transactionId, entry.ledgerAccountId(), entry.sequenceNo(),
				entry.direction(), entry.amount(), entry.businessDate()));
		}
		return new Transaction(transactionId, TransactionType.EXPENSE, TransactionStatus.DRAFT,
			NOW.plusSeconds(operationIndex), BUSINESS_DATE.plusDays(operationIndex % 4), "Asia/Shanghai",
			TransactionSource.MANUAL, transactionId, null, null, 1, null, entries);
	}

	private List<CurrencyCode> randomCurrencies(Random random) {
		List<CurrencyCode> available = new ArrayList<>(List.of(
			CurrencyCode.CNY, CurrencyCode.USD, CurrencyCode.HKD, CurrencyCode.EUR, CurrencyCode.JPY));
		java.util.Collections.shuffle(available, random);
		return List.copyOf(available.subList(0, 1 + random.nextInt(available.size())));
	}

	private Transaction postExpense(Fixture fixture, Money amount, int operationIndex) {
		return postExpense(fixture, amount, operationIndex, UUID.randomUUID());
	}

	private Transaction postExpense(Fixture fixture, Money amount, int operationIndex, UUID transactionId) {
		return ledger.postExpense(new ExpenseCommand(
			fixture.userId(), fixture.accountId(), fixture.expenseLedgerId(), fixture.categoryId(), amount,
			NOW.plusSeconds(operationIndex), BUSINESS_DATE.plusDays(operationIndex % 4), "Asia/Shanghai", "属性商户", "属性支出"),
			transactionId);
	}

	private Fixture fixture(String label) {
		UUID userId = insertUser(label);
		Fixture fixture = new Fixture(userId);
		transactions.required(() -> {
			insertVisibleAccountWithPrimary(fixture);
			insertSystemLedger(userId, "EXPENSE_" + label, CurrencyCode.CNY, fixture.expenseLedgerId());
			insertExpenseCategory(fixture);
		});
		return fixture;
	}

	private UUID insertUser(String label) {
		UUID userId = UUID.randomUUID();
		String email = "qa-led-002-" + label + "-" + userId + "@example.test";
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '账务属性测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, email, email, timestamp(0), timestamp(0), timestamp(0));
		return userId;
	}

	private void insertVisibleAccountWithPrimary(Fixture fixture) {
		jdbc.update("""
			INSERT INTO accounts
				(id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
			VALUES (?, 'ASSET', 'BANK', ?, 'CNY', 'ACTIVE', ?, ?, ?, 1)
			""", fixture.accountId(), "属性账户-" + fixture.accountId(), fixture.userId(), timestamp(0), timestamp(0));
		UUID membershipId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
			VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
			""", membershipId, fixture.accountId(), fixture.userId(), timestamp(0));
		jdbc.update("""
			INSERT INTO account_inclusion_settings
				(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
			""", UUID.randomUUID(), membershipId, timestamp(0), fixture.userId(), timestamp(0));
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
			""", fixture.assetLedgerId(), fixture.accountId(), "PRIMARY_" + fixture.assetLedgerId(), timestamp(0));
	}

	private UUID insertSystemLedger(UUID userId, String code, CurrencyCode currency) {
		UUID ledgerId = UUID.randomUUID();
		insertSystemLedger(userId, code, currency, ledgerId);
		return ledgerId;
	}

	private void insertSystemLedger(UUID userId, String code, CurrencyCode currency, UUID ledgerId) {
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
			VALUES (?, ?, ?, 'SYSTEM', 'EXPENSE', ?, 'ACTIVE', ?)
			""", ledgerId, userId, code, currency.name(), timestamp(0));
	}

	private void insertExpenseCategory(Fixture fixture) {
		String name = "属性分类-" + fixture.categoryId();
		jdbc.update("""
			INSERT INTO categories
				(id, owner_user_id, account_id, category_type, parent_id, name, name_normalized,
				 status, merged_into_id, created_at, updated_at, version)
			VALUES (?, ?, NULL, 'EXPENSE', NULL, ?, ?, 'ACTIVE', NULL, ?, ?, 1)
			""", fixture.categoryId(), fixture.userId(), name, name, timestamp(0), timestamp(0));
	}

	private void persistRawBalancedTransaction(
		UUID userId,
		UUID debitLedgerId,
		UUID creditLedgerId,
		Money amount,
		CurrencyCode currency,
		int operationIndex) {
		UUID transactionId = UUID.randomUUID();
		transactions.required(() -> {
			insertRawDraft(transactionId, userId, operationIndex);
			insertRawEntry(transactionId, debitLedgerId, 1, LedgerDirection.DEBIT, amount, currency, operationIndex);
			insertRawEntry(transactionId, creditLedgerId, 2, LedgerDirection.CREDIT, amount, currency, operationIndex);
			jdbc.update("UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
				timestamp(operationIndex), timestamp(operationIndex), transactionId);
		});
		require(count("SELECT count(*) FROM transactions WHERE id = ?", transactionId) == 1,
			"合法 DRAFT → entries → POSTED 必须真实提交");
	}

	private void insertRawDraft(UUID transactionId, UUID userId, int operationIndex) {
		jdbc.update("""
			INSERT INTO transactions
				(id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
			VALUES (?, 'ADJUSTMENT', 'DRAFT', ?, ?, 'Asia/Shanghai', 'ADJUSTMENT', ?, 1, ?, ?, ?, ?)
			""", transactionId, timestamp(operationIndex), Date.valueOf(BUSINESS_DATE.plusDays(operationIndex % 4)), transactionId,
			userId, userId, timestamp(operationIndex), timestamp(operationIndex));
	}

	private void insertRawEntry(
		UUID transactionId,
		UUID ledgerAccountId,
		int sequenceNo,
		LedgerDirection direction,
		Money amount,
		CurrencyCode currency,
		int operationIndex) {
		jdbc.update("""
			INSERT INTO ledger_entries
				(id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", UUID.randomUUID(), transactionId, ledgerAccountId, sequenceNo,
			direction == LedgerDirection.DEBIT ? "D" : "C", amount.amount(), currency.name(),
			Date.valueOf(BUSINESS_DATE.plusDays(operationIndex % 4)), timestamp(operationIndex));
	}

	private Map<BalanceKey, BigDecimal> snapshotsFor(Fixture fixture) {
		Map<BalanceKey, BigDecimal> snapshots = new LinkedHashMap<>();
		jdbc.query("""
			SELECT ledger_account_id, currency, business_date, balance
			FROM account_balance_snapshots
			WHERE ledger_account_id IN (?, ?)
			""", (RowCallbackHandler) resultSet -> snapshots.put(new BalanceKey(
			resultSet.getObject("ledger_account_id", UUID.class), CurrencyCode.fromCode(resultSet.getString("currency")),
			resultSet.getDate("business_date").toLocalDate()), resultSet.getBigDecimal("balance")),
			fixture.assetLedgerId(), fixture.expenseLedgerId());
		return snapshots;
	}

	private Map<BalanceKey, BigDecimal> allSnapshotsFor(List<SequenceResult> sequences) {
		Map<BalanceKey, BigDecimal> snapshots = new LinkedHashMap<>();
		for (SequenceResult sequence : sequences) {
			snapshots.putAll(snapshotsFor(sequence.fixture()));
		}
		return snapshots;
	}

	private static int operationCount(Random random) {
		return 1 + random.nextInt(20);
	}

	private static Money moneyFromMinor(long minorUnits, CurrencyCode currency) {
		return new Money(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
	}

	private static BigDecimal minimumUnit(CurrencyCode currency) {
		return BigDecimal.valueOf(1L, currency.minorUnits());
	}

	private static BalanceKey balanceKey(LedgerEntry entry) {
		return new BalanceKey(entry.ledgerAccountId(), entry.currency(), entry.businessDate());
	}

	private static BigDecimal perCurrencyNet(List<LedgerEntry> entries, CurrencyCode currency) {
		return entries.stream().filter(entry -> entry.currency() == currency).map(entry ->
			signedAmount(entry))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static BigDecimal signedAmount(LedgerEntry entry) {
		return entry.direction() == LedgerDirection.DEBIT ? entry.amount().amount() : entry.amount().amount().negate();
	}

	private static String canonicalPayload(Fixture fixture, Money amount, UUID transactionId) {
		return "operation=postTransaction|user=" + fixture.userId() + "|account=" + fixture.accountId()
			+ "|category=" + fixture.categoryId() + "|transaction=" + transactionId + "|amount=" + amount.amount().toPlainString()
			+ "|currency=" + amount.currency().name() + "|businessAt=" + NOW + "|businessDate=" + BUSINESS_DATE
			+ "|timezone=Asia/Shanghai|merchant=属性商户|note=属性支出";
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("JDK SHA-256 不可用", exception);
		}
	}

	private static String shortDigest(String value) {
		return sha256(value).substring(0, 12);
	}

	private static String describeEntries(List<LedgerEntry> entries) {
		return entries.stream().map(entry -> entry.ledgerAccountId() + ":" + entry.direction() + ":" + entry.currency()
			+ ":" + entry.amount().amount().toPlainString() + ":" + entry.businessDate()).toList().toString();
	}

	private int count(String sql, Object... arguments) {
		Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
		return value == null ? 0 : value;
	}

	private Timestamp timestamp(int operationIndex) {
		return Timestamp.from(NOW.plusSeconds(operationIndex));
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void runWithSeed(long seed, SeedWork work) {
		SequenceDiagnostics diagnostics = new SequenceDiagnostics(seed);
		try {
			work.run(diagnostics);
		} catch (AssertionError error) {
			throw new AssertionError(diagnostics.describe() + "; assertion=" + error.getMessage());
		} catch (RuntimeException error) {
			throw new AssertionError(diagnostics.describe() + "; unexpected=" + safeRuntimeSummary(error));
		}
	}

	private static String safeRuntimeSummary(RuntimeException error) {
		List<String> types = new ArrayList<>();
		Throwable current = error;
		while (current != null && types.size() < 3) {
			types.add(current.getClass().getSimpleName());
			current = current.getCause();
		}
		return String.join("<-", types) + ", message=redacted";
	}

	@FunctionalInterface
	private interface SeedWork {

		void run(SequenceDiagnostics diagnostics);
	}

	private final class ReferenceModel {

		private final Map<BalanceKey, BigDecimal> dailyChanges = new LinkedHashMap<>();

		void apply(Transaction transaction) {
			for (LedgerEntry entry : transaction.entries()) {
				BalanceKey key = new BalanceKey(entry.ledgerAccountId(), entry.currency(), entry.businessDate());
				BigDecimal signedAmount = entry.direction() == LedgerDirection.DEBIT
					? entry.amount().amount() : entry.amount().amount().negate();
				dailyChanges.merge(key, signedAmount, BigDecimal::add);
			}
		}

		Map<BalanceKey, BigDecimal> balances() {
			return cumulativeBalances(dailyChanges);
		}

		BigDecimal balanceFor(BalanceKey key) {
			return balances().getOrDefault(key, BigDecimal.ZERO);
		}

		// 参考模型只基于测试命令返回的不可变分录累计，不读取生产投影或生产聚合 SQL。
		void assertMatchesPostedEntries(UUID userId, SequenceDiagnostics diagnostics) {
			Map<BalanceKey, BigDecimal> actual = new HashMap<>();
			jdbc.query("""
				SELECT e.ledger_account_id, e.currency, e.business_date,
					SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END) AS balance
				FROM ledger_entries e
				JOIN transactions t ON t.id = e.transaction_id
				WHERE t.created_by = ? AND t.posted_at IS NOT NULL
				GROUP BY e.ledger_account_id, e.currency, e.business_date
				""", (RowCallbackHandler) resultSet -> actual.put(new BalanceKey(
				resultSet.getObject("ledger_account_id", UUID.class), CurrencyCode.fromCode(resultSet.getString("currency")),
				resultSet.getDate("business_date").toLocalDate()), resultSet.getBigDecimal("balance")), userId);
			assertMatches(cumulativeBalances(actual), diagnostics, "已入账 LedgerEntry 汇总");
		}

		void assertMatches(Map<BalanceKey, BigDecimal> actual, SequenceDiagnostics diagnostics, String source) {
			Map<BalanceKey, BigDecimal> expectedBalances = balances();
			BalanceKey keyMismatch = firstMismatch(expectedBalances, actual);
			if (keyMismatch != null) {
				diagnostics.expectation(keyMismatch, expectedBalances.get(keyMismatch), actual.get(keyMismatch));
				require(false, source + " 的余额键集合必须与独立参考模型一致");
			}
			for (Map.Entry<BalanceKey, BigDecimal> expected : expectedBalances.entrySet()) {
				BigDecimal actualAmount = actual.get(expected.getKey());
				diagnostics.expectation(expected.getKey(), expected.getValue(), actualAmount);
				require(expected.getValue().compareTo(actualAmount) == 0, source + " 的余额金额必须与独立参考模型一致");
			}
		}
	}

	private static BalanceKey firstMismatch(Map<BalanceKey, BigDecimal> expected, Map<BalanceKey, BigDecimal> actual) {
		for (BalanceKey key : expected.keySet()) {
			// 差异诊断必须同时定位缺失键和同键金额偏差，才能归属到可复现的失败种子。
			if (!actual.containsKey(key) || expected.get(key).compareTo(actual.get(key)) != 0) {
				return key;
			}
		}
		for (BalanceKey key : actual.keySet()) {
			if (!expected.containsKey(key)) {
				return key;
			}
		}
		return null;
	}

	private static Map<BalanceKey, BigDecimal> cumulativeBalances(Map<BalanceKey, BigDecimal> dailyChanges) {
		Map<AccountCurrencyKey, TreeMap<LocalDate, BigDecimal>> byAccountAndCurrency = new HashMap<>();
		for (Map.Entry<BalanceKey, BigDecimal> daily : dailyChanges.entrySet()) {
			BalanceKey key = daily.getKey();
			byAccountAndCurrency.computeIfAbsent(new AccountCurrencyKey(key.ledgerAccountId(), key.currency()), ignored -> new TreeMap<>())
				.merge(key.businessDate(), daily.getValue(), BigDecimal::add);
		}
		Map<BalanceKey, BigDecimal> cumulative = new LinkedHashMap<>();
		for (Map.Entry<AccountCurrencyKey, TreeMap<LocalDate, BigDecimal>> account : byAccountAndCurrency.entrySet()) {
			BigDecimal running = BigDecimal.ZERO;
			for (Map.Entry<LocalDate, BigDecimal> daily : account.getValue().entrySet()) {
				running = running.add(daily.getValue());
				cumulative.put(new BalanceKey(account.getKey().ledgerAccountId(), account.getKey().currency(), daily.getKey()), running);
			}
		}
		return cumulative;
	}

	private record Fixture(
		UUID userId,
		UUID accountId,
		UUID assetLedgerId,
		UUID expenseLedgerId,
		UUID categoryId) {

		private Fixture(UUID userId) {
			this(userId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		}
	}

	private record CurrentVersion(Transaction transaction) {
	}

	private record SequenceResult(
		long seed,
		Fixture fixture,
		ReferenceModel reference,
		Set<UUID> factIds,
		SequenceDiagnostics diagnostics) {
	}

	private record BalanceKey(UUID ledgerAccountId, CurrencyCode currency, LocalDate businessDate) {
	}

	private record AccountCurrencyKey(UUID ledgerAccountId, CurrencyCode currency) {
	}

	private record IdempotencyTerminalSnapshot(
		String requestHash,
		String status,
		int responseStatus,
		String responseReference,
		String resourceType,
		UUID resourceId) {
	}

	private static final class SequenceDiagnostics {

		private final long seed;
		private final List<String> businessPrefix = new ArrayList<>();
		private int currentOperationIndex = -1;
		private String mutation = "none";
		private String expectation = "account=n/a,currency=n/a,businessDate=n/a,expected=n/a,actual=n/a";

		private SequenceDiagnostics(long seed) {
			this.seed = seed;
		}

		// 只记录待执行业务操作，校验辅助信息不能污染最短可复现前缀或操作下标。
		void recordOperation(int operationIndex, String operation) {
			currentOperationIndex = operationIndex;
			businessPrefix.add(operationIndex + ":" + operation);
		}

		void mutation(String original, String changed) {
			mutation = "original=" + original + ", mutated=" + changed;
		}

		void expectation(BalanceKey key, BigDecimal expected, BigDecimal actual) {
			expectation = "account=" + key.ledgerAccountId() + ",currency=" + key.currency()
				+ ",businessDate=" + key.businessDate() + ",expected=" + expected + ",actual=" + actual;
		}

		String describe() {
			return "seed=" + seed + ", firstFailureOperationIndex=" + currentOperationIndex
				+ ", shortestFailurePrefix=" + businessPrefix + ", " + expectation + ", " + mutation;
		}
	}
}
