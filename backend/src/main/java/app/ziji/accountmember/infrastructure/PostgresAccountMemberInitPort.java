package app.ziji.accountmember.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import app.ziji.accountmember.application.AccountMemberInitPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 在同一事务内写入 OWNER 成员和 100% 计入设置；
 * V007 validate_inclusion_creator 要求 inclusion 的 created_by 等于 membership 的 user_id。
 */
@Repository
public class PostgresAccountMemberInitPort implements AccountMemberInitPort {

	private final JdbcTemplate jdbc;

	public PostgresAccountMemberInitPort(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public UUID initializeOwnerMembership(UUID accountId, UUID userId, Instant now) {
		if (accountId == null || userId == null || now == null) {
			throw new IllegalArgumentException("账户 ID、用户 ID 和时间不能为空。");
		}
		Timestamp ts = Timestamp.from(now);
		// 服务端生成成员 ID；membership_no=1 表示第一个成员周期。
		UUID membershipId = UUID.randomUUID();

		// 写入 ACTIVE OWNER 成员；joined_at 使用传入的统一时间戳。
		jdbc.update("""
			INSERT INTO account_members
			(id, account_id, user_id, role, status, joined_at, membership_no, version)
			VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
			""", membershipId, accountId, userId, ts);
		return membershipId;
	}

	@Override
	public void initializeInitialInclusion(UUID membershipId, UUID userId, Instant now) {
		if (membershipId == null || userId == null || now == null) {
			throw new IllegalArgumentException("成员 ID、用户 ID 和时间不能为空。");
		}
		Timestamp ts = Timestamp.from(now);
		UUID inclusionId = UUID.randomUUID();
		// 写入 100% 当前计入设置；included=true, ratio=1.000000。
		// V007 validate_inclusion_creator 校验 created_by 与 membership.user_id 一致。
		jdbc.update("""
			INSERT INTO account_inclusion_settings
			(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
			""", inclusionId, membershipId, ts, userId, ts);
	}
}
