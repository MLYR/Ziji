package app.ziji.sync.infrastructure;

import java.sql.Timestamp;
import java.util.List;

import app.ziji.sync.application.ChangeLogStore;
import app.ziji.sync.application.ChangeLogWrite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** change_log 追加适配器；唯一键负责重复消费幂等，sequence 由 PostgreSQL identity 生成。 */
@Repository
public class PostgresChangeLogStore implements ChangeLogStore {

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
}
