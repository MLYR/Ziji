package app.ziji.accountmember.infrastructure;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import app.ziji.accountmember.application.AccountPostingAccessPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用数据库当前 membership 周期执行 fail-closed 的账务写入授权。 */
@Repository
public class PostgresAccountPostingAccessPort implements AccountPostingAccessPort {

	private final JdbcTemplate jdbc;

	public PostgresAccountPostingAccessPort(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean mayPost(UUID userId, UUID accountId, Instant effectiveAt) {
		if (userId == null || accountId == null || effectiveAt == null) {
			return false;
		}
		Integer count = jdbc.queryForObject("""
			SELECT count(*)
			FROM account_members
			WHERE user_id = ?
			  AND account_id = ?
			  AND status = 'ACTIVE'
			  AND role IN ('OWNER', 'EDITOR')
			  AND joined_at <= CAST(? AS timestamptz)
			  AND (ended_at IS NULL OR ended_at > CAST(? AS timestamptz))
			""", Integer.class, userId, accountId,
			Timestamp.from(effectiveAt), Timestamp.from(effectiveAt));
		return count != null && count == 1;
	}
}
