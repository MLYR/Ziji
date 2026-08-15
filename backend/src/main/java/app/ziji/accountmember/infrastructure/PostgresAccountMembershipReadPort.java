package app.ziji.accountmember.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读取 account_members 与 account_inclusion_settings 的当前可见成员周期。 */
@Repository
public class PostgresAccountMembershipReadPort implements AccountMembershipReadPort {

	private static final String LIST_ACTIVE_SQL = """
		SELECT m.account_id, m.role, s.ratio
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ? AND m.status = 'ACTIVE' AND s.valid_to IS NULL
		ORDER BY m.account_id
		""";

	private static final String FIND_ACTIVE_SQL = """
		SELECT m.role, s.ratio
		FROM account_members m
		JOIN account_inclusion_settings s ON s.membership_id = m.id
		WHERE m.user_id = ? AND m.account_id = ? AND m.status = 'ACTIVE' AND s.valid_to IS NULL
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
}
