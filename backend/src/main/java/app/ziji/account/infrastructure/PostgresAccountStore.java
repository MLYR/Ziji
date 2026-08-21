package app.ziji.account.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountPersistenceException;
import app.ziji.account.application.AccountKeysetPosition;
import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.account.application.AccountStore;
import app.ziji.account.application.AccountUpdatePort;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountPatch;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/** accounts 表适配器；只写入账户聚合本身，不顺带创建成员、计入设置或科目。 */
@Repository
public class PostgresAccountStore implements AccountStore, AccountQueryReadPort, AccountUpdatePort {

	private static final String INSERT_SQL = """
		INSERT INTO accounts (
			id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), ?,
			CAST(? AS timestamptz), CAST(? AS timestamptz), ?)
		""";

	private static final String SELECT_BY_ID_SQL = """
		SELECT id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version
		FROM accounts
		WHERE id = ?
		""";

	private static final String SELECT_BY_ID_FOR_UPDATE_SQL = """
		SELECT id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version
		FROM accounts
		WHERE id = ?
		FOR UPDATE
		""";

	private static final String UPDATE_NAME_SQL = """
		UPDATE accounts
		SET name = ?, updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE id = ? AND version = ?
		RETURNING id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version
		""";

	private static final String UPDATE_INSTITUTION_SQL = """
		UPDATE accounts
		SET institution = ?, updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE id = ? AND version = ?
		RETURNING id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version
		""";

	private static final String UPDATE_NAME_INSTITUTION_SQL = """
		UPDATE accounts
		SET name = ?, institution = ?, updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE id = ? AND version = ?
		RETURNING id, account_class, account_type, name, institution, currency, note,
			status, archived_at, created_by, created_at, updated_at, version
		""";

	private final DSLContext dsl;

	public PostgresAccountStore(DSLContext dsl) {
		if (dsl == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("数据库访问入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public void insert(Account account) {
		if (account == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户不能为空。"));
		}
		// V007 延迟触发器要求提交前存在 OWNER、100% 计入和 PRIMARY 科目；本适配器只写 accounts。
		try {
			int inserted = dsl.execute(
				INSERT_SQL,
				account.id(),
				account.accountClass().name(),
				account.accountType().name(),
				account.name(),
				account.institution(),
				account.currency().name(),
				account.note(),
				account.status().name(),
				utcNullable(account.archivedAt()),
				account.createdBy(),
				utc(account.createdAt()),
				utc(account.updatedAt()),
				account.version());
			if (inserted != 1) {
				throw new AccountPersistenceException(new IllegalStateException("账户写入未生效。"));
			}
		} catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	@Override
	public Optional<Account> findById(UUID accountId) {
		if (accountId == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户 ID 不能为空。"));
		}
		try {
			Record record = dsl.resultQuery(SELECT_BY_ID_SQL, accountId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	@Override
	public Optional<Account> findByIdForUpdate(UUID accountId) {
		if (accountId == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户 ID 不能为空。"));
		}
		try {
			// 账户归档必须与流动性事实写入按同一账户行串行化，锁取得后再读取状态。
			Record record = dsl.resultQuery(SELECT_BY_ID_FOR_UPDATE_SQL, accountId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	@Override
	public List<Account> listByIds(
		Collection<UUID> accountIds,
		AccountKeysetPosition after,
		int maximumRecords) {
		if (accountIds == null || accountIds.isEmpty()) {
			return List.of();
		}
		if (maximumRecords < 1) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户查询数量无效。"));
		}
		List<UUID> ids = new ArrayList<>(accountIds);
		String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
		String sql = """
			SELECT id, account_class, account_type, name, institution, currency, note,
				status, archived_at, created_by, created_at, updated_at, version
			FROM accounts
			WHERE id IN (%s)
			%s
			ORDER BY created_at DESC, id DESC
			LIMIT ?
			""".formatted(
			placeholders,
			after == null ? "" : "AND (created_at, id) < (CAST(? AS timestamptz), ?)");
		try {
			List<Account> accounts = new ArrayList<>(ids.size());
			List<Object> bindings = new ArrayList<>(ids);
			if (after != null) {
				bindings.add(utc(after.createdAt()));
				bindings.add(after.accountId());
			}
			bindings.add(maximumRecords);
			for (Record record : dsl.resultQuery(sql, bindings.toArray()).fetch()) {
				accounts.add(toDomain(record));
			}
			return List.copyOf(accounts);
		} catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	@Override
	public Optional<Account> updateIfVersion(
		UUID accountId,
		int expectedVersion,
		AccountPatch patch,
		Instant updatedAt) {
		if (accountId == null || patch == null || patch.isEmpty() || expectedVersion < 1 || updatedAt == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户更新参数无效。"));
		}
		try {
			Record record = switch (patch) {
				case AccountPatch p when p.hasName() && p.hasInstitution() ->
					dsl.resultQuery(UPDATE_NAME_INSTITUTION_SQL,
						p.name(), p.institution(), utc(updatedAt), accountId, expectedVersion).fetchOne();
				case AccountPatch p when p.hasName() ->
					dsl.resultQuery(UPDATE_NAME_SQL, p.name(), utc(updatedAt), accountId, expectedVersion).fetchOne();
				default ->
					dsl.resultQuery(UPDATE_INSTITUTION_SQL, patch.institution(), utc(updatedAt), accountId, expectedVersion).fetchOne();
			};
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	private static Account toDomain(Record record) {
		try {
			return Account.restore(
				record.get("id", UUID.class),
				AccountClass.valueOf(record.get("account_class", String.class)),
				AccountType.valueOf(record.get("account_type", String.class)),
				record.get("name", String.class),
				record.get("institution", String.class),
				AccountCurrency.fromCode(record.get("currency", String.class)),
				record.get("note", String.class),
				AccountStatus.valueOf(record.get("status", String.class)),
				instantNullable(record.get("archived_at", OffsetDateTime.class)),
				record.get("created_by", UUID.class),
				instant(record.get("created_at", OffsetDateTime.class)),
				instant(record.get("updated_at", OffsetDateTime.class)),
				record.get("version", Integer.class));
		} catch (RuntimeException exception) {
			throw new AccountPersistenceException(exception);
		}
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户时间不能为空。"));
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static OffsetDateTime utcNullable(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		if (value == null) {
			throw new AccountPersistenceException(new IllegalArgumentException("账户时间读取失败。"));
		}
		return value.toInstant();
	}

	private static Instant instantNullable(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
