package app.ziji.accountmember.infrastructure;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import app.ziji.accountmember.application.AccountPostingAccessPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用数据库当前 membership 周期执行 fail-closed 的账务写入授权。 */
@Repository
public class PostgresAccountPostingAccessPort implements AccountPostingAccessPort {

	private final JdbcTemplate jdbc;
	private static final String POSTING_DECISION_SQL = """
		/* 在同一快照内先确认当前 ACTIVE membership，再区分角色和业务时间边界。 */
		SELECT m.role,
		       m.joined_at <= CAST(? AS timestamptz)
		         AND (m.ended_at IS NULL OR m.ended_at > CAST(? AS timestamptz)) AS effective
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ?
		  AND m.account_id = ?
		  AND m.status = 'ACTIVE'
		  AND s.valid_to IS NULL
		  AND m.joined_at <= statement_timestamp()
		  AND (m.ended_at IS NULL OR m.ended_at > statement_timestamp())
		  AND s.valid_from <= statement_timestamp()
		ORDER BY m.id
		LIMIT 1
		FOR SHARE
		""";

	public PostgresAccountPostingAccessPort(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public PostingAccessDecision postingDecision(UUID userId, UUID accountId, Instant effectiveAt) {
		if (userId == null || accountId == null || effectiveAt == null) {
			return PostingAccessDecision.NOT_VISIBLE;
		}
		List<PostingAccessDecision> decisions = jdbc.query(POSTING_DECISION_SQL, (result, rowNum) -> {
			String role = result.getString("role");
			if (!"OWNER".equals(role) && !"EDITOR".equals(role)) {
				return PostingAccessDecision.READ_ONLY;
			}
			return result.getBoolean("effective")
				? PostingAccessDecision.ALLOWED : PostingAccessDecision.OUTSIDE_PERIOD;
		}, Timestamp.from(effectiveAt), Timestamp.from(effectiveAt), userId, accountId);
		return decisions.isEmpty() ? PostingAccessDecision.NOT_VISIBLE : decisions.getFirst();
	}

	@Override
	public boolean mayPost(UUID userId, UUID accountId, Instant effectiveAt) {
		return postingDecision(userId, accountId, effectiveAt) == PostingAccessDecision.ALLOWED;
	}
}
