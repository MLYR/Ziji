package app.ziji.category.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.category.application.CategoryCommandStore;
import app.ziji.category.application.CategoryKeysetPosition;
import app.ziji.category.application.CategoryPersistenceException;
import app.ziji.category.application.CategoryQueryReadPort;
import app.ziji.category.application.CategorySnapshot;
import app.ziji.category.application.CategoryStatus;
import app.ziji.category.application.CategoryType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** categories 表适配器；分类列表可见性与创建唯一性都由真实 PostgreSQL 事实兜底。 */
@Repository
public class PostgresCategoryRepository implements CategoryQueryReadPort, CategoryCommandStore {

	private static final String SELECT_COLUMNS = """
		id, owner_user_id, account_id, category_type, parent_id, name, name_normalized,
		status, merged_into_id, created_at, updated_at, version
		""";

	private final JdbcTemplate jdbc;

	public PostgresCategoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CategorySnapshot> findById(UUID categoryId) {
		if (categoryId == null) {
			return Optional.empty();
		}
		return jdbc.query("""
			SELECT %s
			FROM categories
			WHERE id = ?
			""".formatted(SELECT_COLUMNS), records -> records.next()
				? Optional.of(toSnapshot(records)) : Optional.empty(), categoryId);
	}

	@Override
	public boolean existsNameConflict(
		UUID ownerUserId,
		UUID accountId,
		CategoryType categoryType,
		UUID parentId,
		String nameNormalized) {
		if (categoryType == null || nameNormalized == null) {
			throw new CategoryPersistenceException(new IllegalArgumentException("分类唯一性预检参数无效。"));
		}
		try {
			return jdbc.queryForObject("""
				SELECT EXISTS (
					SELECT 1 FROM categories
					WHERE COALESCE(owner_user_id, '00000000-0000-0000-0000-000000000000'::uuid)
						= COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid)
					  AND COALESCE(account_id, '00000000-0000-0000-0000-000000000000'::uuid)
						= COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid)
					  AND category_type = ?
					  AND COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid)
						= COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid)
					  AND name_normalized = ?
				)
				""", Boolean.class, ownerUserId, accountId, categoryType.name(), parentId, nameNormalized);
		} catch (DataAccessException exception) {
			throw new CategoryPersistenceException(exception);
		}
	}

	@Override
	public List<CategorySnapshot> listVisible(
		UUID userId,
		Collection<UUID> activeAccountIds,
		UUID accountIdFilter,
		CategoryKeysetPosition after,
		int maximumRecords) {
		if (userId == null || maximumRecords < 1) {
			throw new CategoryPersistenceException(new IllegalArgumentException("分类查询参数无效。"));
		}
		List<Object> arguments = new ArrayList<>();
		StringBuilder where = new StringBuilder("""
			WHERE (
				(owner_user_id IS NULL AND account_id IS NULL)
				OR owner_user_id = ?
				OR account_id IN (SELECT unnest(?::uuid[]))
			)
			""");
		arguments.add(userId);
		arguments.add(activeAccountIds.toArray(UUID[]::new));
		if (accountIdFilter != null) {
			// accountId 过滤保留默认和个人树，同时只加入当前 ACTIVE membership 的账户树。
			where.append(" AND (account_id IS NULL OR account_id = ?)");
			arguments.add(accountIdFilter);
		}
		if (after != null) {
			where.append(" AND (created_at, id) < (CAST(? AS timestamptz), ?)");
			arguments.add(OffsetDateTime.from(after.createdAt().atOffset(ZoneOffset.UTC)));
			arguments.add(after.categoryId());
		}
		arguments.add(maximumRecords);
		try {
			return jdbc.query("""
				SELECT %s
				FROM categories
				%s
				ORDER BY created_at DESC, id DESC
				LIMIT ?
				""".formatted(SELECT_COLUMNS, where), (records, rowNumber) -> toSnapshot(records), arguments.toArray());
		} catch (DataAccessException exception) {
			throw new CategoryPersistenceException(exception);
		}
	}

	@Override
	public void insert(CategorySnapshot category) {
		if (category == null) {
			throw new CategoryPersistenceException(new IllegalArgumentException("分类不能为空。"));
		}
		try {
			int inserted = jdbc.update("""
				INSERT INTO categories (
					id, owner_user_id, account_id, category_type, parent_id, name, name_normalized,
					status, merged_into_id, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?)
				""", category.id(), category.ownerUserId(), category.accountId(), category.type().name(),
				category.parentId(), category.name(), category.nameNormalized(), category.status().name(),
				category.mergedIntoId(), utc(category.createdAt()), utc(category.updatedAt()), category.version());
			if (inserted != 1) {
				throw new CategoryPersistenceException(new IllegalStateException("分类写入未生效。"));
			}
		} catch (DuplicateKeyException exception) {
			throw new app.ziji.category.application.CategoryNameConflictException();
		} catch (DataAccessException exception) {
			throw new CategoryPersistenceException(exception);
		}
	}

	private static CategorySnapshot toSnapshot(ResultSet records) throws SQLException {
		return new CategorySnapshot(
			records.getObject("id", UUID.class),
			records.getObject("owner_user_id", UUID.class),
			records.getObject("account_id", UUID.class),
			CategoryType.valueOf(records.getString("category_type")),
			records.getObject("parent_id", UUID.class),
			records.getString("name"),
			records.getString("name_normalized"),
			CategoryStatus.valueOf(records.getString("status")),
			records.getObject("merged_into_id", UUID.class),
			records.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
			records.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
			records.getInt("version"));
	}

	private static Object utc(Instant instant) {
		return OffsetDateTime.from(instant.atOffset(ZoneOffset.UTC));
	}
}
