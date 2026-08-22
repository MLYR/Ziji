package app.ziji.accountmember.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountRecipientReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

	/** 只读取 account_members 与 account_inclusion_settings 的成员周期。 */
@Repository
public class PostgresAccountMembershipReadPort implements AccountMembershipReadPort, AccountRecipientReadPort {

	private static final String LIST_ACTIVE_SQL = """
		/* 当前 ACTIVE 还必须处于 membership 周期内，避免 status 未同步时泄漏已结束成员事实。 */
		SELECT m.account_id, m.role, s.ratio
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ? AND m.status = 'ACTIVE' AND s.valid_to IS NULL
		  AND m.joined_at <= CURRENT_TIMESTAMP
		  AND (m.ended_at IS NULL OR m.ended_at > CURRENT_TIMESTAMP)
		  AND s.valid_from <= CURRENT_TIMESTAMP
		ORDER BY m.account_id
		""";

	private static final String FIND_ACTIVE_SQL = """
		/* 详情读取与列表使用同一当前周期边界，不能仅依赖 status='ACTIVE'。 */
		SELECT m.role, s.ratio
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ? AND m.account_id = ? AND m.status = 'ACTIVE' AND s.valid_to IS NULL
		  AND m.joined_at <= CURRENT_TIMESTAMP
		  AND (m.ended_at IS NULL OR m.ended_at > CURRENT_TIMESTAMP)
		  AND s.valid_from <= CURRENT_TIMESTAMP
		""";

	private static final String FIND_ACTIVE_FOR_UPDATE_SQL = """
		/* 锁等待结束后用数据库墙上时钟重判当前周期，不能用事务开始时间放行已撤权成员。 */
		SELECT m.role, s.ratio
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ? AND m.account_id = ? AND m.status = 'ACTIVE' AND s.valid_to IS NULL
		  AND m.joined_at <= clock_timestamp()
		  AND (m.ended_at IS NULL OR m.ended_at > clock_timestamp())
		  AND s.valid_from <= clock_timestamp()
		""";

	private static final String LOCK_MEMBERSHIPS_SQL = """
		/* 先锁定该用户在账户上的全部成员周期，再用新命令读取锁释放后的当前状态。 */
		SELECT m.id
		FROM account_members m
		WHERE m.user_id = ? AND m.account_id = ?
		ORDER BY m.joined_at DESC, m.id DESC
		FOR UPDATE OF m
		""";

	private final JdbcTemplate jdbc;

	public PostgresAccountMembershipReadPort(JdbcTemplate jdbc) {
		if (jdbc == null) {
			throw new IllegalArgumentException("账户成员读取入口不能为空。");
		}
		this.jdbc = jdbc;
	}

	@Override
	public List<ActiveMembership> listActiveMemberships(UUID userId) {
		if (userId == null) {
			return List.of();
		}
		return jdbc.query(LIST_ACTIVE_SQL, (result, rowNum) -> new ActiveMembership(
			result.getObject("account_id", UUID.class),
			result.getString("role"),
			result.getBigDecimal("ratio")), userId);
	}

	@Override
	public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			return Optional.empty();
		}
		List<ActiveMembership> memberships = jdbc.query(FIND_ACTIVE_SQL, (result, rowNum) -> new ActiveMembership(
			accountId,
			result.getString("role"),
			result.getBigDecimal("ratio")), userId, accountId);
		return memberships.isEmpty() ? Optional.empty() : Optional.of(memberships.getFirst());
	}

	@Override
	public Optional<ActiveMembership> findActiveMembershipForUpdate(UUID userId, UUID accountId) {
		if (userId == null || accountId == null) {
			return Optional.empty();
		}
		// 当前周期查询必须在锁等待完成后作为新命令执行，避免旧事务时间和旧快照共同放行撤权成员。
		jdbc.query(LOCK_MEMBERSHIPS_SQL, (result, rowNum) -> result.getObject("id", UUID.class), userId, accountId);
		List<ActiveMembership> memberships = jdbc.query(FIND_ACTIVE_FOR_UPDATE_SQL, (result, rowNum) -> new ActiveMembership(
			accountId,
			result.getString("role"),
			result.getBigDecimal("ratio")), userId, accountId);
		return memberships.isEmpty() ? Optional.empty() : Optional.of(memberships.getFirst());
	}

	@Override
	public List<UUID> listRecipientUserIdsAt(UUID accountId, Instant occurredAt) {
		if (accountId == null || occurredAt == null) {
			return List.of();
		}
		return jdbc.query("""
			SELECT DISTINCT m.user_id
			FROM account_members m
			JOIN account_inclusion_settings s ON s.membership_id = m.id
			WHERE m.account_id = ?
			  AND s.included = TRUE
			  AND m.joined_at <= CAST(? AS timestamptz)
			  AND (m.ended_at IS NULL OR CAST(? AS timestamptz) < m.ended_at)
			  AND s.valid_from <= CAST(? AS timestamptz)
			  AND (s.valid_to IS NULL OR CAST(? AS timestamptz) < s.valid_to)
			ORDER BY m.user_id
			""", (result, rowNum) -> result.getObject("user_id", UUID.class), accountId,
			java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt),
			java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt));
	}
}
