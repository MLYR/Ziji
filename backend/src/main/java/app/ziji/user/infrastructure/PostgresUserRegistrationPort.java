package app.ziji.user.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import app.ziji.user.application.UserEmailAlreadyExistsException;
import app.ziji.user.application.UserPersistenceException;
import app.ziji.user.application.UserRegistrationCommand;
import app.ziji.user.application.UserRegistrationPort;
import org.jooq.DSLContext;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/** users 写入适配器；以数据库邮箱唯一约束作为跨实例注册并发的最终权威。 */
@Repository
public class PostgresUserRegistrationPort implements UserRegistrationPort {

	private static final String INSERT_SQL = """
		INSERT INTO users
			(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status,
			 created_at, updated_at, version)
		VALUES (?, ?, ?, CAST(? AS timestamptz), ?, 1, ?, ?, ?, ?, 'STANDARD', 'ACTIVE',
			CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
		ON CONFLICT ON CONSTRAINT uq_users_email_normalized DO NOTHING
		""";

	private final DSLContext dsl;

	public PostgresUserRegistrationPort(DSLContext dsl) {
		if (dsl == null) {
			throw new UserPersistenceException(new IllegalArgumentException("数据库访问入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public void register(UserRegistrationCommand command) {
		if (command == null) {
			throw new UserPersistenceException(new IllegalArgumentException("用户注册参数不能为空。"));
		}
		try {
			int inserted = dsl.execute(
				INSERT_SQL,
				command.userId(), command.email(), command.emailNormalized(), utc(command.registeredAt()),
				command.passwordHash(), command.nickname(), command.timezone().getId(),
				command.baseCurrency().name(), command.locale(), utc(command.registeredAt()),
				utc(command.registeredAt()));
			if (inserted == 0) {
				// ON CONFLICT 精确指向 email_normalized，不能把其他数据库失败误报为邮箱重复。
				throw new UserEmailAlreadyExistsException();
			}
		} catch (DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	private static OffsetDateTime utc(java.time.Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
