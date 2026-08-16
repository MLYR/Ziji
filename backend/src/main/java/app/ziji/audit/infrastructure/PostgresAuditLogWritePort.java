package app.ziji.audit.infrastructure;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import app.ziji.audit.application.AuditLogWritePort;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 追加适配器；外层业务事务决定审计事实与业务事实的原子提交。 */
@Repository
public class PostgresAuditLogWritePort implements AuditLogWritePort {

	private static final String INSERT_SQL = """
		INSERT INTO audit_logs (
			id, occurred_at, actor_user_id, actor_type, action, resource_type, resource_id,
			account_id, request_id, result, reason_code, metadata
		) VALUES (?, CAST(? AS timestamptz), ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
		""";

	private final DSLContext dsl;
	private final ObjectMapper objectMapper;

	public PostgresAuditLogWritePort(DSLContext dsl, ObjectMapper objectMapper) {
		if (dsl == null || objectMapper == null) {
			throw new IllegalArgumentException("审计写入依赖不能为空。");
		}
		this.dsl = dsl;
		this.objectMapper = objectMapper;
	}

	@Override
	public void append(AuditLogEntry entry) {
		if (entry == null) {
			throw new IllegalArgumentException("审计追加事实不能为空。");
		}
		String metadata = objectMapper.valueToTree(entry.metadata()).toString();
		int changed = dsl.execute(INSERT_SQL,
			java.util.UUID.randomUUID(), OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC),
			entry.actorUserId(), entry.actorType().name(), entry.action(), entry.resourceType(), entry.resourceId(),
			entry.accountId(), entry.requestId(), entry.result().name(), entry.reasonCode(), metadata);
		if (changed != 1) {
			throw new IllegalStateException("审计追加写入失败。");
		}
	}
}
