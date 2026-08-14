package app.ziji.auth.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.RefreshTokenSessionState;
import app.ziji.auth.domain.DeviceId;
import app.ziji.auth.domain.DeviceName;
import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.RefreshTokenHash;
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
		FOR UPDATE
		""";

	private static final String REVOKE_SESSION_FOR_REPLACEMENT_SQL = """
		UPDATE user_sessions
		SET revoked_at = CAST(? AS timestamptz), revoke_reason = 'REPLACED_BY_LOGIN'
		WHERE id = ? AND revoked_at IS NULL
		""";

	private static final String REVOKE_CURRENT_REFRESH_TOKENS_SQL = """
		UPDATE session_refresh_tokens
		SET revoked_at = CAST(? AS timestamptz)
		WHERE session_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
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

	private static final String FIND_REFRESH_TOKEN_FOR_UPDATE_SQL = """
		SELECT r.id AS token_id, r.session_id AS token_session_id, r.token_hash, r.issued_at AS token_issued_at,
			r.expires_at AS token_expires_at, r.consumed_at, r.revoked_at AS token_revoked_at,
			r.replaced_by_id, r.created_at AS token_created_at,
			s.id AS session_id, s.user_id, s.device_id, s.device_name, s.issued_at AS session_issued_at,
			s.expires_at AS session_expires_at, s.revoked_at AS session_revoked_at,
			s.revoke_reason, s.last_seen_at
		FROM session_refresh_tokens r
		JOIN user_sessions s ON s.id = r.session_id
		WHERE r.token_hash = ?
		FOR UPDATE OF r, s
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
			// 先对旧会话取行锁，再撤销其当前 Token；新会话 INSERT 与此处必须由调用方放在同一事务。
			List<UUID> sessionIds = dsl.resultQuery(FIND_ACTIVE_SESSION_IDS_FOR_UPDATE_SQL, userId, deviceId)
				.fetch("id", UUID.class);
			for (UUID sessionId : sessionIds) {
				dsl.execute(REVOKE_SESSION_FOR_REPLACEMENT_SQL, utc(revokedAt), sessionId);
				dsl.execute(REVOKE_CURRENT_REFRESH_TOKENS_SQL, utc(revokedAt), sessionId);
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
			Record record = dsl.resultQuery(FIND_REFRESH_TOKEN_FOR_UPDATE_SQL, tokenHash).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toState(record));
		} catch (DataAccessException exception) {
			throw new AuthInfrastructureException("刷新凭据查询失败。", exception);
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

	private static RefreshTokenSessionState toState(Record record) {
		DeviceSession session = DeviceSession.restore(
			record.get("session_id", UUID.class),
			record.get("user_id", UUID.class),
			DeviceId.ofNullable(record.get("device_id", String.class)),
			DeviceName.of(record.get("device_name", String.class)),
			instant(record.get("session_issued_at", OffsetDateTime.class)),
			instant(record.get("session_expires_at", OffsetDateTime.class)),
			instantNullable(record.get("session_revoked_at", OffsetDateTime.class)),
			record.get("revoke_reason", String.class),
			instant(record.get("last_seen_at", OffsetDateTime.class)));
		StoredRefreshToken refreshToken = StoredRefreshToken.restore(
			record.get("token_id", UUID.class),
			record.get("token_session_id", UUID.class),
			RefreshTokenHash.restore(record.get("token_hash", String.class)),
			instant(record.get("token_issued_at", OffsetDateTime.class)),
			instant(record.get("token_expires_at", OffsetDateTime.class)),
			instantNullable(record.get("consumed_at", OffsetDateTime.class)),
			instantNullable(record.get("token_revoked_at", OffsetDateTime.class)),
			record.get("replaced_by_id", UUID.class),
			instant(record.get("token_created_at", OffsetDateTime.class)));
		return new RefreshTokenSessionState(session, refreshToken);
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
