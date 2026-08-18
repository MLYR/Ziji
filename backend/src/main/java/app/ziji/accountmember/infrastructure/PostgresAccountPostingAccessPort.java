package app.ziji.accountmember.infrastructure;

import java.util.List;
import java.util.UUID;

import app.ziji.accountmember.application.AccountPostingAccessPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用数据库请求时当前 ACTIVE membership 执行 fail-closed 的账务写入授权。 */
@Repository
public class PostgresAccountPostingAccessPort implements AccountPostingAccessPort {

	private final JdbcTemplate jdbc;
	private static final String POSTING_DECISION_SQL = """
		/* 权限只看请求时当前 ACTIVE membership；历史 businessAt 不能回溯成员生效周期。 */
		SELECT m.role
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
	public PostingAccessDecision postingDecision(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			return PostingAccessDecision.NOT_VISIBLE;
		}
		List<PostingAccessDecision> decisions = jdbc.query(POSTING_DECISION_SQL, (result, rowNum) -> {
			String role = result.getString("role");
			if (!"OWNER".equals(role) && !"EDITOR".equals(role)) {
				return PostingAccessDecision.READ_ONLY;
			}
			return PostingAccessDecision.ALLOWED;
		}, userId, accountId);
		return decisions.isEmpty() ? PostingAccessDecision.NOT_VISIBLE : decisions.getFirst();
	}

	@Override
	public boolean mayPost(UUID userId, UUID accountId) {
		return postingDecision(userId, accountId) == PostingAccessDecision.ALLOWED;
	}
}
