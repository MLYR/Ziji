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

import app.ziji.category.application.TagCommandStore;
import app.ziji.category.application.TagKeysetPosition;
import app.ziji.category.application.TagNameConflictException;
import app.ziji.category.application.TagPersistenceException;
import app.ziji.category.application.TagQueryReadPort;
import app.ziji.category.application.TagSnapshot;
import app.ziji.category.application.TagStatus;
import app.ziji.category.application.TagStore;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** tags 表适配器；标签归属、唯一性和乐观锁由 PostgreSQL 事实兜底。 */
@Repository
public class PostgresTagRepository implements TagQueryReadPort, TagCommandStore, TagStore {

	private static final String SELECT_COLUMNS = """
		id, owner_user_id, name, name_normalized, status, created_at, updated_at, version
		""";

	private final JdbcTemplate jdbc;

	public PostgresTagRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<TagSnapshot> findByIdForUpdate(UUID tagId) {
		if (tagId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT %s
				FROM tags
				WHERE id = ?
				FOR UPDATE
				""".formatted(SELECT_COLUMNS), records -> records.next()
					? Optional.of(toSnapshot(records)) : Optional.empty(), tagId);
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	@Override
	public boolean existsNameConflict(UUID ownerUserId, String nameNormalized, UUID excludeTagId) {
		if (ownerUserId == null || nameNormalized == null) {
			throw new TagPersistenceException(new IllegalArgumentException("标签唯一性预检参数无效。"));
		}
		try {
			return jdbc.queryForObject("""
				SELECT EXISTS (
					SELECT 1 FROM tags
					WHERE owner_user_id = ?
					  AND name_normalized = ?
					  AND (?::uuid IS NULL OR id <> ?::uuid)
				)
				""", Boolean.class, ownerUserId, nameNormalized, excludeTagId, excludeTagId);
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	@Override
	public List<TagSnapshot> listOwner(UUID ownerUserId, TagKeysetPosition after, int maximumRecords) {
		if (ownerUserId == null || maximumRecords < 1) {
			throw new TagPersistenceException(new IllegalArgumentException("标签查询参数无效。"));
		}
		List<Object> arguments = new ArrayList<>();
		StringBuilder where = new StringBuilder("WHERE owner_user_id = ?");
		arguments.add(ownerUserId);
		if (after != null) {
			where.append(" AND (created_at, id) < (CAST(? AS timestamptz), ?)");
			arguments.add(OffsetDateTime.from(after.createdAt().atOffset(ZoneOffset.UTC)));
			arguments.add(after.tagId());
		}
		arguments.add(maximumRecords);
		try {
			return jdbc.query("""
				SELECT %s
				FROM tags
				%s
				ORDER BY created_at DESC, id DESC
				LIMIT ?
				""".formatted(SELECT_COLUMNS, where), (records, rowNumber) -> toSnapshot(records), arguments.toArray());
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	@Override
	public void insert(TagSnapshot tag) {
		if (tag == null) {
			throw new TagPersistenceException(new IllegalArgumentException("标签不能为空。"));
		}
		try {
			int inserted = jdbc.update("""
				INSERT INTO tags (
					id, owner_user_id, name, name_normalized, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?)
				""", tag.id(), tag.ownerUserId(), tag.name(), tag.nameNormalized(), tag.status().name(),
				utc(tag.createdAt()), utc(tag.updatedAt()), tag.version());
			if (inserted != 1) {
				throw new TagPersistenceException(new IllegalStateException("标签写入未生效。"));
			}
		} catch (DuplicateKeyException exception) {
			throw new TagNameConflictException();
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	@Override
	public Optional<TagSnapshot> updateIfVersion(TagSnapshot tag, int expectedVersion) {
		if (tag == null || expectedVersion < 1) {
			throw new TagPersistenceException(new IllegalArgumentException("标签更新参数无效。"));
		}
		try {
			int updated = jdbc.update("""
				UPDATE tags
				SET name = ?, name_normalized = ?, status = ?, updated_at = ?, version = version + 1
				WHERE id = ? AND owner_user_id = ? AND version = ?
				""", tag.name(), tag.nameNormalized(), tag.status().name(), utc(tag.updatedAt()),
				tag.id(), tag.ownerUserId(), expectedVersion);
			if (updated == 0) {
				return Optional.empty();
			}
			if (updated != 1) {
				throw new TagPersistenceException(new IllegalStateException("标签更新写入数量异常。"));
			}
			return Optional.ofNullable(jdbc.queryForObject(
				"SELECT %s FROM tags WHERE id = ?".formatted(SELECT_COLUMNS),
				(records, rowNumber) -> toSnapshot(records), tag.id()));
		} catch (DuplicateKeyException exception) {
			throw new TagNameConflictException();
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	@Override
	public int countActiveOwned(Collection<UUID> tagIds, UUID ownerUserId) {
		if (ownerUserId == null) {
			throw new TagPersistenceException(new IllegalArgumentException("标签归属参数无效。"));
		}
		if (tagIds == null || tagIds.isEmpty()) {
			return 0;
		}
		try {
			Integer count = jdbc.queryForObject("""
				SELECT count(*)
				FROM tags
				WHERE owner_user_id = ?
				  AND status = 'ACTIVE'
				  AND id = ANY(?::uuid[])
				""", Integer.class, ownerUserId, tagIds.toArray(UUID[]::new));
			return count == null ? 0 : count;
		} catch (DataAccessException exception) {
			throw new TagPersistenceException(exception);
		}
	}

	private static TagSnapshot toSnapshot(ResultSet records) throws SQLException {
		return new TagSnapshot(
			records.getObject("id", UUID.class),
			records.getObject("owner_user_id", UUID.class),
			records.getString("name"),
			records.getString("name_normalized"),
			TagStatus.valueOf(records.getString("status")),
			records.getObject("created_at", OffsetDateTime.class).toInstant(),
			records.getObject("updated_at", OffsetDateTime.class).toInstant(),
			records.getInt("version"));
	}

	private static Object utc(Instant instant) {
		return OffsetDateTime.from(instant.atOffset(ZoneOffset.UTC));
	}
}
