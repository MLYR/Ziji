package app.ziji.user.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.util.Optional;
import java.util.UUID;

import app.ziji.user.application.UserProfileStore;
import app.ziji.user.application.UserPersistenceException;
import app.ziji.user.domain.AmountFormat;
import app.ziji.user.domain.BaseCurrency;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import app.ziji.user.domain.UserStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/** 只读取非敏感用户资料列，并用 PostgreSQL 条件更新实现乐观锁。 */
@Repository
public final class PostgresUserProfileStore implements UserProfileStore {

	private static final String SELECT_SQL = """
		SELECT id, email, nickname, timezone, base_currency, locale, amount_format, status, version
		FROM users
		WHERE id = ?
		""";

	private static final String UPDATE_SQL = """
		UPDATE users
		SET nickname = COALESCE(?, nickname),
			timezone = COALESCE(?, timezone),
			base_currency = COALESCE(?, base_currency),
			locale = COALESCE(?, locale),
			amount_format = COALESCE(?, amount_format),
			updated_at = ?,
			version = version + 1
		WHERE id = ? AND version = ?
		RETURNING id, email, nickname, timezone, base_currency, locale, amount_format, status, version
		""";

	private final DSLContext dsl;

	public PostgresUserProfileStore(DSLContext dsl) {
		if (dsl == null) {
			throw new UserPersistenceException(new IllegalArgumentException("数据库访问入口不能为空。"));
		}
		this.dsl = dsl;
	}

	@Override
	public Optional<UserProfile> findById(UUID userId) {
		try {
			Record record = dsl.resultQuery(SELECT_SQL, userId).fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	@Override
	public Optional<UserProfile> updateIfVersion(
		UUID userId,
		int expectedVersion,
		UserProfilePatch patch,
		Instant updatedAt) {
		if (userId == null || patch == null || updatedAt == null || expectedVersion < 1) {
			throw new UserPersistenceException(new IllegalArgumentException("用户资料更新参数无效。"));
		}
		try {
			Record record = dsl.resultQuery(
				UPDATE_SQL,
				patch.nickname().orElse(null),
				patch.timezone().map(ZoneId::getId).orElse(null),
				patch.baseCurrency().map(BaseCurrency::name).orElse(null),
				patch.locale().orElse(null),
				patch.amountFormat().map(AmountFormat::name).orElse(null),
				utc(updatedAt), userId, expectedVersion)
				.fetchOne();
			return record == null ? Optional.empty() : Optional.of(toDomain(record));
		} catch (DataAccessException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	private static UserProfile toDomain(Record record) {
		try {
			return new UserProfile(
				record.get("id", UUID.class),
				record.get("email", String.class),
				record.get("nickname", String.class),
				ZoneId.of(record.get("timezone", String.class)),
				BaseCurrency.valueOf(record.get("base_currency", String.class)),
				record.get("locale", String.class),
				AmountFormat.valueOf(record.get("amount_format", String.class)),
				UserStatus.valueOf(record.get("status", String.class)),
				record.get("version", Integer.class));
		} catch (DateTimeException | IllegalArgumentException exception) {
			throw new UserPersistenceException(exception);
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
