package app.ziji.shared.infrastructure;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.shared.application.IdempotencyInfrastructureException;
import app.ziji.shared.application.IdempotencyLockTimeoutException;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL V009 适配器：原子 UPSERT、FOR UPDATE 和数据库锁等待是唯一的跨实例并发权威。 */
@Repository
public class PostgresIdempotencyRecordStore implements IdempotencyRecordStore {

	private static final Duration PROCESSING_LEASE = Duration.ofSeconds(30);
	private static final Duration REPLAY_PROTECTION = Duration.ofDays(7);

	private static final String SELECT_AUTHENTICATED_FOR_UPDATE_SQL = """
		SELECT id, request_hash, status, response_status, response_reference::text AS response_reference_json,
			resource_type, resource_id, processing_lease_expires_at, retry_after_at
		FROM idempotency_records
		WHERE user_id = ? AND api_major_version = ? AND operation_id = ? AND idempotency_key = ?
		FOR UPDATE
		""";

	private static final String SELECT_ANONYMOUS_ONE_FOR_UPDATE_SQL = """
		SELECT id, request_hash, status, response_status, response_reference::text AS response_reference_json,
			resource_type, resource_id, processing_lease_expires_at, retry_after_at
		FROM idempotency_records
		WHERE anonymous_subject_hash = ? AND anonymous_subject_hash_key_version = ?
			AND api_major_version = ? AND operation_id = ? AND idempotency_key = ?
		FOR UPDATE
		""";

	private static final String SELECT_ANONYMOUS_TWO_FOR_UPDATE_SQL = """
		SELECT id, request_hash, status, response_status, response_reference::text AS response_reference_json,
			resource_type, resource_id, processing_lease_expires_at, retry_after_at
		FROM idempotency_records
		WHERE api_major_version = ? AND operation_id = ? AND idempotency_key = ?
			AND ((anonymous_subject_hash = ? AND anonymous_subject_hash_key_version = ?)
				OR (anonymous_subject_hash = ? AND anonymous_subject_hash_key_version = ?))
		ORDER BY anonymous_subject_hash_key_version, id
		FOR UPDATE
		""";

	private static final String INSERT_AUTHENTICATED_PROCESSING_SQL = """
		INSERT INTO idempotency_records (
			id, user_id, anonymous_subject_hash, anonymous_subject_hash_key_version, api_major_version,
			operation_id, idempotency_key, request_hash, status, response_status, response_reference,
			resource_type, resource_id, created_at, completed_at, processing_started_at,
			processing_lease_expires_at, retry_after_at, expires_at
		) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, 'PROCESSING', NULL, NULL, NULL, NULL,
			CAST(? AS timestamptz), NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL,
			CAST(? AS timestamptz))
		ON CONFLICT DO NOTHING
		RETURNING id
		""";

	private static final String INSERT_ANONYMOUS_PROCESSING_SQL = """
		INSERT INTO idempotency_records (
			id, user_id, anonymous_subject_hash, anonymous_subject_hash_key_version, api_major_version,
			operation_id, idempotency_key, request_hash, status, response_status, response_reference,
			resource_type, resource_id, created_at, completed_at, processing_started_at,
			processing_lease_expires_at, retry_after_at, expires_at
		) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, 'PROCESSING', NULL, NULL, NULL, NULL,
			CAST(? AS timestamptz), NULL, CAST(? AS timestamptz), CAST(? AS timestamptz), NULL,
			CAST(? AS timestamptz))
		ON CONFLICT DO NOTHING
		RETURNING id
		""";

	private static final String TAKE_OVER_PROCESSING_SQL = """
		UPDATE idempotency_records
		SET status = 'PROCESSING', response_status = NULL, response_reference = NULL,
			resource_type = NULL, resource_id = NULL, completed_at = NULL,
			processing_started_at = CAST(? AS timestamptz),
			processing_lease_expires_at = CAST(? AS timestamptz), retry_after_at = NULL
		WHERE id = ?
		""";

	private static final String COMPLETE_SQL = """
		UPDATE idempotency_records
		SET status = ?, response_status = ?, response_reference = CAST(? AS jsonb),
			resource_type = ?, resource_id = ?, completed_at = CAST(? AS timestamptz),
			processing_started_at = NULL, processing_lease_expires_at = NULL,
			retry_after_at = CASE WHEN ? = 'FAILED_RETRYABLE'
				THEN CAST(? AS timestamptz) + interval '5 seconds' ELSE NULL END
		WHERE id = ? AND status = 'PROCESSING'
		""";

	private static final String DELETE_EXPIRED_TERMINAL_SQL = """
		WITH candidates AS (
			SELECT id
			FROM idempotency_records
			WHERE status <> 'PROCESSING'
				AND expires_at <= CAST(? AS timestamptz)
				AND expires_at <= CURRENT_TIMESTAMP
				AND NOT EXISTS (
					SELECT 1 FROM transactions WHERE transactions.idempotency_record_id = idempotency_records.id)
				AND NOT EXISTS (
					SELECT 1 FROM sync_operations WHERE sync_operations.idempotency_record_id = idempotency_records.id)
			ORDER BY expires_at, id
			LIMIT ?
			FOR UPDATE SKIP LOCKED
		)
		DELETE FROM idempotency_records USING candidates
		WHERE idempotency_records.id = candidates.id
		RETURNING idempotency_records.id
		""";

	private final DSLContext dsl;
	private final ObjectMapper objectMapper;

	public PostgresIdempotencyRecordStore(DSLContext dsl, ObjectMapper objectMapper) {
		if (dsl == null || objectMapper == null) {
			throw new IdempotencyInfrastructureException("幂等数据库访问入口不能为空。");
		}
		this.dsl = dsl;
		this.objectMapper = objectMapper;
	}

	@Override
	public Acquisition acquire(IdempotencyRequest request, Instant now) {
		try {
			setLockTimeout();
			List<StoredRecord> existing = findForUpdate(request);
			if (existing.size() > 1) {
				// 旧/新配置并发写入会产生两个版本行；不猜测哪个结果可重放或重执行业务。
				return new Acquisition.SafeReplayUnavailable();
			}
			if (existing.size() == 1) {
				return resolveLocked(existing.getFirst(), request, now);
			}
			UUID id = insertProcessing(request, now);
			if (id != null) {
				return new Acquisition.Acquired(id);
			}
			// ON CONFLICT 可能刚等待了另一实例提交；重新 FOR UPDATE 后只读取其终态，不并行执行业务。
			existing = findForUpdate(request);
			if (existing.size() != 1) {
				return new Acquisition.SafeReplayUnavailable();
			}
			return resolveLocked(existing.getFirst(), request, now);
		} catch (org.jooq.exception.DataAccessException exception) {
			throw translate(exception);
		} catch (org.springframework.dao.DataAccessException exception) {
			throw translate(exception);
		}
	}

	@Override
	public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
		try {
			if (recordId == null || response == null || completedAt == null) {
				throw new IdempotencyInfrastructureException("幂等终态写入参数无效。");
			}
			String reference = serialize(response);
			int changed = dsl.execute(COMPLETE_SQL,
				response.status().name(), response.responseStatus(), reference, response.resourceType(), response.resourceId(),
				utc(completedAt), response.status().name(), utc(completedAt), recordId);
			if (changed != 1) {
				throw new IdempotencyInfrastructureException("幂等终态写入状态无效。");
			}
		} catch (org.jooq.exception.DataAccessException exception) {
			throw translate(exception);
		} catch (org.springframework.dao.DataAccessException exception) {
			throw translate(exception);
		}
	}

	@Override
	public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
		try {
			if (now == null || maximumRecords < 1) {
				throw new IdempotencyInfrastructureException("幂等清理参数无效。");
			}
			return dsl.resultQuery(DELETE_EXPIRED_TERMINAL_SQL, utc(now), maximumRecords).fetch().size();
		} catch (org.jooq.exception.DataAccessException exception) {
			throw translate(exception);
		} catch (org.springframework.dao.DataAccessException exception) {
			throw translate(exception);
		}
	}

	private Acquisition resolveLocked(StoredRecord record, IdempotencyRequest request, Instant now) {
		if (!request.requestHash().equals(record.requestHash())) {
			return new Acquisition.KeyReused();
		}
		if ("PROCESSING".equals(record.status())) {
			if (record.processingLeaseExpiresAt() == null) {
				return new Acquisition.SafeReplayUnavailable();
			}
			if (now.isBefore(record.processingLeaseExpiresAt())) {
				return new Acquisition.InProgress();
			}
			return takeOver(record.id(), now);
		}
		if ("FAILED_RETRYABLE".equals(record.status())) {
			if (record.retryAfterAt() == null) {
				return new Acquisition.SafeReplayUnavailable();
			}
			if (now.isBefore(record.retryAfterAt())) {
				return new Acquisition.InProgress();
			}
			return takeOver(record.id(), now);
		}
		if ("SUCCEEDED".equals(record.status()) || "FAILED_FINAL".equals(record.status())) {
			IdempotencyResponse response = deserialize(record);
			return response == null ? new Acquisition.SafeReplayUnavailable() : new Acquisition.Replay(response);
		}
		return new Acquisition.SafeReplayUnavailable();
	}

	private Acquisition takeOver(UUID recordId, Instant now) {
		int changed = dsl.execute(TAKE_OVER_PROCESSING_SQL, utc(now), utc(now.plus(PROCESSING_LEASE)), recordId);
		if (changed != 1) {
			throw new IdempotencyInfrastructureException("幂等记录接管失败。");
		}
		return new Acquisition.Acquired(recordId);
	}

	private List<StoredRecord> findForUpdate(IdempotencyRequest request) {
		if (request.subject() instanceof IdempotencySubject.Authenticated authenticated) {
			return records(dsl.resultQuery(SELECT_AUTHENTICATED_FOR_UPDATE_SQL,
				authenticated.userId(), request.apiMajorVersion(), request.operationId(), request.idempotencyKey()).fetch());
		}
		IdempotencySubject.Anonymous anonymous = (IdempotencySubject.Anonymous) request.subject();
		List<IdempotencySubject.AnonymousDigest> candidates = anonymous.lookupCandidatesInVersionOrder();
		if (candidates.size() == 1) {
			IdempotencySubject.AnonymousDigest only = candidates.getFirst();
			return records(dsl.resultQuery(SELECT_ANONYMOUS_ONE_FOR_UPDATE_SQL,
				only.valueCopy(), only.keyVersion(), request.apiMajorVersion(), request.operationId(), request.idempotencyKey())
				.fetch());
		}
		IdempotencySubject.AnonymousDigest first = candidates.get(0);
		IdempotencySubject.AnonymousDigest second = candidates.get(1);
		return records(dsl.resultQuery(SELECT_ANONYMOUS_TWO_FOR_UPDATE_SQL,
			request.apiMajorVersion(), request.operationId(), request.idempotencyKey(),
			first.valueCopy(), first.keyVersion(), second.valueCopy(), second.keyVersion()).fetch());
	}

	private UUID insertProcessing(IdempotencyRequest request, Instant now) {
		UUID id = UUID.randomUUID();
		Instant leaseExpiresAt = now.plus(PROCESSING_LEASE);
		Instant expiresAt = now.plus(REPLAY_PROTECTION);
		Record record;
		if (request.subject() instanceof IdempotencySubject.Authenticated authenticated) {
			record = dsl.resultQuery(INSERT_AUTHENTICATED_PROCESSING_SQL,
				id, authenticated.userId(), request.apiMajorVersion(), request.operationId(), request.idempotencyKey(),
				request.requestHash(), utc(now), utc(now), utc(leaseExpiresAt), utc(expiresAt)).fetchOne();
		} else {
			IdempotencySubject.AnonymousDigest current = ((IdempotencySubject.Anonymous) request.subject()).current();
			record = dsl.resultQuery(INSERT_ANONYMOUS_PROCESSING_SQL,
				id, current.valueCopy(), current.keyVersion(), request.apiMajorVersion(), request.operationId(),
				request.idempotencyKey(), request.requestHash(), utc(now), utc(now), utc(leaseExpiresAt), utc(expiresAt))
				.fetchOne();
		}
		return record == null ? null : record.get("id", UUID.class);
	}

	private List<StoredRecord> records(org.jooq.Result<Record> records) {
		List<StoredRecord> converted = new ArrayList<>();
		for (Record record : records) {
			converted.add(new StoredRecord(
				record.get("id", UUID.class),
				record.get("request_hash", String.class),
				record.get("status", String.class),
				record.get("response_status", Integer.class),
				record.get("response_reference_json", String.class),
				record.get("resource_type", String.class),
				record.get("resource_id", UUID.class),
				instant(record.get("processing_lease_expires_at", OffsetDateTime.class)),
				instant(record.get("retry_after_at", OffsetDateTime.class))));
		}
		return List.copyOf(converted);
	}

	private String serialize(IdempotencyResponse response) {
		try {
			var node = objectMapper.createObjectNode().put("kind", response.reference().kind());
			if (response.reference() instanceof IdempotencyResponse.ResourceReference resource) {
				if (resource.location() != null) {
					node.put("location", resource.location());
				}
				if (resource.etag() != null) {
					node.put("etag", resource.etag());
				}
				if (resource.resourceVersion() != null) {
					node.put("resourceVersion", resource.resourceVersion());
				}
			}
			if (response.reference() instanceof IdempotencyResponse.ProblemReference problem) {
				node.put("errorCode", problem.errorCode());
				if (problem.retryable()) {
					node.put("retryAfterSeconds", 5);
				}
			}
			String serialized = objectMapper.writeValueAsString(node);
			if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8_192) {
				throw new IdempotencyInfrastructureException("幂等安全响应引用超过上限。");
			}
			return serialized;
		} catch (RuntimeException exception) {
			throw new IdempotencyInfrastructureException("幂等安全响应引用序列化失败。", exception);
		}
	}

	private IdempotencyResponse deserialize(StoredRecord record) {
		try {
			if (record.responseStatus() == null || record.responseReferenceJson() == null) {
				return null;
			}
			JsonNode node = objectMapper.readTree(record.responseReferenceJson());
			if (node == null || !node.isObject() || !text(node, "kind", null)) {
				return null;
			}
			String kind = node.get("kind").textValue();
			if ("SUCCEEDED".equals(record.status())) {
				if ("EMPTY".equals(kind) && hasOnly(node, "kind")
					&& record.resourceType() == null && record.resourceId() == null) {
					return IdempotencyResponse.succeededEmpty(record.responseStatus());
				}
				if ("RESOURCE".equals(kind) && hasOnly(node, "kind", "location", "etag", "resourceVersion")
					&& record.resourceType() != null && record.resourceId() != null) {
					return IdempotencyResponse.succeededResource(record.responseStatus(), record.resourceType(), record.resourceId(),
						new IdempotencyResponse.ResourceReference(
							optionalText(node, "location"), optionalText(node, "etag"), optionalLong(node, "resourceVersion")));
				}
			}
			if ("FAILED_FINAL".equals(record.status()) && "PROBLEM".equals(kind)
				&& hasOnly(node, "kind", "errorCode") && text(node, "errorCode", null)) {
				return IdempotencyResponse.failedFinal(record.responseStatus(), node.get("errorCode").textValue());
			}
			return null;
		} catch (RuntimeException exception) {
			// 历史 NOT VALID 行可能不满足 V009；不能把其载荷、SQL 或错误细节带到结果中。
			return null;
		}
	}

	private static boolean hasOnly(JsonNode node, String... allowed) {
		List<String> fields = new ArrayList<>();
		for (String field : node.propertyNames()) {
			fields.add(field);
		}
		if (fields.size() < 1 || fields.size() > allowed.length) {
			return false;
		}
		for (String field : fields) {
			boolean found = false;
			for (String permitted : allowed) {
				if (permitted.equals(field)) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	private static boolean text(JsonNode node, String field, String unused) {
		return node.has(field) && node.get(field).isTextual() && node.get(field).textValue() != null;
	}

	private static String optionalText(JsonNode node, String field) {
		if (!node.has(field)) {
			return null;
		}
		return text(node, field, null) ? node.get(field).textValue() : invalidJson();
	}

	private static Long optionalLong(JsonNode node, String field) {
		if (!node.has(field)) {
			return null;
		}
		if (!node.get(field).isIntegralNumber() || !node.get(field).canConvertToLong()) {
			return invalidJson();
		}
		return node.get(field).longValue();
	}

	private static <T> T invalidJson() {
		throw new IllegalArgumentException("unsafe response reference");
	}

	private void setLockTimeout() {
		// SET LOCAL 绑定当前 REQUIRED 事务，避免污染连接池中的下一次请求。
		dsl.execute("SET LOCAL lock_timeout = '5s'");
	}

	private RuntimeException translate(Throwable exception) {
		if (isLockTimeout(exception)) {
			return new IdempotencyLockTimeoutException(exception);
		}
		return new IdempotencyInfrastructureException("幂等记录数据库操作失败。", exception);
	}

	private static boolean isLockTimeout(Throwable exception) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private static OffsetDateTime utc(Instant value) {
		if (value == null) {
			throw new IdempotencyInfrastructureException("幂等时间不能为空。");
		}
		return value.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private record StoredRecord(
		UUID id,
		String requestHash,
		String status,
		Integer responseStatus,
		String responseReferenceJson,
		String resourceType,
		UUID resourceId,
		Instant processingLeaseExpiresAt,
		Instant retryAfterAt) {
	}
}
