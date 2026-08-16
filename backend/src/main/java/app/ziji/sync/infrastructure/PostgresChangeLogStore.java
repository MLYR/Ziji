package app.ziji.sync.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import app.ziji.sync.application.ChangeLogStore;
import app.ziji.sync.application.ChangeLogWrite;
import app.ziji.sync.application.SyncChange;
import app.ziji.sync.application.SyncChangeReadPort;
import app.ziji.sync.application.SyncQueryPersistenceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** change_log 追加适配器；唯一键负责重复消费幂等，sequence 由 PostgreSQL identity 生成。 */
@Repository
public class PostgresChangeLogStore implements ChangeLogStore, SyncChangeReadPort {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public PostgresChangeLogStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		if (jdbc == null || objectMapper == null) {
			throw new IllegalArgumentException("change_log 依赖不能为空。");
		}
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@Override
	public void appendIfAbsent(List<ChangeLogWrite> changes) {
		for (ChangeLogWrite change : changes) {
			try {
				jdbc.update("""
					INSERT INTO change_log (
						entity_type, entity_id, entity_version, change_type, recipient_user_id,
						account_id, changed_at, payload_version, payload
					) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), 1, CAST(? AS jsonb))
					ON CONFLICT (recipient_user_id, entity_type, entity_id, entity_version, change_type)
					DO NOTHING
					""",
					change.entityType(), change.entityId(), change.entityVersion(), change.changeType(),
					change.recipientUserId(), change.accountId(), Timestamp.from(change.changedAt()),
					objectMapper.writeValueAsString(change.payload()));
			} catch (Exception exception) {
				throw new IllegalStateException("change_log 写入失败。", exception);
			}
		}
	}

	@Override
	public List<SyncChange> listAfter(UUID recipientUserId, long sequenceExclusive, int maximumRows) {
		try {
			// recipient 条件必须留在 SQL 边界；账户归属、created_by 或客户端参数都不能扩大投递范围。
			return jdbc.query("""
				SELECT sequence, entity_type, entity_id, entity_version, change_type, payload_version, payload::text
				FROM change_log
				WHERE recipient_user_id = ? AND sequence > ?
				ORDER BY sequence ASC
				LIMIT ?
				""", (result, rowNumber) -> new SyncChange(
				result.getLong("sequence"),
				result.getString("entity_type"),
				result.getObject("entity_id", UUID.class),
				result.getInt("entity_version"),
				result.getString("change_type"),
				result.getInt("payload_version"),
				payload(result.getString("payload"))),
				recipientUserId, sequenceExclusive, maximumRows);
		} catch (SyncQueryPersistenceException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new SyncQueryPersistenceException(exception);
		}
	}

	@Override
	public boolean containsSequence(UUID recipientUserId, long sequence) {
		try {
			Boolean exists = jdbc.queryForObject("""
				SELECT EXISTS (
					SELECT 1 FROM change_log WHERE recipient_user_id = ? AND sequence = ?
				)
				""", Boolean.class, recipientUserId, sequence);
			return Boolean.TRUE.equals(exists);
		} catch (Exception exception) {
			throw new SyncQueryPersistenceException(exception);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> payload(String json) {
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, Map.class);
		} catch (Exception exception) {
			throw new SyncQueryPersistenceException(exception);
		}
	}
}
