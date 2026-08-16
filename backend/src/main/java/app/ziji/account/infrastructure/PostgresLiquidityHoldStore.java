package app.ziji.account.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.LiquidityHoldException;
import app.ziji.account.application.LiquidityHoldKeysetPosition;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldEndReason;
import app.ziji.account.domain.LiquidityHoldSource;
import app.ziji.account.domain.LiquidityHoldType;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** PostgreSQL LiquidityHold 适配器：完整历史 keyset 与当前版本条件更新均由数据库执行。 */
@Repository
public class PostgresLiquidityHoldStore implements LiquidityHoldStore {

	private static final String COLUMNS = """
		id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
		root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
		updated_at, version
		""";

	private static final String SELECT_BY_ACCOUNT_AND_ID = """
		SELECT %s FROM liquidity_holds WHERE account_id = ? AND id = ?
		""".formatted(COLUMNS);

	private static final String SELECT_LOCK_BY_ACCOUNT_AND_ID = """
		SELECT %s FROM liquidity_holds WHERE account_id = ? AND id = ? FOR UPDATE
		""".formatted(COLUMNS);

	private static final String INSERT_SQL = """
		INSERT INTO liquidity_holds (
			id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
			root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at,
			updated_at, version
		) VALUES (?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), ?, ?,
			?, ?, ?, CAST(? AS timestamptz), ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?)
		""";

	private static final String SUPERSEDE_SQL = """
		UPDATE liquidity_holds
		SET ended_at = CAST(? AS timestamptz), end_reason = 'SUPERSEDED', updated_at = CAST(? AS timestamptz),
			version = version + 1
		WHERE account_id = ? AND id = ? AND version = ? AND ended_at IS NULL
		RETURNING %s
		""".formatted(COLUMNS);

	private static final String RELEASE_SQL = """
		UPDATE liquidity_holds
		SET released_at = CAST(? AS timestamptz), ended_at = CAST(? AS timestamptz), end_reason = 'RELEASED',
			updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE account_id = ? AND id = ? AND version = ? AND ended_at IS NULL
		RETURNING %s
		""".formatted(COLUMNS);

	private final DSLContext dsl;

	public PostgresLiquidityHoldStore(DSLContext dsl) {
		if (dsl == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用数据库入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public List<LiquidityHold> listByAccount(UUID accountId, LiquidityHoldKeysetPosition after, int maximumRecords) {
		if (accountId == null || maximumRecords < 1) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用历史查询参数无效。"));
		}
		String sql = """
			SELECT %s
			FROM liquidity_holds
			WHERE account_id = ?
			%s
			ORDER BY created_at DESC, id DESC
			LIMIT ?
			""".formatted(COLUMNS, after == null ? "" : "AND (created_at, id) < (CAST(? AS timestamptz), ?)");
		try {
			List<Object> values = new ArrayList<>();
			values.add(accountId);
			if (after != null) {
				values.add(utc(after.createdAt()));
				values.add(after.holdId());
			}
			values.add(maximumRecords);
			List<LiquidityHold> rows = new ArrayList<>();
			for (Record record : dsl.resultQuery(sql, values.toArray()).fetch()) {
				rows.add(toDomain(record));
			}
			return List.copyOf(rows);
		} catch (org.jooq.exception.DataAccessException exception) {
			throw new LiquidityHoldException.Persistence(exception);
		}
	}

	@Override
	public Optional<LiquidityHold> findByAccountAndId(UUID accountId, UUID holdId) {
		return find(SELECT_BY_ACCOUNT_AND_ID, accountId, holdId);
	}

	@Override
	public Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId) {
		return find(SELECT_LOCK_BY_ACCOUNT_AND_ID, accountId, holdId);
	}

	@Override
	public void insert(LiquidityHold hold) {
		if (hold == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用不能为空。"));
		}
		try {
			int changed = dsl.execute(INSERT_SQL,
				hold.id(), hold.accountId(), hold.type().name(), hold.amount(), hold.currency().name(), utc(hold.effectiveAt()),
				utcNullable(hold.expiresAt()), utcNullable(hold.releasedAt()), hold.source().name(), hold.note(),
				hold.rootHoldId(), hold.previousRevisionId(), hold.revisionNo(), utcNullable(hold.endedAt()),
				hold.endReason() == null ? null : hold.endReason().name(), hold.createdBy(), utc(hold.createdAt()),
				utc(hold.updatedAt()), hold.version());
			if (changed != 1) {
				throw new LiquidityHoldException.Persistence(new IllegalStateException("流动性占用写入未生效。"));
			}
		} catch (org.jooq.exception.DataAccessException exception) {
			throw new LiquidityHoldException.Persistence(exception);
		}
	}

	@Override
	public Optional<LiquidityHold> supersedeIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
		return conditional(SUPERSEDE_SQL, accountId, holdId, expectedVersion, now, false);
	}

	@Override
	public Optional<LiquidityHold> releaseIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
		return conditional(RELEASE_SQL, accountId, holdId, expectedVersion, now, true);
	}

	private Optional<LiquidityHold> conditional(
		String sql,
		UUID accountId,
		UUID holdId,
		int expectedVersion,
		Instant now,
		boolean release) {
		if (accountId == null || holdId == null || expectedVersion < 1 || now == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用条件更新参数无效。"));
		}
		try {
			Record record = release
				? dsl.resultQuery(sql, utc(now), utc(now), utc(now), accountId, holdId, expectedVersion).fetchOne()
				: dsl.resultQuery(sql, utc(now), utc(now), accountId, holdId, expectedVersion).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (org.jooq.exception.DataAccessException exception) {
			throw new LiquidityHoldException.Persistence(exception);
		}
	}

	private Optional<LiquidityHold> find(String sql, UUID accountId, UUID holdId) {
		if (accountId == null || holdId == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用 ID 不能为空。"));
		}
		try {
			Record record = dsl.resultQuery(sql, accountId, holdId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (org.jooq.exception.DataAccessException exception) {
			throw new LiquidityHoldException.Persistence(exception);
		}
	}

	private static LiquidityHold toDomain(Record record) {
		try {
			return LiquidityHold.restore(
				record.get("id", UUID.class), record.get("account_id", UUID.class), record.get("root_hold_id", UUID.class),
				record.get("previous_revision_id", UUID.class), record.get("revision_no", Integer.class),
				LiquidityHoldType.valueOf(record.get("hold_type", String.class)), record.get("amount", java.math.BigDecimal.class),
				AccountCurrency.fromCode(record.get("currency", String.class)), instant(record.get("effective_at", OffsetDateTime.class)),
				instantNullable(record.get("expires_at", OffsetDateTime.class)), instantNullable(record.get("released_at", OffsetDateTime.class)),
				LiquidityHoldSource.valueOf(record.get("source", String.class)), record.get("note", String.class),
				instantNullable(record.get("ended_at", OffsetDateTime.class)), endReason(record.get("end_reason", String.class)),
				record.get("created_by", UUID.class), instant(record.get("created_at", OffsetDateTime.class)),
				instant(record.get("updated_at", OffsetDateTime.class)), record.get("version", Integer.class));
		} catch (RuntimeException exception) {
			throw new LiquidityHoldException.Persistence(exception);
		}
	}

	private static LiquidityHoldEndReason endReason(String value) {
		return value == null ? null : LiquidityHoldEndReason.valueOf(value);
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用时间不能为空。"));
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static OffsetDateTime utcNullable(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		if (value == null) {
			throw new LiquidityHoldException.Persistence(new IllegalArgumentException("流动性占用时间读取失败。"));
		}
		return value.toInstant();
	}

	private static Instant instantNullable(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
