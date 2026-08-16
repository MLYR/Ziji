package app.ziji;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL 审计端口验收：事实只追加、metadata 结构化，且必须服从调用方的业务事务。 */
@SpringBootTest
@ActiveProfiles("test")
class AuditLogPostgresIntegrationTests extends PostgresIntegrationTestSupport {

	private static final Instant NOW = Instant.parse("2026-08-15T03:04:05Z");

	@Autowired
	private AuditLogWritePort auditLogs;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void appendsStructuredFactAndDatabaseRejectsMutation() {
		UUID actorId = insertUser("audit-append");
		UUID resourceId = UUID.randomUUID();
		auditLogs.append(entry(actorId, resourceId));

		Map<String, Object> row = jdbc.queryForMap("""
			SELECT actor_user_id, actor_type, action, resource_type, resource_id, account_id,
				request_id, result, reason_code, metadata ->> 'holdId' AS hold_id,
				metadata ->> 'version' AS version
			FROM audit_logs WHERE resource_id = ?
			""", resourceId);
		assertEquals(actorId, row.get("actor_user_id"));
		assertEquals("USER", row.get("actor_type"));
		assertEquals("LIQUIDITY_HOLD_CREATED", row.get("action"));
		assertEquals("LIQUIDITY_HOLD", row.get("resource_type"));
		assertEquals(resourceId, row.get("resource_id"));
		assertEquals("SUCCESS", row.get("result"));
		assertEquals(resourceId.toString(), row.get("hold_id"));
		assertEquals("1", row.get("version"));

		assertThrows(DataAccessException.class, () -> jdbc.update(
			"UPDATE audit_logs SET action = 'TAMPERED' WHERE resource_id = ?", resourceId));
		assertThrows(DataAccessException.class, () -> jdbc.update(
			"DELETE FROM audit_logs WHERE resource_id = ?", resourceId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE resource_id = ?", Integer.class, resourceId));
		assertEquals(Boolean.TRUE, hasPrivilege("INSERT"));
		assertEquals(Boolean.TRUE, hasPrivilege("SELECT"));
		assertEquals(Boolean.FALSE, hasPrivilege("UPDATE"));
		assertEquals(Boolean.FALSE, hasPrivilege("DELETE"));
	}

	@Test
	void appendsSystemFactWithoutAUserActor() {
		UUID resourceId = UUID.randomUUID();
		auditLogs.append(new AuditLogWritePort.AuditLogEntry(
			NOW, null, AuditLogWritePort.ActorType.SYSTEM, "LIQUIDITY_HOLD_EXPIRED", "LIQUIDITY_HOLD",
			resourceId, null, "req-audit-system-001", AuditLogWritePort.Result.SUCCESS, "EXPIRED",
			Map.of("holdId", resourceId.toString(), "version", "2")));

		Map<String, Object> row = jdbc.queryForMap(
			"SELECT actor_user_id, actor_type, reason_code FROM audit_logs WHERE resource_id = ?", resourceId);
		assertNull(row.get("actor_user_id"));
		assertEquals("SYSTEM", row.get("actor_type"));
		assertEquals("EXPIRED", row.get("reason_code"));
	}

	@Test
	void outerFailureRollsBackTheAppendWithTheBusinessTransaction() {
		UUID actorId = insertUser("audit-rollback");
		UUID resourceId = UUID.randomUUID();

		assertThrows(IllegalStateException.class, () -> transactions.required(() -> {
			auditLogs.append(entry(actorId, resourceId));
			throw new IllegalStateException("强制回滚审计事实。");
		}));

		Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id = ?", Integer.class, resourceId);
		assertEquals(0, count);
	}

	private AuditLogWritePort.AuditLogEntry entry(UUID actorId, UUID resourceId) {
		return new AuditLogWritePort.AuditLogEntry(
			NOW, actorId, AuditLogWritePort.ActorType.USER, "LIQUIDITY_HOLD_CREATED", "LIQUIDITY_HOLD",
			resourceId, null, "req-audit-001", AuditLogWritePort.Result.SUCCESS, null,
			Map.of("holdId", resourceId.toString(), "version", "1"));
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '审计测试用户', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, suffix + "@example.test", suffix + "@example.test", timestamp(), timestamp(), timestamp());
		return userId;
	}

	private Boolean hasPrivilege(String privilege) {
		// 直接核对 V007 为普通应用角色冻结的 audit_logs 表权限。
		return jdbc.queryForObject(
			"SELECT has_table_privilege('ziji_app', 'public.audit_logs', ?)", Boolean.class, privilege);
	}

	private static Timestamp timestamp() {
		return Timestamp.from(NOW);
	}
}
