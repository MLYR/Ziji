package app.ziji;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 当前迁移升级验收：V012 历史库可升级，且 V001～V012 的 Flyway checksum 保持不变。 */
@Testcontainers
class LiquidityHoldMoneyCapacityMigrationTests {

	@Container
	private static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
		new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.6-alpine")
			.withDatabaseName("ziji_liquidity_capacity_upgrade")
			.withUsername("ziji")
			.withPassword("ziji-test");

	@Test
	void v012DatabaseUpgradesToCurrentWithoutRewritingPreviousMigrations() throws Exception {
		migrate("12");
		Map<String, Integer> previousChecksums;
		try (Connection connection = connection()) {
			previousChecksums = checksumsThroughV012(connection);
		}

		migrate(null);

		try (Connection connection = connection()) {
			assertEquals(18, count(connection, "SELECT COUNT(*) FROM flyway_schema_history"));
			assertEquals(previousChecksums, checksumsThroughV012(connection));
			assertEquals("30/8", numericShape(connection, "liquidity_holds", "amount"));
			assertEquals("30/8", numericShape(connection, "account_liquidity_snapshots", "ledger_balance"));
			assertEquals("30/8", numericShape(connection, "account_liquidity_snapshots", "unavailable_amount"));
			assertEquals("30/8", numericShape(connection, "account_liquidity_snapshots", "available_balance"));
		}
	}

	private static void migrate(String target) {
		var configuration = Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static Map<String, Integer> checksumsThroughV012(Connection connection) throws SQLException {
		Map<String, Integer> checksums = new LinkedHashMap<>();
		try (var statement = connection.prepareStatement("""
			SELECT version, checksum
			FROM flyway_schema_history
			WHERE installed_rank <= 12
			ORDER BY installed_rank
			"""); ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				checksums.put(result.getString("version"), result.getInt("checksum"));
			}
		}
		return checksums;
	}

	private static int count(Connection connection, String sql) throws SQLException {
		try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
			result.next();
			return result.getInt(1);
		}
	}

	private static String numericShape(Connection connection, String table, String column) throws SQLException {
		try (var statement = connection.prepareStatement("""
			SELECT numeric_precision || '/' || numeric_scale
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
			""")) {
			statement.setString(1, table);
			statement.setString(2, column);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getString(1);
			}
		}
	}
}
