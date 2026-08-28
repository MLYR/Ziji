package app.ziji;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.sync.application.SyncOutboxConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA-SYNC-001 / T-SYNC-001/003/004/005/007/009 金标准：同一用户两台设备在真实 HTTP 与
 * PostgreSQL 下走完整离线闭环——离线队列重连上传（客户端 UUID 持久化）、同操作重复提交只有
 * 一份事实、同 Key 异 Hash 逐项拒绝、双设备增量拉取游标连续且重启续拉无重复、双设备同版本
 * 编辑产生安全 VERSION_CONFLICT 摘要、作废经 outbox consumer 产生其他设备可见的 TOMBSTONE。
 * 成员移除/ACCESS_REVOKED 属 B2（CHG-SYNC-002），不在本任务范围。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SyncOfflineGoldenSamplePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private DeviceSessionApplicationService sessions;
	@Autowired private SyncOutboxConsumer outboxConsumer;
	@Autowired private app.ziji.shared.application.TransactionRunner transactions;

	@Test
	void offlineUploadDuplicateReplayRestartPullStaleConflictAndVoidTombstoneGoldenFlow() throws Exception {
		UUID userId = insertUser("sync-golden-owner");
		UUID accountId = insertVisibleAccount(userId);
		UUID expenseCategory = category(userId, accountId);
		String deviceA = bearer(userId, "golden-device-a");
		String deviceB = bearer(userId, "golden-device-b");

		// T-SYNC-001 离线记账重连上传：客户端 Transaction UUID 被服务端原样持久化。
		UUID expenseId = UUID.randomUUID();
		String createKey = key();
		String createBody = envelope("device-a",
			expense(UUID.randomUUID(), createKey, expenseId, accountId, expenseCategory, "20.00"));
		MvcResult created = postOperations(deviceA, createBody);
		JsonNode createdBody = json(created);
		assertEquals("APPLIED", createdBody.at("/data/results/0/status").asText());
		assertEquals(expenseId.toString(), createdBody.at("/data/results/0/entityId").asText());
		assertEquals(1, createdBody.at("/data/results/0/entityVersion").asInt());
		// 上传结果不返回 changeSequence 或 serverCursor；客户端只能以持久化 cursor 拉取。
		assertTrue(createdBody.at("/data/results/0/changeSequence").isMissingNode());
		assertTrue(createdBody.at("/meta/serverCursor").isMissingNode());
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ? AND source = 'SYNC' AND status = 'POSTED'", expenseId));
		assertEquals(2, count("SELECT count(*) FROM ledger_entries WHERE transaction_id = ?", expenseId));

		// T-SYNC-004 重复提交：同 Key/同 Hash 重放只返回 DUPLICATE，不产生第二份事实。
		MvcResult replayed = postOperations(deviceA, createBody);
		JsonNode replayedBody = json(replayed);
		assertEquals("DUPLICATE", replayedBody.at("/data/results/0/status").asText());
		assertEquals(expenseId.toString(), replayedBody.at("/data/results/0/entityId").asText());
		assertEquals(1, replayedBody.at("/data/results/0/entityVersion").asInt());
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ?", expenseId));
		assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", expenseId));
		assertEquals(1, count("SELECT count(*) FROM audit_logs WHERE resource_id = ?", expenseId));

		// T-SYNC-009 同 Key 异 Hash（金额与客户端 operationId 差异改变 Hash）逐项拒绝且不产生新事实。
		String reusedKeyBody = envelope("device-a", expense(
			UUID.randomUUID(), createKey, expenseId, accountId, expenseCategory, "30.00"));
		MvcResult reused = postOperations(deviceA, reusedKeyBody);
		assertEquals("REJECTED", json(reused).at("/data/results/0/status").asText());
		assertEquals("IDEMPOTENCY_KEY_REUSED", json(reused).at("/data/results/0/error/code").asText());
		assertEquals(1, count("SELECT count(*) FROM transactions WHERE id = ?", expenseId));

		// T-SYNC-003 消费 outbox 后设备 B 增量拉取：游标连续、二次拉取与重启续拉均无重复。
		drainOutbox();
		JsonNode page1 = pull(deviceB, null);
		assertEquals(1, page1.at("/data").size());
		assertEquals("UPSERT", page1.at("/data/0/changeType").asText());
		assertEquals(expenseId.toString(), page1.at("/data/0/entityId").asText());
		String cursor = page1.at("/meta/nextCursor").asText();
		assertFalse(cursor.isBlank());
		JsonNode page2 = pull(deviceB, cursor);
		assertEquals(0, page2.at("/data").size());
		assertFalse(page2.at("/meta/hasMore").asBoolean());
		// 模拟客户端重启：从已确认 cursor 重新拉取，仍为空页且序列不回退、不重复。
		JsonNode page3 = pull(deviceB, cursor);
		assertEquals(0, page3.at("/data").size());

		// T-SYNC-007 两设备修改同一版本：先提交方成功，后提交方收到不含当前资源的安全冲突摘要。
		UUID expense2 = UUID.randomUUID();
		assertApplied(postOperations(deviceA,
			envelope("device-a", expense(UUID.randomUUID(), key(), expense2, accountId, expenseCategory, "11.00"))));
		drainOutbox();
		JsonNode deviceBPage = pull(deviceB, cursor);
		assertTrue(containsChange(deviceBPage, "UPSERT", expense2));
		String deviceBCursor = lastCursor(deviceBPage, cursor);
		// 设备 B 按其所见版本 1 先提交修订。
		assertApplied(postOperations(deviceB,
			envelope("device-b", updateExpense(UUID.randomUUID(), key(), expense2, accountId, expenseCategory, 1))));
		// 设备 A 仍持有版本 1 的离线修订，重连后提交收到安全摘要。
		MvcResult conflict = postOperations(deviceA,
			envelope("device-a", updateExpense(UUID.randomUUID(), key(), expense2, accountId, expenseCategory, 1)));
		JsonNode conflictBody = json(conflict);
		assertEquals("CONFLICT", conflictBody.at("/data/results/0/status").asText());
		assertEquals("VERSION_CONFLICT", conflictBody.at("/data/results/0/error/code").asText());
		assertEquals(2, conflictBody.at("/data/results/0/error/versionConflict/currentVersion").asLong());
		assertEquals("\"2\"", conflictBody.at("/data/results/0/error/versionConflict/currentEtag").asText());
		assertEquals("/api/v1/transactions/" + expense2,
			conflictBody.at("/data/results/0/error/versionConflict/resourceLocation").asText());
		assertTrue(conflictBody.at("/data/results/0/error/currentResource").isMissingNode());
		assertTrue(conflictBody.at("/data/results/0/error/versionConflict/currentResource").isMissingNode());

		// 设备 B 追平修订产生的 UPSERT（原交易 v2 与替换交易），获得最新游标。
		drainOutbox();
		PullSummary catchUp = pullAll(deviceB, deviceBCursor);
		assertTrue(catchUp.changeKeys().contains("UPSERT:" + expense2));
		String deviceBNow = catchUp.cursor();

		// T-SYNC-005 交易作废：未被修订的交易由设备 A 按版本 1 作废，设备 B 经增量拉取收到 TOMBSTONE。
		UUID expense3 = UUID.randomUUID();
		assertApplied(postOperations(deviceA,
			envelope("device-a", expense(UUID.randomUUID(), key(), expense3, accountId, expenseCategory, "7.00"))));
		MvcResult reversal = postOperations(deviceA,
			envelope("device-a", reverse(UUID.randomUUID(), key(), expense3, 1)));
		assertEquals("APPLIED", json(reversal).at("/data/results/0/status").asText());
		drainOutbox();
		PullSummary tombstones = pullAll(deviceB, deviceBNow);
		// 作废只对外投递原交易的 TOMBSTONE；内部冲正交易不得作为业务变更下发。
		UUID reversalId = jdbc.queryForObject(
			"SELECT id FROM transactions WHERE reversal_of_id = ?", UUID.class, expense3);
		assertTrue(tombstones.changeKeys().contains("TOMBSTONE:" + expense3));
		assertFalse(tombstones.changeKeys().contains("UPSERT:" + reversalId));
		assertFalse(tombstones.changeKeys().contains("TOMBSTONE:" + reversalId));
		assertEquals("REVERSED", jdbc.queryForObject(
			"SELECT status FROM transactions WHERE id = ?", String.class, expense3));
	}

	private void assertApplied(MvcResult result) throws Exception {
		assertEquals("APPLIED", json(result).at("/data/results/0/status").asText());
	}

	private MvcResult postOperations(String token, String body) throws Exception {
		return mvc.perform(post("/api/v1/sync/operations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-Request-ID", "sync-golden-request")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meta.requestId").value("sync-golden-request"))
			.andReturn();
	}

	/** 持续消费 outbox 直到没有到期事件，等价于服务端投递循环。 */
	private void drainOutbox() {
		int guard = 0;
		while (outboxConsumer.consumeNext()) {
			if (++guard > 100) throw new AssertionError("outbox 消费未在保护上限内收敛");
		}
	}

	private JsonNode pull(String token, String cursor) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/sync/changes")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("X-Request-ID", "sync-golden-request")
			.param("limit", "10");
		if (cursor != null) request = request.param("cursor", cursor);
		return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
	}

	/** 按游标翻页拉取全部变更，跨页校验序列严格递增，并汇总 (changeType, entityId) 键。 */
	private PullSummary pullAll(String token, String cursor) throws Exception {
		Set<String> keys = new LinkedHashSet<>();
		List<Long> sequences = new ArrayList<>();
		String current = cursor;
		String finalCursor = cursor;
		while (true) {
			JsonNode page = pull(token, current);
			for (JsonNode change : page.at("/data")) {
				long sequence = change.at("/sequence").asLong();
				if (!sequences.isEmpty() && sequence <= sequences.getLast()) {
					throw new AssertionError("增量拉取序列未严格递增：" + sequences);
				}
				sequences.add(sequence);
				keys.add(change.at("/changeType").asText() + ":" + change.at("/entityId").asText());
			}
			finalCursor = page.at("/meta/nextCursor").asText("");
			if (!page.at("/meta/hasMore").asBoolean()) break;
			current = finalCursor;
		}
		return new PullSummary(finalCursor, keys, sequences);
	}

	private record PullSummary(String cursor, Set<String> changeKeys, List<Long> sequences) {
	}

	private boolean containsChange(JsonNode page, String changeType, UUID entityId) {
		for (JsonNode change : page.at("/data")) {
			if (changeType.equals(change.at("/changeType").asText())
				&& entityId.toString().equals(change.at("/entityId").asText())) {
				return true;
			}
		}
		return false;
	}

	private String lastCursor(JsonNode page, String fallback) {
		String next = page.at("/meta/nextCursor").asText("");
		return next.isBlank() ? fallback : next;
	}

	private UUID insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "sync-golden-" + suffix + "-" + userId + "@example.test";
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '同步金标准', 'Asia/Shanghai', 'CNY', 'zh-CN',
			 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, email, email, now, now, now);
		return userId;
	}

	/** 业务时间固定在 2026-01 之后，避免成员周期被夹具误造成历史无权；账户与成员必须同事务落库。 */
	private UUID insertVisibleAccount(UUID ownerId) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		String now = Instant.parse("2026-01-01T00:00:00Z").toString();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, 'CNY', 'ACTIVE', ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
				""", accountId, "同步金标准账户-" + accountId, ownerId, now, now);
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', CAST(? AS timestamptz), 1, 1)
				""", membershipId, accountId, ownerId, now);
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
				""", UUID.randomUUID(), membershipId, now, ownerId, now);
			jdbc.update("""
				INSERT INTO ledger_accounts (id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', CAST(? AS timestamptz))
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, now);
		});
		return accountId;
	}

	private UUID category(UUID ownerId, UUID accountId) {
		UUID categoryId = UUID.randomUUID();
		String now = Instant.parse("2026-01-01T00:00:00Z").toString();
		String name = "EXPENSE-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, account_id, category_type, parent_id, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, 'EXPENSE', NULL, ?, ?, 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", categoryId, ownerId, accountId, name, name, now, now);
		return categoryId;
	}

	private String bearer(UUID userId, String deviceName) {
		SessionTokenResult result = sessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(userId, "sync-golden", deviceName));
		return result.accessToken();
	}

	private static String envelope(String deviceId, String... operations) {
		return "{\"deviceId\":\"" + deviceId + "\",\"operations\":[" + String.join(",", operations) + "]}";
	}

	private static String expense(UUID operationId, String idempotencyKey, UUID transactionId,
		UUID accountId, UUID categoryId, String amount) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"CREATE","baseVersion":null,"payloadVersion":1,
			 "payload":{"type":"EXPENSE","businessAt":"2026-08-16T04:00:00Z","businessDate":"2026-08-16",
			 "timezone":"Asia/Shanghai","accountId":"%s","amount":"%s","currency":"CNY","categoryId":"%s"},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, accountId, amount, categoryId)
			.replaceAll("\\s+", "");
	}

	private static String updateExpense(UUID operationId, String idempotencyKey, UUID transactionId,
		UUID accountId, UUID categoryId, int baseVersion) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"UPDATE","baseVersion":%d,"payloadVersion":1,
			 "payload":{"reason":"同步修订","replacement":{"type":"EXPENSE","businessAt":"2026-08-16T04:00:00Z",
			 "businessDate":"2026-08-16","timezone":"Asia/Shanghai","accountId":"%s","amount":"21.00","currency":"CNY","categoryId":"%s"}},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, baseVersion, accountId, categoryId)
			.replaceAll("\\s+", "");
	}

	private static String reverse(UUID operationId, String idempotencyKey, UUID transactionId, int baseVersion) {
		return """
			{"operationId":"%s","idempotencyKey":"%s","entityType":"TRANSACTION","entityId":"%s",
			 "operationType":"REVERSE","baseVersion":%d,"payloadVersion":1,"payload":{"reason":"同步作废"},
			 "createdAt":"2026-08-16T04:00:00Z"}
			""".formatted(operationId, idempotencyKey, transactionId, baseVersion)
			.replaceAll("\\s+", "");
	}

	private static String key() {
		return "sync-golden-key-" + UUID.randomUUID();
	}

	private int count(String sql, Object... arguments) {
		Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
		return value == null ? 0 : value;
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
