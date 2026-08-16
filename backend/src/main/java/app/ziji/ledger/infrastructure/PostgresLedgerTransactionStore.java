package app.ziji.ledger.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.ledger.application.BalanceAdjustmentWriteDetails;
import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.application.LedgerTransactionStore;
import app.ziji.ledger.application.NoTransactionDetails;
import app.ziji.ledger.application.PostedTransactionWrite;
import app.ziji.ledger.application.RefundWriteDetails;
import app.ziji.ledger.application.TransferWriteDetails;
import app.ziji.ledger.application.TransactionWriteDetails;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionStatus;
import app.ziji.ledger.domain.TransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Transaction、LedgerEntry 和一对一明细的原子 PostgreSQL 适配器。 */
@Repository
public class PostgresLedgerTransactionStore implements LedgerTransactionStore {

	private final JdbcTemplate jdbc;

	public PostgresLedgerTransactionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void persistPosted(PostedTransactionWrite write) {
		if (write == null || write.transaction().status() != app.ziji.ledger.domain.TransactionStatus.POSTED) {
			throw new LedgerPersistenceException(new IllegalArgumentException("只能持久化已校验的入账交易。"));
		}
		Transaction transaction = write.transaction();
		Instant postedAt = transaction.postedAt();
		try {
			jdbc.update("""
				INSERT INTO transactions (
					id, transaction_type, status, business_at, business_date, timezone,
					counterparty, merchant, note, source, client_operation_id,
					idempotency_record_id, root_transaction_id, previous_version_id,
					reversal_of_id, version_no, posted_at, created_by, updated_by,
					created_at, updated_at)
				VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
				""",
				transaction.transactionId(),
				transaction.type().name(),
				timestamp(transaction.businessAt()),
				Date.valueOf(transaction.businessDate()),
				transaction.timezone().getId(),
				write.counterparty(),
				write.merchant(),
				write.note(),
				transaction.source().name(),
				transaction.rootTransactionId(),
				transaction.previousVersionId(),
				transaction.reversalOfId(),
				transaction.versionNo(),
				write.createdBy(),
				write.createdBy(),
				timestamp(postedAt),
				timestamp(postedAt));

			for (LedgerEntry entry : transaction.entries()) {
				jdbc.update("""
					INSERT INTO ledger_entries (
						id, transaction_id, ledger_account_id, sequence_no, direction,
						amount, currency, business_date, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					entry.entryId(),
					entry.transactionId(),
					entry.ledgerAccountId(),
					entry.sequenceNo(),
					direction(entry.direction()),
					entry.amount().amount(),
					entry.currency().name(),
					Date.valueOf(entry.businessDate()),
					timestamp(postedAt));
			}
			insertDetails(write);
			insertCategory(write);
			jdbc.update("""
				UPDATE transactions
				SET status = 'POSTED', posted_at = ?, updated_by = ?, updated_at = ?
				WHERE id = ?
				""", timestamp(postedAt), write.createdBy(), timestamp(postedAt), transaction.transactionId());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<RefundCandidate> findRefundCandidate(UUID originalTransactionId) {
		if (originalTransactionId == null) {
			return Optional.empty();
		}
		try {
			// 空结果必须按不可退款处理，不能让 JdbcTemplate 的 EmptyResultDataAccessException 越过应用边界。
			boolean originalExists = jdbc.query("""
				SELECT 1
				FROM transactions
				WHERE id = ? AND transaction_type = 'EXPENSE' AND status = 'POSTED'
				FOR UPDATE
			""", (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) result -> result.next(),
				originalTransactionId);
			if (!originalExists) {
				return Optional.empty();
			}
			List<RefundBase> bases = jdbc.query("""
				SELECT t.created_by,
					(array_agg(la.visible_account_id) FILTER (
						WHERE la.visible_account_id IS NOT NULL AND e.direction = 'C'))[1]
						AS original_account_id,
					(array_agg(la.id) FILTER (
						WHERE la.account_nature = 'EXPENSE' AND e.direction = 'D'))[1]
						AS expense_ledger_account_id,
					SUM(CASE WHEN la.account_nature = 'EXPENSE' AND e.direction = 'D'
						THEN e.amount ELSE 0 END) AS original_amount,
					(SELECT category_id
					 FROM transaction_categories
					 WHERE transaction_id = t.id AND role = 'PRIMARY'
					 LIMIT 1) AS category_id,
					MAX(e.currency) AS currency
				FROM transactions t
				JOIN ledger_entries e ON e.transaction_id = t.id
				JOIN ledger_accounts la ON la.id = e.ledger_account_id
				WHERE t.id = ? AND t.transaction_type = 'EXPENSE' AND t.status = 'POSTED'
				GROUP BY t.id, t.created_by
				""",
				(result, rowNumber) -> new RefundBase(
					result.getObject("created_by", UUID.class),
					result.getObject("original_account_id", UUID.class),
					result.getObject("expense_ledger_account_id", UUID.class),
					result.getBigDecimal("original_amount"),
					result.getObject("category_id", UUID.class),
					result.getString("currency")),
				originalTransactionId);
			if (bases.isEmpty()) {
				return Optional.empty();
			}
			RefundBase base = bases.get(0);
			BigDecimal refunded = jdbc.queryForObject("""
				SELECT COALESCE(SUM(e.amount), 0)
				FROM refund_details r
				JOIN transactions t ON t.id = r.transaction_id
				JOIN ledger_entries e ON e.transaction_id = t.id
				JOIN ledger_accounts la ON la.id = e.ledger_account_id
				WHERE r.original_transaction_id = ?
				  AND t.status = 'POSTED'
				  AND e.direction = 'C'
				  AND la.account_nature = 'EXPENSE'
				""", BigDecimal.class, originalTransactionId);
			CurrencyCode currency = CurrencyCode.fromCode(base.currency());
			return Optional.of(new RefundCandidate(
				originalTransactionId,
				base.createdBy(),
				base.originalAccountId(),
				base.expenseLedgerAccountId(),
				base.categoryId(),
				new Money(base.originalAmount(), currency),
				new Money(refunded == null ? BigDecimal.ZERO : refunded, currency)));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<PostedTransactionSnapshot> findPostedForMutation(UUID transactionId) {
		if (transactionId == null) {
			return Optional.empty();
		}
		try {
			List<PostedTransactionBase> bases = jdbc.query("""
				SELECT t.id, t.transaction_type, t.business_at, t.business_date, t.timezone,
					t.counterparty, t.merchant, t.note, t.source,
					t.root_transaction_id, t.previous_version_id, t.reversal_of_id, t.version_no,
					t.posted_at, t.entity_version,
					EXISTS (
						SELECT 1 FROM refund_details r WHERE r.original_transaction_id = t.id
						UNION ALL
						SELECT 1 FROM transactions related
						WHERE related.reversal_of_id = t.id OR related.previous_version_id = t.id
					) AS has_dependent_facts
				FROM transactions t
				WHERE t.id = ? AND t.status = 'POSTED'
				FOR UPDATE
				""",
				(result, rowNumber) -> new PostedTransactionBase(
					result.getObject("id", UUID.class),
					TransactionType.valueOf(result.getString("transaction_type")),
					result.getTimestamp("business_at").toInstant(),
					result.getDate("business_date").toLocalDate(),
					result.getString("timezone"),
					result.getString("counterparty"),
					result.getString("merchant"),
					result.getString("note"),
					TransactionSource.valueOf(result.getString("source")),
					result.getObject("root_transaction_id", UUID.class),
					result.getObject("previous_version_id", UUID.class),
					result.getObject("reversal_of_id", UUID.class),
					result.getInt("version_no"),
					result.getTimestamp("posted_at").toInstant(),
					result.getInt("entity_version"),
					result.getBoolean("has_dependent_facts")),
				transactionId);
			if (bases.isEmpty()) {
				return Optional.empty();
			}
			PostedTransactionBase base = bases.get(0);
			List<LedgerEntry> entries = jdbc.query("""
				SELECT id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date
				FROM ledger_entries
				WHERE transaction_id = ?
				ORDER BY sequence_no
				""",
				(result, rowNumber) -> new LedgerEntry(
					result.getObject("id", UUID.class),
					result.getObject("transaction_id", UUID.class),
					result.getObject("ledger_account_id", UUID.class),
					result.getInt("sequence_no"),
					"D".equals(result.getString("direction")) ? LedgerDirection.DEBIT : LedgerDirection.CREDIT,
					new Money(result.getBigDecimal("amount"), CurrencyCode.fromCode(result.getString("currency"))),
					result.getDate("business_date").toLocalDate()),
				transactionId);
			Transaction transaction = new Transaction(
				base.transactionId(), base.type(), TransactionStatus.POSTED, base.businessAt(), base.businessDate(),
				base.timezone(), base.source(), base.rootTransactionId(), base.previousVersionId(), base.reversalOfId(),
				base.versionNo(), base.postedAt(), entries);
			CurrencyCode currency = entries.get(0).currency();
			UUID categoryId = jdbc.query("""
				SELECT category_id FROM transaction_categories
				WHERE transaction_id = ? AND role = 'PRIMARY'
				""", (org.springframework.jdbc.core.ResultSetExtractor<UUID>) result ->
				result.next() ? result.getObject("category_id", UUID.class) : null, transactionId);
			return Optional.of(new PostedTransactionSnapshot(
				transaction, base.entityVersion(), base.hasDependentFacts(), base.counterparty(), base.merchant(), base.note(),
				categoryId, findMutationDetails(base.type(), transactionId, currency)));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public void persistRevision(TransactionRevisionWrite write) {
		try {
			PostedTransactionWrite reversalWrite = new PostedTransactionWrite(
				write.reversal(), write.replacement().createdBy(), null, null, write.reason(), null,
				new NoTransactionDetails());
			persistPosted(reversalWrite);
			transitionOriginal(write.originalTransactionId(), write.expectedEntityVersion(), "SUPERSEDED",
				write.replacement().createdBy(), write.reversal().postedAt());
			persistPosted(write.replacement());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public void persistVoid(TransactionVoidWrite write) {
		try {
			persistPosted(new PostedTransactionWrite(
				write.reversal(), write.updatedBy(), null, null, write.reason(), null,
				new NoTransactionDetails()));
			transitionOriginal(write.originalTransactionId(), write.expectedEntityVersion(), "REVERSED",
				write.updatedBy(), write.reversal().postedAt());
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private void transitionOriginal(
		UUID originalTransactionId,
		int expectedEntityVersion,
		String status,
		UUID updatedBy,
		Instant updatedAt) {
		int updated = jdbc.update("""
			UPDATE transactions
			SET status = ?, entity_version = entity_version + 1, updated_by = ?, updated_at = ?
			WHERE id = ? AND status = 'POSTED' AND entity_version = ?
			""", status, updatedBy, timestamp(updatedAt), originalTransactionId, expectedEntityVersion);
		if (updated != 1) {
			throw new LedgerPersistenceException(new IllegalStateException("已确认交易状态或版本已变化。"));
		}
	}

	private TransactionWriteDetails findMutationDetails(
		TransactionType type,
		UUID transactionId,
		CurrencyCode currency) {
		return switch (type) {
			case TRANSFER -> jdbc.query("""
				SELECT from_account_id, to_account_id, from_amount, to_amount, fee_amount
				FROM transfer_details WHERE transaction_id = ?
				""", (org.springframework.jdbc.core.ResultSetExtractor<TransactionWriteDetails>) result ->
				result.next()
					? new TransferWriteDetails(
						result.getObject("from_account_id", UUID.class),
						result.getObject("to_account_id", UUID.class),
						new Money(result.getBigDecimal("from_amount"), currency),
						new Money(result.getBigDecimal("to_amount"), currency),
						new Money(result.getBigDecimal("fee_amount"), currency))
					: new NoTransactionDetails(), transactionId);
			case REFUND -> jdbc.query("""
				SELECT original_transaction_id, category_id FROM refund_details WHERE transaction_id = ?
				""", (org.springframework.jdbc.core.ResultSetExtractor<TransactionWriteDetails>) result ->
				result.next()
					? new RefundWriteDetails(
						result.getObject("original_transaction_id", UUID.class),
						result.getObject("category_id", UUID.class))
					: new NoTransactionDetails(), transactionId);
			case ADJUSTMENT -> jdbc.query("""
				SELECT account_id, before_balance, actual_balance, difference_amount, reason
				FROM balance_adjustment_details WHERE transaction_id = ?
				""", (org.springframework.jdbc.core.ResultSetExtractor<TransactionWriteDetails>) result ->
				result.next()
					? new BalanceAdjustmentWriteDetails(
						result.getObject("account_id", UUID.class),
						new Money(result.getBigDecimal("before_balance"), currency),
						new Money(result.getBigDecimal("actual_balance"), currency),
						new Money(result.getBigDecimal("difference_amount"), currency),
						result.getString("reason"))
					: new NoTransactionDetails(), transactionId);
			default -> new NoTransactionDetails();
		};
	}

	private void insertDetails(PostedTransactionWrite write) {
		switch (write.details()) {
			case NoTransactionDetails ignored -> {
			}
			case TransferWriteDetails details -> jdbc.update("""
				INSERT INTO transfer_details (
					transaction_id, from_account_id, to_account_id, from_amount,
					to_amount, exchange_rate, fee_amount)
				VALUES (?, ?, ?, ?, ?, NULL, ?)
				""",
				write.transaction().transactionId(),
				details.fromAccountId(),
				details.toAccountId(),
				details.fromAmount().amount(),
				details.toAmount().amount(),
				details.feeAmount().amount());
			case RefundWriteDetails details -> jdbc.update("""
				INSERT INTO refund_details (transaction_id, original_transaction_id, category_id)
				VALUES (?, ?, ?)
				""",
				write.transaction().transactionId(),
				details.originalTransactionId(),
				details.categoryId());
			case BalanceAdjustmentWriteDetails details -> jdbc.update("""
				INSERT INTO balance_adjustment_details (
					transaction_id, account_id, before_balance, actual_balance,
					difference_amount, reason)
				VALUES (?, ?, ?, ?, ?, ?)
				""",
				write.transaction().transactionId(),
				details.accountId(),
				details.beforeBalance().amount(),
				details.actualBalance().amount(),
				details.differenceAmount().amount(),
				details.reason());
		}
	}

	private void insertCategory(PostedTransactionWrite write) {
		UUID categoryId = write.categoryId();
		String role = "PRIMARY";
		if (write.details() instanceof RefundWriteDetails details) {
			categoryId = details.categoryId();
			role = "ORIGINAL";
		}
		if (categoryId == null) {
			return;
		}
		jdbc.update("""
			INSERT INTO transaction_categories (transaction_id, category_id, role)
			VALUES (?, ?, ?)
			""", write.transaction().transactionId(), categoryId, role);
	}

	private static String direction(LedgerDirection direction) {
		return direction == LedgerDirection.DEBIT ? "D" : "C";
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private static LedgerPersistenceException persistence(Throwable exception) {
		if (exception instanceof LedgerPersistenceException persistence) {
			return persistence;
		}
		return new LedgerPersistenceException(exception);
	}

	private record RefundBase(
		UUID createdBy,
		UUID originalAccountId,
		UUID expenseLedgerAccountId,
		BigDecimal originalAmount,
		UUID categoryId,
		String currency) {
	}

	private record PostedTransactionBase(
		UUID transactionId,
		TransactionType type,
		Instant businessAt,
		java.time.LocalDate businessDate,
		String timezone,
		String counterparty,
		String merchant,
		String note,
		TransactionSource source,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
		Instant postedAt,
		int entityVersion,
		boolean hasDependentFacts) {
	}
}
