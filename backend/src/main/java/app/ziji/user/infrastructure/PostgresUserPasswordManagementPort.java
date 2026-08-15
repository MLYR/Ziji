package app.ziji.user.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPasswordManagementPort;
import app.ziji.user.application.UserPersistenceException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/** PostgreSQL 密码适配器；按 users 行锁串行化重置与改密，只更新凭据安全字段。 */
@Repository
public class PostgresUserPasswordManagementPort implements UserPasswordManagementPort {

	private static final String FIND_BY_ID_FOR_UPDATE_SQL = """
		SELECT id, password_hash, password_hash_version, status
		FROM users
		WHERE id = ?
		FOR UPDATE
		""";

	private static final String FIND_BY_EMAIL_FOR_UPDATE_SQL = """
		SELECT id, password_hash, password_hash_version, status
		FROM users
		WHERE email_normalized = ?
		FOR UPDATE
		""";

	private static final String UPDATE_BY_EMAIL_SQL = """
		UPDATE users
		SET password_hash = ?, password_hash_version = 1,
			updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE email_normalized = ?
		RETURNING id
		""";

	private static final String UPDATE_BY_ID_SQL = """
		UPDATE users
		SET password_hash = ?, password_hash_version = 1,
			updated_at = CAST(? AS timestamptz), version = version + 1
		WHERE id = ?
		""";

	private final DSLContext dsl;

	public PostgresUserPasswordManagementPort(DSLContext dsl) {
		if (dsl == null) {
			throw new UserPersistenceException(new IllegalArgumentException("数据库访问入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public Optional<UserCredential> findByUserIdForUpdate(UUID userId) {
		if (userId == null) {
			throw new UserPersistenceException(new IllegalArgumentException("用户 ID 不能为空。"));
		}
		try {
			Record record = dsl.resultQuery(FIND_BY_ID_FOR_UPDATE_SQL, userId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toCredential(record));
		} catch (org.jooq.exception.DataAccessException | DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	@Override
	public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
		if (emailNormalized == null || emailNormalized.isBlank()) {
			throw new UserPersistenceException(new IllegalArgumentException("凭据查询邮箱不能为空。"));
		}
		try {
			Record record = dsl.resultQuery(FIND_BY_EMAIL_FOR_UPDATE_SQL, emailNormalized).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toCredential(record));
		} catch (org.jooq.exception.DataAccessException | DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	@Override
	public Optional<UUID> updatePasswordByNormalizedEmail(
		String emailNormalized,
		String passwordHash,
		Instant updatedAt) {
		validateUpdate(emailNormalized, passwordHash, updatedAt);
		try {
			Record record = dsl.resultQuery(
				UPDATE_BY_EMAIL_SQL, passwordHash, utc(updatedAt), emailNormalized).fetchOne();
			return record == null ? Optional.empty() : Optional.of(record.get("id", UUID.class));
		} catch (org.jooq.exception.DataAccessException | DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	@Override
	public void updatePasswordForUser(UUID userId, String passwordHash, Instant updatedAt) {
		if (userId == null) {
			throw new UserPersistenceException(new IllegalArgumentException("用户 ID 不能为空。"));
		}
		validateUpdate("user", passwordHash, updatedAt);
		try {
			if (dsl.execute(UPDATE_BY_ID_SQL, passwordHash, utc(updatedAt), userId) != 1) {
				throw new UserPersistenceException(new IllegalStateException("用户密码更新目标不存在。"));
			}
		} catch (org.jooq.exception.DataAccessException | DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	private static UserCredential toCredential(Record record) {
		try {
			return new UserCredential(
				record.get("id", UUID.class),
				record.get("password_hash", String.class),
				record.get("password_hash_version", Integer.class),
				UserCredentialStatus.valueOf(record.get("status", String.class)));
		} catch (IllegalArgumentException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	private static void validateUpdate(String subject, String passwordHash, Instant updatedAt) {
		if (subject == null || passwordHash == null || passwordHash.isBlank() || updatedAt == null) {
			throw new UserPersistenceException(new IllegalArgumentException("密码更新参数无效。"));
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
