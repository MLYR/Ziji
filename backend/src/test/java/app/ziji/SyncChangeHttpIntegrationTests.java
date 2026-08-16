package app.ziji;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 PostgreSQL 17.6 验证 recipient 隔离、稳定 sequence 分页、游标和墓碑最小载荷。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SyncChangeHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Test
	void pagesOnlyCurrentRecipientWithoutDuplicatesAndKeepsTombstonePayload() throws Exception {
		UserFixture user = insertUser("sync-page-user");
		UserFixture other = insertUser("sync-page-other");
		List<Long> expected = new ArrayList<>();
		expected.add(insertChange(user.userId(), "UPSERT", "{\"entity\":\"one\"}"));
		insertChange(other.userId(), "UPSERT", "{\"entity\":\"hidden\"}");
		expected.add(insertChange(user.userId(), "TOMBSTONE", null));
		expected.add(insertChange(user.userId(), "ACCESS_REVOKED", "{\"scope\":\"MEMBERSHIP\"}"));
		expected.add(insertChange(user.userId(), "BOOTSTRAP", "{\"schemaVersion\":1}"));

		String token = bearer(user);
		List<Long> actual = new ArrayList<>();
		String cursor = null;
		JsonNode last = null;
		while (true) {
			var request = get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-Request-ID", "sync-page-request")
				.param("limit", "2");
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}
			MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
			last = json(result);
			last.at("/data").forEach(node -> actual.add(node.get("sequence").longValue()));
			assertEquals("sync-page-request", last.at("/meta/requestId").asText());
			if (!last.at("/meta/hasMore").asBoolean()) {
				break;
			}
			cursor = last.at("/meta/nextCursor").asText();
		}

		assertEquals(expected, actual);
		assertEquals(actual.size(), new HashSet<>(actual).size());
		assertTrue(actual.stream().sorted().toList().equals(actual));
		assertFalse(last.at("/meta/nextCursor").isMissingNode());

		assertEquals("BOOTSTRAP", last.at("/data/1/changeType").asText());
		JsonNode firstPage = json(mvc.perform(get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "2"))
			.andExpect(status().isOk()).andReturn());
		// NULL payload 的 TOMBSTONE 原样保留为缺省字段，不被重新计算或填充。
		assertTrue(firstPage.at("/data/1/payload").isMissingNode());
	}

	@Test
	void emptyFirstPageAndNewChangeAfterBoundaryAreStable() throws Exception {
		UserFixture user = insertUser("sync-empty-user");
		String token = bearer(user);

		JsonNode empty = json(mvc.perform(get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-Request-ID", "empty-request"))
			.andExpect(status().isOk()).andReturn());
		assertEquals(0, empty.at("/data").size());
		assertTrue(empty.at("/meta/nextCursor").isNull());
		assertFalse(empty.at("/meta/hasMore").asBoolean());

		long first = insertChange(user.userId(), "UPSERT", "{\"n\":1}");
		JsonNode page = json(mvc.perform(get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1"))
			.andExpect(status().isOk()).andReturn());
		assertEquals(first, page.at("/data/0/sequence").longValue());

		long second = insertChange(user.userId(), "UPSERT", "{\"n\":2}");
		JsonNode next = json(mvc.perform(get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1")
				.param("cursor", page.at("/meta/nextCursor").asText()))
			.andExpect(status().isOk()).andReturn());
		assertEquals(second, next.at("/data/0/sequence").longValue());
	}

	@Test
	void rejectsInvalidLimitsCursorsAndUnauthenticatedRequests() throws Exception {
		UserFixture user = insertUser("sync-validation-user");
		String token = bearer(user);
		for (String limit : List.of("0", "201", "-1", "1.5", "999999999999999999999")) {
			mvc.perform(get("/api/v1/sync/changes")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("X-Request-ID", "invalid-request")
					.param("limit", limit))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.requestId").value("invalid-request"));
		}
		mvc.perform(get("/api/v1/sync/changes")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("cursor", "tampered-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/sync/changes"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	private long insertChange(UUID recipientUserId, String changeType, String payload) {
		UUID entityId = UUID.randomUUID();
		return jdbc.queryForObject("""
			INSERT INTO change_log (
				entity_type, entity_id, entity_version, change_type, recipient_user_id,
				account_id, changed_at, payload_version, payload
			) VALUES ('TRANSACTION', ?, 1, ?, ?, NULL, CAST(? AS timestamptz), 1, CAST(? AS jsonb))
			RETURNING sequence
			""", Long.class, entityId, changeType, recipientUserId,
			Timestamp.from(Instant.now()), payload);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "sync-test", "sync-device"));
		return session.accessToken();
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "sync-http-" + suffix + "-" + UUID.randomUUID() + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '同步测试', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private record UserFixture(UUID userId) {
	}
}
