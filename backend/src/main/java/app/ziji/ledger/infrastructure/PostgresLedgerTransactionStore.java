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
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
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
}
