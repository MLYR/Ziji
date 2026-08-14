package app.ziji.auth.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.RefreshTokenSessionState;
import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.RefreshTokenHash;
import app.ziji.auth.domain.SessionRevocationReason;
import app.ziji.auth.domain.StoredRefreshToken;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Repository;

/** PostgreSQL 会话适配器；所有状态转换依赖同一 REQUIRED 事务和行锁，而非 JVM 内存锁。 */
@Repository
public class PostgresDeviceSessionStore implements DeviceSessionStore {

	private static final String FIND_ACTIVE_SESSION_IDS_FOR_UPDATE_SQL = """
		SELECT id
		FROM user_sessions
		WHERE user_id = ? AND device_id = ? AND revoked_at IS NULL
		ORDER BY id
		FOR UPDATE
		""";

	private static final String REVOKE_SESSION_SQL = """
		UPDATE user_sessions
		SET revoked_at = CAST(? AS timestamptz), revoke_reason = ?
		WHERE id = ? AND revoked_at IS NULL
		""";

	private static final String FIND_CURRENT_REFRESH_TOKEN_IDS_FOR_UPDATE_SQL = """
		SELECT id
		FROM session_refresh_tokens
		WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
		ORDER BY id
		FOR UPDATE
		""";

	private static final String REVOKE_CURRENT_REFRESH_TOKEN_SQL = """
		UPDATE session_refresh_tokens
		SET revoked_at = CAST(? AS timestamptz)
		WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
		""";

	private static final String INSERT_SESSION_SQL = """
		INSERT INTO user_sessions
			(id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at)
		VALUES (?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL, CAST(? AS timestamptz))
		""";

	private static final String INSERT_REFRESH_TOKEN_SQL = """
		INSERT INTO session_refresh_tokens
			(id, session_id, token_hash, issued_at, expires_at, consumed_at, revoked_at, replaced_by_id, created_at)
		VALUES (?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL, NULL, NULL, CAST(? AS timestamptz))
		""";

	private static final String FIND_REFRESH_TOKEN_SESSION_ID_SQL = """
		SELECT session_id
		FROM session_refresh_tokens
		WHERE token_hash = ?
		""";

	private static final String FIND_SESSION_FOR_UPDATE_SQL = """
		SELECT id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at,
			security_baseline_version
		FROM user_sessions
		WHERE id = ?
		FOR UPDATE
		""";

	private static final String FIND_SESSION_FOR_USER_FOR_UPDATE_SQL = """
		SELECT id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at,
			security_baseline_version
		FROM user_sessions
		WHERE id = ? AND user_id = ?
		FOR UPDATE
		""";

	private static final String FIND_ACTIVE_SESSIONS_FOR_USER_FOR_UPDATE_SQL = """
		SELECT id, user_id, device_id, device_name, issued_at, expires_at, revoked_at, revoke_reason, last_seen_at,
			security_baseline_version
		FROM user_sessions
		WHERE user_id = ? AND revoked_at IS NULL
		ORDER BY id
		FOR UPDATE
		""";

	private static final String FIND_REFRESH_TOKEN_FOR_UPDATE_SQL = """
		SELECT id AS token_id, session_id AS token_session_id, token_hash, issued_at AS token_issued_at,
			expires_at AS token_expires_at, consumed_at, revoked_at AS token_revoked_at,
			replaced_by_id, created_at AS token_created_at
		FROM session_refresh_tokens
		WHERE token_hash = ? AND session_id = ?
		FOR UPDATE
		""";

	private static final String CONSUME_REFRESH_TOKEN_SQL = """
		UPDATE session_refresh_tokens
		SET consumed_at = CAST(? AS timestamptz)
		WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
		""";

	private static final String LINK_REPLACEMENT_SQL = """
		UPDATE session_refresh_tokens
		SET replaced_by_id = ?
		WHERE id = ? AND consumed_at IS NOT NULL AND replaced_by_id IS NULL
		""";

	private static final String UPDATE_LAST_SEEN_SQL = """
		UPDATE user_sessions
		SET last_seen_at = CAST(? AS timestamptz)
		WHERE id = ? AND revoked_at IS NULL
			AND expires_at > CAST(? AS timestamptz)
			AND last_seen_at <= CAST(? AS timestamptz)
		""";

	private final DSLContext dsl;

	public PostgresDeviceSessionStore(DSLContext dsl) {
		if (dsl == null) {
			throw new AuthInfrastructureException("会话数据库访问入口不能为空。");
		}
		this.dsl = dsl;
	}

	@Override
	public void revokeActiveSessionForReplacement(UUID userId, String deviceId, Instant revokedAt) {
		try {
			// 先按稳定顺序锁定旧会话，再锁定其当前 Token；新会话 INSERT 与此处必须由调用方放在同一事务。
			List<UUID> sessionIds = dsl.resultQuery(FIND_ACTIVE_SESSION_IDS_FOR_UPDATE_SQL, userId, deviceId)
				.fetch("id", UUID.class);
			for (UUID sessionId : sessionIds) {
				dsl.execute(REVOKE_SESSION_SQL, utc(revokedAt), SessionRevocationReason.REPLACED_BY_LOGIN.name(), sessionId);
				revokeCurrentRefreshTokensLocked(sessionId, revokedAt);
			}
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("稳定会话替换失败。", exception);
		}
	}

	@Override
	public void insertSession(DeviceSession session) {
		try {
			dsl.execute(INSERT_SESSION_SQL,
				session.id(), session.userId(), session.deviceId(), session.deviceName(), utc(session.issuedAt()),
				utc(session.expiresAt()), utc(session.lastSeenAt()));
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("稳定会话写入失败。", exception);
		}
	}

	@Override
	public void insertRefreshToken(StoredRefreshToken refreshToken) {
		try {
			dsl.execute(INSERT_REFRESH_TOKEN_SQL,
				refreshToken.id(), refreshToken.sessionId(), refreshToken.tokenHash(), utc(refreshToken.issuedAt()),
				utc(refreshToken.expiresAt()), utc(refreshToken.createdAt()));
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("刷新凭据写入失败。", exception);
		}
	}

	@Override
	public Optional<RefreshTokenSessionState> findRefreshTokenForUpdate(String tokenHash) {
		try {
			Record tokenReference = dsl.resultQuery(FIND_REFRESH_TOKEN_SESSION_ID_SQL, tokenHash).fetchOne();
			if (tokenReference == null) {
				return Optional.empty();
			}
			// 所有旋转和撤销都先锁 session 再锁 Token，避免并发撤销与轮换互相等待形成死锁。
			DeviceSession session = findSessionForUpdate(tokenReference.get("session_id", UUID.class));
			if (session == null) {
				return Optional.empty();
			}
			Record token = dsl.resultQuery(FIND_REFRESH_TOKEN_FOR_UPDATE_SQL, tokenHash, session.id()).fetchOne();
			return token == null ? Optional.empty() : Optional.of(new RefreshTokenSessionState(session, toRefreshToken(token)));
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("刷新凭据查询失败。", exception);
		}
	}

	@Override
	public Optional<DeviceSession> findSessionForUserForUpdate(UUID userId, UUID sessionId) {
		try {
			Record record = dsl.resultQuery(FIND_SESSION_FOR_USER_FOR_UPDATE_SQL, sessionId, userId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toSession(record));
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("设备会话查询失败。", exception);
		}
	}

	@Override
	public List<DeviceSession> findActiveSessionsForUserForUpdate(UUID userId) {
		try {
			List<DeviceSession> sessions = new ArrayList<>();
			for (Record record : dsl.resultQuery(FIND_ACTIVE_SESSIONS_FOR_USER_FOR_UPDATE_SQL, userId).fetch()) {
				sessions.add(toSession(record));
			}
			return List.copyOf(sessions);
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("活动设备会话查询失败。", exception);
		}
	}

	@Override
	public boolean revokeSession(UUID sessionId, Instant revokedAt, SessionRevocationReason reason) {
		try {
			return dsl.execute(REVOKE_SESSION_SQL, utc(revokedAt), requireReason(reason), sessionId) == 1;
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("设备会话撤销失败。", exception);
		}
	}

	@Override
	public void revokeCurrentRefreshTokens(UUID sessionId, Instant revokedAt) {
		try {
			// 显式锁定全部当前 Token，再逐条撤销，保证设备撤销和刷新轮换在同一顺序上串行。
			revokeCurrentRefreshTokensLocked(sessionId, revokedAt);
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("当前刷新凭据撤销失败。", exception);
		}
	}

	@Override
	public boolean consumeRefreshToken(UUID tokenId, Instant consumedAt) {
		try {
			return dsl.execute(CONSUME_REFRESH_TOKEN_SQL, utc(consumedAt), tokenId) == 1;
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("刷新凭据消费失败。", exception);
		}
	}

	@Override
	public boolean linkReplacement(UUID tokenId, UUID replacementTokenId) {
		try {
			return dsl.execute(LINK_REPLACEMENT_SQL, replacementTokenId, tokenId) == 1;
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("刷新凭据轮换关联失败。", exception);
		}
	}

	@Override
	public boolean updateLastSeen(UUID sessionId, Instant lastSeenAt) {
		try {
			return dsl.execute(UPDATE_LAST_SEEN_SQL, utc(lastSeenAt), sessionId, utc(lastSeenAt), utc(lastSeenAt)) == 1;
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("会话最后活动时间更新失败。", exception);
		}
	}

	private DeviceSession findSessionForUpdate(UUID sessionId) {
		Record record = dsl.resultQuery(FIND_SESSION_FOR_UPDATE_SQL, sessionId).fetchOne();
		return record == null ? null : toSession(record);
	}

	private void revokeCurrentRefreshTokensLocked(UUID sessionId, Instant revokedAt) {
		List<UUID> tokenIds = dsl.resultQuery(FIND_CURRENT_REFRESH_TOKEN_IDS_FOR_UPDATE_SQL, sessionId)
			.fetch("id", UUID.class);
		for (UUID tokenId : tokenIds) {
			dsl.execute(REVOKE_CURRENT_REFRESH_TOKEN_SQL, utc(revokedAt), tokenId);
		}
	}

	private static DeviceSession toSession(Record record) {
		return DeviceSession.restore(
			record.get("id", UUID.class),
			record.get("user_id", UUID.class),
			record.get("device_id", String.class),
			record.get("device_name", String.class),
			instant(record.get("issued_at", OffsetDateTime.class)),
			instant(record.get("expires_at", OffsetDateTime.class)),
			instantNullable(record.get("revoked_at", OffsetDateTime.class)),
			record.get("revoke_reason", String.class),
			instant(record.get("last_seen_at", OffsetDateTime.class)),
			record.get("security_baseline_version", Integer.class));
	}

	private static StoredRefreshToken toRefreshToken(Record record) {
		return StoredRefreshToken.restore(
			record.get("token_id", UUID.class),
			record.get("token_session_id", UUID.class),
			RefreshTokenHash.restore(record.get("token_hash", String.class)),
			instant(record.get("token_issued_at", OffsetDateTime.class)),
			instant(record.get("token_expires_at", OffsetDateTime.class)),
			instantNullable(record.get("consumed_at", OffsetDateTime.class)),
			instantNullable(record.get("token_revoked_at", OffsetDateTime.class)),
			record.get("replaced_by_id", UUID.class),
			instant(record.get("token_created_at", OffsetDateTime.class)));
	}

	private static String requireReason(SessionRevocationReason reason) {
		if (reason == null) {
			throw new AuthInfrastructureException("会话撤销原因不能为空。");
		}
		return reason.name();
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new AuthInfrastructureException("会话时间不能为空。");
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		if (value == null) {
			throw new AuthInfrastructureException("会话时间读取失败。");
		}
		return value.toInstant();
	}

	private static Instant instantNullable(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
