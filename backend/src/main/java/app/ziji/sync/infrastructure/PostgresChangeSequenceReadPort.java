package app.ziji.sync.infrastructure;

import java.util.UUID;

import app.ziji.statistics.application.ChangeSequenceReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 从 change_log 读取用户当前最大定向变更序列；无变更或无行时返回 0 哨兵。 */
@Repository
public class PostgresChangeSequenceReadPort implements ChangeSequenceReadPort {

	private final JdbcTemplate jdbc;

	public PostgresChangeSequenceReadPort(JdbcTemplate jdbc) {
		if (jdbc == null) {
			throw new IllegalArgumentException("变更序列读取依赖不能为空。");
		}
		this.jdbc = jdbc;
	}

	@Override
	public long latestSequence(UUID userId) {
		if (userId == null) {
			return 0L;
		}
		Long latest = jdbc.queryForObject(
			"SELECT COALESCE(MAX(sequence), 0) FROM change_log WHERE recipient_user_id = ?",
			Long.class, userId);
		return latest == null ? 0L : latest;
	}
}
