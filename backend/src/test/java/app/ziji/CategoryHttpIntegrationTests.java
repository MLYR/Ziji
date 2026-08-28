package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountType;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PostgreSQL 验收 T-CAT-001/002：两级分类、同树唯一、默认与账户分类可见性。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CategoryHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private AccountCreationService accountCreationService;

	@Test
	void unauthenticatedCreateIsRejected() throws Exception {
		mvc.perform(post("/api/v1/categories")
				.header("Idempotency-Key", "category-unauthenticated-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"未认证分类","categoryType":"EXPENSE"}
					"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void unauthenticatedUpdateAndMergeAreRejected() throws Exception {
		mvc.perform(patch("/api/v1/categories/{categoryId}", UUID.randomUUID())
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"未认证修改\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(post("/api/v1/categories/{categoryId}/merge", UUID.randomUUID())
				.header("Idempotency-Key", "category-merge-unauth-001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetCategoryId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void updatesCategoryWithOptimisticVersionAndRejectsNameConflict() throws Exception {
		User owner = insertUser("update");
		String token = bearer(owner);
		UUID rootId = extractId(create(token, "category-update-root-00001", """
			{"name":"原分类","categoryType":"EXPENSE"}
			"""));
		create(token, "category-update-target-01", """
			{"name":"目标分类","categoryType":"EXPENSE"}
			""");

		mvc.perform(patch("/api/v1/categories/{categoryId}", rootId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"2\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"新名字\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));

		mvc.perform(patch("/api/v1/categories/{categoryId}", rootId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"目标分类\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_NAME_ALREADY_EXISTS"));

		mvc.perform(patch("/api/v1/categories/{categoryId}", rootId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("""
					{"name":"更新分类","status":"INACTIVE"}
					"""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.name").value("更新分类"))
			.andExpect(jsonPath("$.data.status").value("INACTIVE"));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM categories WHERE id = ? AND name = '更新分类' AND status = 'INACTIVE' AND version = 2",
			Integer.class, rootId));
	}

	@Test
	void mergesUsedAccountCategoryAndReplaysWithoutChangingHistory() throws Exception {
		User owner = insertUser("merge");
		UUID accountId = accountCreationService.createAccount(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.BANK, "合并现金", null,
			app.ziji.account.domain.AccountCurrency.CNY, null, owner.userId())).id();
		String token = bearer(owner);
		UUID sourceId = extractId(create(token, "category-merge-source-001", """
			{"name":"旧支出","categoryType":"EXPENSE","accountId":"%s"}
			""".formatted(accountId)));
		UUID targetId = extractId(create(token, "category-merge-target-001", """
			{"name":"新支出","categoryType":"EXPENSE","accountId":"%s"}
			""".formatted(accountId)));
		String key = "category-merge-idempotent-1";
		String transactionBody = """
			{"type":"EXPENSE","businessAt":"2026-08-29T12:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"10.00","currency":"CNY","categoryId":"%s","tagIds":[]}
			""".formatted(accountId, sourceId);
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "category-merge-transaction-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(transactionBody))
			.andExpect(status().isCreated());

		MvcResult firstMerge = merge(token, sourceId, targetId, key, "\"1\"");
		assertEquals(targetId, UUID.fromString(objectMapper.readTree(
			firstMerge.getResponse().getContentAsString()).at("/data/mergedIntoId").asString()));
		MvcResult replayMerge = merge(token, sourceId, targetId, key, "\"1\"");
		assertEquals(extractId(firstMerge), extractId(replayMerge));
		assertEquals(firstMerge.getResponse().getHeader(HttpHeaders.ETAG),
			replayMerge.getResponse().getHeader(HttpHeaders.ETAG));
		assertEquals(1, idempotencyCount(owner.userId(), key, "mergeCategory"));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM categories WHERE id = ? AND status = 'MERGED' AND merged_into_id = ? AND version = 2",
			Integer.class, sourceId, targetId));
		// 合并不迁移、不删除历史交易分类映射；旧事实仍指向已合并分类。
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM transaction_categories WHERE category_id = ?",
			Integer.class, sourceId));

		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "category-merge-reuse-denied")
				.contentType(MediaType.APPLICATION_JSON)
				.content(transactionBody.replace("10.00", "11.00")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
	}

	@Test
	void accountViewerCannotUpdateOrMergeCategory() throws Exception {
		User owner = insertUser("merge-viewer");
		UUID accountId = accountCreationService.createAccount(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.BANK, "只读现金", null,
			app.ziji.account.domain.AccountCurrency.CNY, null, owner.userId())).id();
		String ownerToken = bearer(owner);
		UUID sourceId = extractId(create(ownerToken, "category-view-source-001", """
			{"name":"只读旧分类","categoryType":"INCOME","accountId":"%s"}
			""".formatted(accountId)));
		UUID targetId = extractId(create(ownerToken, "category-view-target-001", """
			{"name":"只读新分类","categoryType":"INCOME","accountId":"%s"}
			""".formatted(accountId)));
		User viewer = insertUser("merge-viewer-member");
		insertMembership(accountId, viewer.userId(), "VIEWER");
		String viewerToken = bearer(viewer);

		mvc.perform(patch("/api/v1/categories/{categoryId}", sourceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"越权改名\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
		mvc.perform(post("/api/v1/categories/{categoryId}/merge", sourceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
				.header("Idempotency-Key", "category-view-merge-00001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetCategoryId\":\"" + targetId + "\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
	}

	@Test
	void createsTwoLevelPersonalCategoriesAndRejectsThirdLevelAndDuplicates() throws Exception {
		User owner = insertUser("hierarchy");
		String token = bearer(owner);
		MvcResult root = create(token, "category-level-one-00001", """
			{"name":"项目收入","categoryType":"INCOME"}
			""");
		UUID rootId = UUID.fromString(root.getResponse().getContentAsString()
			.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1"));

		create(token, "category-level-two-0001", """
			{"name":"副业收入","categoryType":"INCOME","parentId":"%s"}
			""".formatted(rootId));

		mvc.perform(post("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "category-level-three-01")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"第三级","categoryType":"INCOME",
					 "parentId":"%s"}
					""".formatted(childId(owner.userId(), rootId)))
				)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(owner.userId(), "category-level-three-01"));

		mvc.perform(post("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "category-name-conflict-001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"  副业收入 ","categoryType":"INCOME","parentId":"%s"}
					""".formatted(rootId)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_NAME_ALREADY_EXISTS"));
		assertEquals(1, idempotencyCount(owner.userId(), "category-name-conflict-001"));

		String list = mvc.perform(get("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meta.hasMore").value(false))
			.andReturn().getResponse().getContentAsString();
		JsonNode categories = objectMapper.readTree(list).get("data");
		// 固定种子 5 个默认分类，加本次合法根和子分类，冲突请求不新增事实。
		assertEquals(7, categories.size());
		assertTrue(containsName(categories, "副业收入"));
		assertTrue(containsName(categories, "餐饮"));
	}

	@Test
	void accountScopedCategoriesFollowMembershipAndAccountFilter() throws Exception {
		User owner = insertUser("account-scope");
		UUID accountId = accountCreationService.createAccount(new AccountCreationCommand(
			AccountClass.ASSET, AccountType.BANK, "家庭现金", null,
			app.ziji.account.domain.AccountCurrency.CNY, null, owner.userId())).id();
		String ownerToken = bearer(owner);
		MvcResult accountRoot = create(ownerToken, "category-account-root-0001", """
			{"name":"家庭餐饮","categoryType":"EXPENSE","accountId":"%s"}
			""".formatted(accountId));
		UUID accountRootId = UUID.fromString(accountRoot.getResponse().getContentAsString()
			.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1"));
		create(ownerToken, "category-account-child-001", """
			{"name":"买菜","categoryType":"EXPENSE","accountId":"%s","parentId":"%s"}
			""".formatted(accountId, accountRootId));

		String filtered = mvc.perform(get("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("accountId", accountId.toString()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode categories = objectMapper.readTree(filtered).get("data");
		assertTrue(containsName(categories, "家庭餐饮"));
		assertTrue(containsName(categories, "买菜"));
		assertTrue(containsName(categories, "餐饮"));

		User viewer = insertUser("account-viewer");
		insertMembership(accountId, viewer.userId(), "VIEWER");
		mvc.perform(post("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer))
				.header("Idempotency-Key", "category-viewer-denied-001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"只读分类","categoryType":"EXPENSE","accountId":"%s"}
					""".formatted(accountId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
	}

	@Test
	void sameIdempotencyKeyReplaysCategoryWithoutNewFact() throws Exception {
		User owner = insertUser("idempotent");
		String token = bearer(owner);
		String key = "category-idempotent-replay1";
		String body = """
			{"name":"个人支出","categoryType":"EXPENSE"}
			""";
		MvcResult first = create(token, key, body);
		MvcResult replay = create(token, key, body);

		assertEquals(extractId(first), extractId(replay));
		assertEquals(first.getResponse().getHeader(HttpHeaders.ETAG),
			replay.getResponse().getHeader(HttpHeaders.ETAG));
		assertEquals(1, idempotencyCount(owner.userId(), key));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM categories WHERE owner_user_id = ? AND name = '个人支出'",
			Integer.class, owner.userId()));
	}

	private MvcResult create(String token, String key, String body) throws Exception {
		return mvc.perform(post("/api/v1/categories")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andReturn();
	}

	private UUID childId(UUID userId, UUID rootId) {
		return jdbc.queryForObject("""
			SELECT id FROM categories
			WHERE owner_user_id = ? AND parent_id = ?
			ORDER BY created_at DESC LIMIT 1
			""", UUID.class, userId, rootId);
	}

	private boolean containsName(JsonNode categories, String expected) {
		for (JsonNode category : categories) {
			if (expected.equals(category.get("name").textValue())) {
				return true;
			}
		}
		return false;
	}

	private MvcResult merge(String token, UUID sourceId, UUID targetId, String key, String ifMatch) throws Exception {
		return mvc.perform(post("/api/v1/categories/{categoryId}/merge", sourceId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.header("If-Match", ifMatch)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetCategoryId\":\"" + targetId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("MERGED"))
			.andReturn();
	}

	private UUID extractId(MvcResult result) throws Exception {
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
			.get("data").get("id").textValue());
	}

	private int idempotencyCount(UUID userId, String key) {
		return jdbc.queryForObject("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND operation_id = 'createCategory' AND idempotency_key = ?
			""", Integer.class, userId, key);
	}

	private int idempotencyCount(UUID userId, String key, String operationId) {
		return jdbc.queryForObject("""
			SELECT count(*) FROM idempotency_records
			WHERE user_id = ? AND operation_id = ? AND idempotency_key = ?
			""", Integer.class, userId, operationId, key);
	}

	private String bearer(User user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "category-http", "category-http-" + user.userId()));
		return session.accessToken();
	}

	private User insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "category-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '分类 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new User(userId);
	}

	private void insertMembership(UUID accountId, UUID userId, String role) {
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
			VALUES (?, ?, ?, ?, 'ACTIVE', CAST(? AS timestamptz), 2, 1)
			""", membershipId, accountId, userId, role, now.toString());
		jdbc.update("""
			INSERT INTO account_inclusion_settings
				(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, ?, CAST(? AS numeric), CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""", UUID.randomUUID(), membershipId, true, new BigDecimal("1.000000"),
			now.toString(), userId, now.toString());
	}

	private record User(UUID userId) {
	}
}
