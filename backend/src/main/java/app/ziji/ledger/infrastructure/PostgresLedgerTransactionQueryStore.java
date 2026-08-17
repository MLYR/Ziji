package app.ziji.ledger.infrastructure;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.application.TransactionKeysetPosition;
import app.ziji.ledger.application.TransactionQuery;
import app.ziji.ledger.application.TransactionQueryReadPort;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionStatus;
import app.ziji.ledger.domain.TransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ledger 事实只读适配器；可见账户集合由 accountmember application port 先行收敛。 */
@Repository
public class PostgresLedgerTransactionQueryStore implements TransactionQueryReadPort {

	private final JdbcTemplate jdbc;

	public PostgresLedgerTransactionQueryStore(JdbcTemplate jdbc) {
		if (jdbc == null) {
			throw new IllegalArgumentException("交易查询数据库入口不能为空。");
		}
		this.jdbc = jdbc;
	}

	@Override
	public List<TransactionSnapshot> listVisible(
		Set<UUID> visibleAccountIds,
		TransactionQuery query,
		TransactionKeysetPosition after,
		int maximumRecords) {
		if (visibleAccountIds == null || visibleAccountIds.isEmpty()) {
			return List.of();
		}
		try {
			List<Object> bindings = new ArrayList<>();
			String sql = baseSql(visibleAccountIds, query, after, bindings)
				+ " ORDER BY t.business_date DESC, t.id DESC LIMIT ?";
			bindings.add(maximumRecords);
			List<UUID> ids = jdbc.queryForList(sql, UUID.class, bindings.toArray());
			return snapshotsByIds(ids);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public boolean hasVisibleBoundary(Set<UUID> visibleAccountIds, TransactionQuery query, TransactionKeysetPosition position) {
		if (visibleAccountIds == null || visibleAccountIds.isEmpty() || position == null) {
			return false;
		}
		try {
			List<Object> bindings = new ArrayList<>();
			String sql = "SELECT EXISTS (" + baseSql(visibleAccountIds, query, null, bindings)
				+ " AND t.business_date = ? AND t.id = ?)";
			bindings.add(Date.valueOf(position.businessDate()));
			bindings.add(position.transactionId());
			return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, bindings.toArray()));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<TransactionSnapshot> findVisible(Set<UUID> visibleAccountIds, UUID transactionId) {
		if (visibleAccountIds == null || visibleAccountIds.isEmpty() || transactionId == null) {
			return Optional.empty();
		}
		try {
			String placeholders = placeholders(visibleAccountIds.size());
			String sql = """
				SELECT 1
				FROM transactions t
				WHERE t.id = ?
				  AND EXISTS (
					SELECT 1 FROM ledger_entries e
					JOIN ledger_accounts la ON la.id = e.ledger_account_id
					WHERE e.transaction_id = t.id AND la.visible_account_id IN (%s))
				""".formatted(placeholders);
			List<Object> bindings = new ArrayList<>();
			bindings.add(transactionId);
			bindings.addAll(visibleAccountIds);
			Boolean visible = jdbc.query(sql,
				(org.springframework.jdbc.core.ResultSetExtractor<Boolean>) result -> result.next(), bindings.toArray());
			return Boolean.TRUE.equals(visible) ? snapshotsByIds(List.of(transactionId)).stream().findFirst() : Optional.empty();
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private String baseSql(
		Set<UUID> visibleAccountIds,
		TransactionQuery query,
		TransactionKeysetPosition after,
		List<Object> bindings) {
		String placeholders = placeholders(visibleAccountIds.size());
		StringBuilder sql = new StringBuilder("""
			SELECT t.id
			FROM transactions t
			WHERE EXISTS (
				SELECT 1 FROM ledger_entries visible_entry
				JOIN ledger_accounts visible_ledger ON visible_ledger.id = visible_entry.ledger_account_id
				WHERE visible_entry.transaction_id = t.id
				  AND visible_ledger.visible_account_id IN (%s))
			""".formatted(placeholders));
		bindings.addAll(visibleAccountIds);
		if (query.accountId() != null) {
			sql.append(" AND EXISTS (SELECT 1 FROM ledger_entries account_entry JOIN ledger_accounts account_ledger ON account_ledger.id = account_entry.ledger_account_id WHERE account_entry.transaction_id = t.id AND account_ledger.visible_account_id = ?)");
			bindings.add(query.accountId());
		}
		if (query.type() != null) {
			sql.append(" AND t.transaction_type = ?");
			bindings.add(query.type().name());
		}
		if (query.dateFrom() != null) {
			sql.append(" AND t.business_date >= ?");
			bindings.add(Date.valueOf(query.dateFrom()));
		}
		if (query.dateTo() != null) {
			sql.append(" AND t.business_date <= ?");
			bindings.add(Date.valueOf(query.dateTo()));
		}
		if (query.categoryId() != null) {
			sql.append(" AND EXISTS (SELECT 1 FROM transaction_categories tc WHERE tc.transaction_id = t.id AND tc.category_id = ?)");
			bindings.add(query.categoryId());
		}
		if (after != null) {
			sql.append(" AND (t.business_date, t.id) < (?, ?)");
			bindings.add(Date.valueOf(after.businessDate()));
			bindings.add(after.transactionId());
		}
		return sql.toString();
	}

	/** 当前页的 Transaction 与 LedgerEntry 分两次批量读取，避免 limit=200 时退化为 N+1。 */
	private List<TransactionSnapshot> snapshotsByIds(List<UUID> transactionIds) {
		if (transactionIds.isEmpty()) {
			return List.of();
		}
		String placeholders = placeholders(transactionIds.size());
		List<TransactionBase> bases = jdbc.query("""
			SELECT t.id, t.transaction_type, t.status, t.business_at, t.business_date, t.timezone,
				t.source, t.root_transaction_id, t.previous_version_id, t.reversal_of_id,
				t.version_no, t.posted_at, t.entity_version
			FROM transactions t
			WHERE t.id IN (%s)
			""".formatted(placeholders), (result, rowNum) -> {
				Timestamp postedAt = result.getTimestamp("posted_at");
				return new TransactionBase(result.getObject("id", UUID.class),
					TransactionType.valueOf(result.getString("transaction_type")), TransactionStatus.valueOf(result.getString("status")),
					result.getTimestamp("business_at").toInstant(), result.getDate("business_date").toLocalDate(), result.getString("timezone"),
					TransactionSource.valueOf(result.getString("source")), result.getObject("root_transaction_id", UUID.class),
					result.getObject("previous_version_id", UUID.class), result.getObject("reversal_of_id", UUID.class), result.getInt("version_no"),
					postedAt == null ? null : postedAt.toInstant(), result.getInt("entity_version"));
			}, transactionIds.toArray());
		Map<UUID, TransactionBase> baseById = new HashMap<>();
		for (TransactionBase base : bases) {
			baseById.put(base.id(), base);
		}
		List<LedgerEntry> entries = jdbc.query("""
			SELECT id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date
			FROM ledger_entries WHERE transaction_id IN (%s) ORDER BY transaction_id, sequence_no
			""".formatted(placeholders), (entry, ignored) -> new LedgerEntry(
				entry.getObject("id", UUID.class), entry.getObject("transaction_id", UUID.class),
				entry.getObject("ledger_account_id", UUID.class), entry.getInt("sequence_no"),
				"D".equals(entry.getString("direction")) ? LedgerDirection.DEBIT : LedgerDirection.CREDIT,
				new Money(entry.getBigDecimal("amount"), CurrencyCode.fromCode(entry.getString("currency"))),
				entry.getDate("business_date").toLocalDate()), transactionIds.toArray());
		Map<UUID, List<LedgerEntry>> entriesByTransaction = new LinkedHashMap<>();
		for (LedgerEntry entry : entries) {
			entriesByTransaction.computeIfAbsent(entry.transactionId(), ignored -> new ArrayList<>()).add(entry);
		}
		List<TransactionSnapshot> snapshots = new ArrayList<>(transactionIds.size());
		for (UUID transactionId : transactionIds) {
			TransactionBase base = baseById.get(transactionId);
			List<LedgerEntry> transactionEntries = entriesByTransaction.get(transactionId);
			if (base == null || transactionEntries == null || transactionEntries.isEmpty()) {
				continue;
			}
			Transaction transaction = new Transaction(base.id(), base.type(), base.status(), base.businessAt(), base.businessDate(),
				base.timezone(), base.source(), base.rootTransactionId(), base.previousVersionId(), base.reversalOfId(),
				base.versionNo(), base.postedAt(), transactionEntries);
			snapshots.add(new TransactionSnapshot(transaction, base.entityVersion()));
		}
		return List.copyOf(snapshots);
	}

	private static String placeholders(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}

	private static LedgerPersistenceException persistence(Throwable exception) {
		return exception instanceof LedgerPersistenceException e ? e : new LedgerPersistenceException(exception);
	}

	private record TransactionBase(
		UUID id,
		TransactionType type,
		TransactionStatus status,
		java.time.Instant businessAt,
		java.time.LocalDate businessDate,
		String timezone,
		TransactionSource source,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
		java.time.Instant postedAt,
		int entityVersion) {
	}
}
