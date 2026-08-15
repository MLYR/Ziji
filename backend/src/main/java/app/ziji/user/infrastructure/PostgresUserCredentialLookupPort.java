package app.ziji.user.infrastructure;

import java.util.Optional;
import java.util.UUID;

import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialLookupPort;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPersistenceException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/**
 * users 凭据查询适配器；只按规范化邮箱读取认证所需列，不查询资料、账务数据或 Token，也不记录邮箱、Hash 或密码。
 * jOOQ 类型只在本 infrastructure 实现内出现，不泄漏到 user application DTO 或 auth 模块。
 */
@Repository
public class PostgresUserCredentialLookupPort implements UserCredentialLookupPort {

	private static final String SELECT_SQL = """
		SELECT id, password_hash, password_hash_version, status
		FROM users
		WHERE email_normalized = ?
		""";

	private static final String SELECT_FOR_UPDATE_SQL = """
		SELECT id, password_hash, password_hash_version, status
		FROM users
		WHERE email_normalized = ?
		FOR UPDATE
		""";

	private final DSLContext dsl;

	public PostgresUserCredentialLookupPort(DSLContext dsl) {
		if (dsl == null) {
			throw new UserPersistenceException(new IllegalArgumentException("数据库访问入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public Optional<UserCredential> findByNormalizedEmail(String emailNormalized) {
		return find(emailNormalized, SELECT_SQL);
	}

	@Override
	public Optional<UserCredential> findByNormalizedEmailForUpdate(String emailNormalized) {
		return find(emailNormalized, SELECT_FOR_UPDATE_SQL);
	}

	private Optional<UserCredential> find(String emailNormalized, String sql) {
		if (emailNormalized == null || emailNormalized.isBlank()) {
			throw new UserPersistenceException(new IllegalArgumentException("凭据查询邮箱不能为空。"));
		}
		try {
			Record record = dsl.resultQuery(sql, emailNormalized).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toCredential(record));
		} catch (DataAccessException exception) {
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
			// 未知账号状态属于数据完整性问题，按持久化异常上抛而非静默按可认证处理。
			throw new UserPersistenceException(exception);
		}
	}
}
