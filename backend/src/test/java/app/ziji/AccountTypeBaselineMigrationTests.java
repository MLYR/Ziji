package app.ziji;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V012 数据库基线：冻结 FUND/CONSUMER_LOAN，并校验账户 class/type 合法矩阵。 */
@Testcontainers
class AccountTypeBaselineMigrationTests {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-15T02:00:00Z");
	private static final List<String> ACCOUNT_CLASSES = List.of("ASSET", "INVESTMENT", "LIABILITY");
	private static final List<String> ACCOUNT_TYPES = List.of(
		"BANK", "WECHAT", "ALIPAY", "CASH", "BROKERAGE", "FUND", "CREDIT_CARD", "LOAN", "CONSUMER_LOAN", "OTHER");
	private static final Map<String, List<String>> ALLOWED_TYPES = Map.of(
		"ASSET", List.of("BANK", "WECHAT", "ALIPAY", "CASH", "OTHER"),
		"INVESTMENT", List.of("BROKERAGE", "FUND", "OTHER"),
		"LIABILITY", List.of("CREDIT_CARD", "LOAN", "CONSUMER_LOAN", "OTHER"));
	private static final List<LegacyAccount> LEGACY_ACCOUNTS = List.of(
		new LegacyAccount("ASSET", "BANK", "工资卡"),
		new LegacyAccount("ASSET", "WECHAT", "微信钱包"),
		new LegacyAccount("ASSET", "ALIPAY", "支付宝"),
		new LegacyAccount("ASSET", "CASH", "现金"),
		new LegacyAccount("INVESTMENT", "BROKERAGE", "证券账户"),
		new LegacyAccount("LIABILITY", "CREDIT_CARD", "信用卡"),
		new LegacyAccount("LIABILITY", "LOAN", "亲友借款"),
		new LegacyAccount("ASSET", "OTHER", "其他现金"),
		new LegacyAccount("INVESTMENT", "OTHER", "其他投资"),
		new LegacyAccount("LIABILITY", "OTHER", "其他负债"));

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer EMPTY_POSTGRES = newContainer("ziji_account_type_empty");

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer UPGRADE_POSTGRES = newContainer("ziji_account_type_upgrade");

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer ILLEGAL_HISTORY_POSTGRES = newContainer("ziji_account_type_illegal");

	@Test
	void emptyDatabaseMigratesToV012AndEnforcesFrozenMatrix() throws Exception {
		migrate(EMPTY_POSTGRES, null);
		try (Connection connection = connection(EMPTY_POSTGRES)) {
			assertNamedConstraintsValidated(connection);
			UUID userId = insertUser(connection, "empty-matrix");
			int legalCount = 0;
			for (String accountClass : ACCOUNT_CLASSES) {
				for (String accountType : ACCOUNT_TYPES) {
					if (!isAllowed(accountClass, accountType)) {
						continue;
					}
					insertCompleteAccount(connection, userId, accountClass, accountType, accountClass + "-" + accountType);
					legalCount++;
				}
			}
			assertEquals(12, legalCount);
			assertEquals(12, count(connection, "SELECT COUNT(*) FROM accounts"));
			for (String accountClass : ACCOUNT_CLASSES) {
				for (String accountType : ACCOUNT_TYPES) {
					if (isAllowed(accountClass, accountType)) {
						continue;
					}
					assertRejected(connection, userId, accountClass, accountType);
				}
			}
			assertRejected(connection, userId, "INVESTMENT", "MUTUAL_FUND");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM accounts WHERE account_type = 'FUND'"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM accounts WHERE account_type = 'CONSUMER_LOAN'"));
		}
	}

	@Test
	void v011UpgradeKeepsLegacyTypesAndAcceptsNewFrozenCodes() throws Exception {
		migrate(UPGRADE_POSTGRES, "11");
		Map<UUID, LegacyAccount> inserted = new LinkedHashMap<>();
		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			UUID userId = insertUser(connection, "upgrade-legacy");
			for (LegacyAccount account : LEGACY_ACCOUNTS) {
				UUID accountId = insertCompleteAccount(
					connection, userId, account.accountClass(), account.accountType(), account.name());
				inserted.put(accountId, account);
			}
			assertEquals(10, inserted.size());
		}

		migrate(UPGRADE_POSTGRES, null);

		try (Connection connection = connection(UPGRADE_POSTGRES)) {
			assertNamedConstraintsValidated(connection);
			assertEquals(10, count(connection, "SELECT COUNT(*) FROM accounts"));
			for (Map.Entry<UUID, LegacyAccount> entry : inserted.entrySet()) {
				assertEquals(1, count(connection, """
					SELECT COUNT(*) FROM accounts
					WHERE id = '%s' AND account_class = '%s' AND account_type = '%s' AND name = '%s'
					""".formatted(
					entry.getKey(),
					entry.getValue().accountClass(),
					entry.getValue().accountType(),
					entry.getValue().name())));
			}
			UUID userId = findUser(connection, "upgrade-legacy@example.test");
			insertCompleteAccount(connection, userId, "INVESTMENT", "FUND", "场外基金");
			insertCompleteAccount(connection, userId, "LIABILITY", "CONSUMER_LOAN", "消费贷款");
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM accounts WHERE account_type = 'FUND'"));
			assertEquals(1, count(connection, "SELECT COUNT(*) FROM accounts WHERE account_type = 'CONSUMER_LOAN'"));
			for (String accountClass : ACCOUNT_CLASSES) {
				for (String accountType : ACCOUNT_TYPES) {
					if (isAllowed(accountClass, accountType)) {
						continue;
					}
					assertRejected(connection, userId, accountClass, accountType);
				}
			}
		}
	}

	@Test
	void illegalHistoricalPairCausesV012ToFail() throws Exception {
		migrate(ILLEGAL_HISTORY_POSTGRES, "11");
		try (Connection connection = connection(ILLEGAL_HISTORY_POSTGRES)) {
			UUID userId = insertUser(connection, "illegal-history");
			// V011 只检查 account_type 枚举，允许跨类写入；V012 必须因此安全失败。
			insertCompleteAccount(connection, userId, "ASSET", "BROKERAGE", "非法历史配对");
		}
		Exception exception = assertThrows(Exception.class, () -> migrate(ILLEGAL_HISTORY_POSTGRES, null));
		assertTrue(containsConstraintName(exception), exception.toString());
	}

	private static org.testcontainers.postgresql.PostgreSQLContainer newContainer(String databaseName) {
		return new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName(databaseName)
			.withUsername("ziji")
			.withPassword("ziji-test");
	}

	private static void migrate(org.testcontainers.postgresql.PostgreSQLContainer postgres, String target) {
		var configuration = Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private static Connection connection(org.testcontainers.postgresql.PostgreSQLContainer postgres) throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	private static boolean isAllowed(String accountClass, String accountType) {
		return ALLOWED_TYPES.get(accountClass).contains(accountType);
	}

	private static void assertNamedConstraintsValidated(Connection connection) throws SQLException {
		assertEquals(0, count(connection, """
			SELECT COUNT(*) FROM pg_constraint
			WHERE conrelid = 'accounts'::regclass AND conname = 'accounts_account_type_check'
			"""));
		assertEquals(2, count(connection, """
			SELECT COUNT(*) FROM pg_constraint
			WHERE conrelid = 'accounts'::regclass
			  AND conname IN ('ck_accounts_account_type_values', 'ck_accounts_class_type_pair')
			  AND convalidated
			  AND NOT connoinherit
			"""));
		assertEquals(0, count(connection, """
			SELECT COUNT(*) FROM pg_constraint
			WHERE conrelid = 'accounts'::regclass
			  AND conname IN ('ck_accounts_account_type_values', 'ck_accounts_class_type_pair')
			  AND NOT convalidated
			"""));
	}

	private static void assertRejected(
		Connection connection, UUID userId, String accountClass, String accountType) {
		SQLException exception = assertThrows(SQLException.class,
			() -> insertAccountOnly(connection, userId, accountClass, accountType, "非法-" + accountClass + "-" + accountType));
		assertEquals("23514", exception.getSQLState());
	}

	private static UUID insertUser(Connection connection, String suffix) throws SQLException {
		UUID userId = UUID.randomUUID();
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO users (
				id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version
			) VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '账户类型基线', 'Asia/Shanghai', 'CNY',
				'zh-CN', 'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""")) {
			statement.setObject(1, userId);
			statement.setString(2, suffix + "@example.test");
			statement.setString(3, suffix + "@example.test");
			statement.setString(4, NOW.toString());
			statement.setString(5, NOW.toString());
			statement.setString(6, NOW.toString());
			statement.executeUpdate();
		}
		return userId;
	}

	private static UUID findUser(Connection connection, String email) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM users WHERE email = ?")) {
			statement.setString(1, email);
			try (ResultSet result = statement.executeQuery()) {
				assertTrue(result.next());
				return result.getObject(1, UUID.class);
			}
		}
	}

	private static UUID insertCompleteAccount(
		Connection connection,
		UUID userId,
		String accountClass,
		String accountType,
		String name) throws SQLException {
		boolean originalAutoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			UUID accountId = insertAccountOnly(connection, userId, accountClass, accountType, name);
			UUID membershipId = UUID.randomUUID();
			try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', CAST(? AS timestamptz), 1, 1)
				""")) {
				statement.setObject(1, membershipId);
				statement.setObject(2, accountId);
				statement.setObject(3, userId);
				statement.setString(4, NOW.toString());
				statement.executeUpdate();
			}
			try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
				""")) {
				statement.setObject(1, UUID.randomUUID());
				statement.setObject(2, membershipId);
				statement.setString(3, NOW.toString());
				statement.setObject(4, userId);
				statement.setString(5, NOW.toString());
				statement.executeUpdate();
			}
			String nature = "LIABILITY".equals(accountClass) ? "LIABILITY" : "ASSET";
			try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', ?, 'CNY', 'ACTIVE', CAST(? AS timestamptz))
				""")) {
				statement.setObject(1, UUID.randomUUID());
				statement.setObject(2, accountId);
				statement.setString(3, "ACCOUNT_" + accountId);
				statement.setString(4, nature);
				statement.setString(5, NOW.toString());
				statement.executeUpdate();
			}
			connection.commit();
			return accountId;
		} catch (SQLException exception) {
			connection.rollback();
			throw exception;
		} finally {
			connection.setAutoCommit(originalAutoCommit);
		}
	}

	private static UUID insertAccountOnly(
		Connection connection,
		UUID userId,
		String accountClass,
		String accountType,
		String name) throws SQLException {
		UUID accountId = UUID.randomUUID();
		try (PreparedStatement statement = connection.prepareStatement("""
			INSERT INTO accounts (
				id, account_class, account_type, name, institution, currency, note, status,
				created_by, created_at, updated_at, version
			) VALUES (?, ?, ?, ?, NULL, 'CNY', NULL, 'ACTIVE', ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""")) {
			statement.setObject(1, accountId);
			statement.setString(2, accountClass);
			statement.setString(3, accountType);
			statement.setString(4, name);
			statement.setObject(5, userId);
			statement.setString(6, NOW.toString());
			statement.setString(7, NOW.toString());
			statement.executeUpdate();
		}
		return accountId;
	}

	private static int count(Connection connection, String sql) throws SQLException {
		try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private static boolean containsConstraintName(Throwable throwable) {
		List<String> messages = new ArrayList<>();
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				messages.add(current.getMessage());
			}
		}
		return messages.stream().anyMatch(message ->
			message.contains("ck_accounts_class_type_pair") || message.contains("23514"));
	}

	private record LegacyAccount(String accountClass, String accountType, String name) {
	}
}
