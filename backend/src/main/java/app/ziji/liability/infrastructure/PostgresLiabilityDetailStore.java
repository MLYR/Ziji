package app.ziji.liability.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import app.ziji.liability.application.LiabilityDetailApplicationException;
import app.ziji.liability.application.LiabilityDetailStore;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailValues;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** PostgreSQL liability_details adapter：插入幂等、行锁和 version 条件均由数据库执行。 */
@Repository
public class PostgresLiabilityDetailStore implements LiabilityDetailStore {

	private static final String COLUMNS = """
		account_id, interest_rate, loan_date, due_date, billing_day, repayment_day,
		current_amount_due, updated_at, version
		""";

	private static final String SELECT_SQL = """
		SELECT %s FROM liability_details WHERE account_id = ?
		""".formatted(COLUMNS);
	private static final String SELECT_LOCK_SQL = SELECT_SQL + " FOR UPDATE";
	private static final String INSERT_SQL = """
		INSERT INTO liability_details (
			account_id, interest_rate, loan_date, due_date, billing_day, repayment_day,
			current_amount_due, updated_at, version)
		VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), 1)
		ON CONFLICT (account_id) DO NOTHING
		RETURNING %s
		""".formatted(COLUMNS);
	private static final String UPDATE_SQL = """
		UPDATE liability_details
		SET interest_rate = ?, loan_date = ?, due_date = ?, billing_day = ?, repayment_day = ?,
			current_amount_due = ?, updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE account_id = ? AND version = ?
		RETURNING %s
		""".formatted(COLUMNS);

	private final DSLContext dsl;

	public PostgresLiabilityDetailStore(DSLContext dsl) {
		if (dsl == null) {
			throw new LiabilityDetailApplicationException.Persistence(
				new IllegalArgumentException("负债详情数据库入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public Optional<LiabilityDetail> findByAccountId(UUID accountId) {
		return find(SELECT_SQL, accountId);
	}

	@Override
	public Optional<LiabilityDetail> lockByAccountId(UUID accountId) {
		return find(SELECT_LOCK_SQL, accountId);
	}

	@Override
	public boolean insertIfAbsent(LiabilityDetail detail) {
		if (detail == null || detail.version() != 1) {
			throw persistence(new IllegalArgumentException("负债详情首次写入参数无效。"));
		}
		try {
			Record row = dsl.resultQuery(INSERT_SQL,
				detail.accountId(), detail.interestRate(), detail.loanDate(), detail.dueDate(), detail.billingDay(),
				detail.repaymentDay(), detail.currentAmountDue(), utc(detail.updatedAt())).fetchOne();
			return row != null;
		} catch (org.jooq.exception.DataAccessException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<LiabilityDetail> updateIfVersion(LiabilityDetail detail, int expectedVersion) {
		if (detail == null || expectedVersion < 1 || detail.version() != expectedVersion + 1) {
			throw persistence(new IllegalArgumentException("负债详情版本更新参数无效。"));
		}
		try {
			Record row = dsl.resultQuery(UPDATE_SQL,
				detail.interestRate(), detail.loanDate(), detail.dueDate(), detail.billingDay(), detail.repaymentDay(),
				detail.currentAmountDue(), utc(detail.updatedAt()), detail.accountId(), expectedVersion).fetchOne();
			return row == null ? Optional.empty() : Optional.of(toDomain(row));
		} catch (org.jooq.exception.DataAccessException exception) {
			throw persistence(exception);
		}
	}

	private Optional<LiabilityDetail> find(String sql, UUID accountId) {
		if (accountId == null) {
			throw persistence(new IllegalArgumentException("负债详情账户 ID 不能为空。"));
		}
		try {
			Record row = dsl.resultQuery(sql, accountId).fetchOne();
			return row == null ? Optional.empty() : Optional.of(toDomain(row));
		} catch (org.jooq.exception.DataAccessException exception) {
			throw persistence(exception);
		}
	}

	private static LiabilityDetail toDomain(Record row) {
		try {
			return LiabilityDetail.restore(
				row.get("account_id", UUID.class),
				new LiabilityDetailValues(
					row.get("interest_rate", java.math.BigDecimal.class),
					row.get("loan_date", java.time.LocalDate.class),
					row.get("due_date", java.time.LocalDate.class),
					row.get("billing_day", Integer.class),
					row.get("repayment_day", Integer.class),
					canonical(row.get("current_amount_due", java.math.BigDecimal.class))),
				instant(row.get("updated_at", OffsetDateTime.class)), row.get("version", Integer.class));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private static LiabilityDetailApplicationException.Persistence persistence(Throwable cause) {
		return new LiabilityDetailApplicationException.Persistence(cause);
	}

	private static java.math.BigDecimal canonical(java.math.BigDecimal value) {
		if (value == null) return null;
		return value.signum() == 0 ? java.math.BigDecimal.ZERO : value.stripTrailingZeros();
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw persistence(new IllegalArgumentException("负债详情更新时间不能为空。"));
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		if (value == null) {
			throw persistence(new IllegalArgumentException("负债详情更新时间读取失败。"));
		}
		return value.toInstant();
	}
}
