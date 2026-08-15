package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.TransactionRunner;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 SecurityFilterChain + PostgreSQL 验收账户查询、成员可见性、If-Match 乐观锁和 keyset 游标。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccountHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private TransactionRunner transactionRunner;

	@Test
	void ownerEditorViewerCanQueryVisibleAccountWithTheirOwnRoleAndRatio() throws Exception {
		UserFixture owner = insertUser("acc-owner");
		UserFixture editor = insertUser("acc-editor");
		UserFixture viewer = insertUser("acc-viewer");
		AccountSeed account = seedAccount(owner.userId(), "共享账户", Instant.parse("2026-08-15T01:00:00Z"));
		addMembership(account.accountId(), editor.userId(), "EDITOR", "ACTIVE",
			Instant.parse("2026-08-15T02:00:00Z"), null);
		addMembership(account.accountId(), viewer.userId(), "VIEWER", "ACTIVE",
			Instant.parse("2026-08-15T03:00:00Z"), null);

		assertQueryRole(owner, "OWNER", account.accountId());
		assertQueryRole(editor, "EDITOR", account.accountId());
		assertQueryRole(viewer, "VIEWER", account.accountId());
	}

	@Test
	void leftRemovedAndUnrelatedUsersCannotSeeAccount() throws Exception {
		UserFixture owner = insertUser("left-owner");
		UserFixture left = insertUser("left-member");
		UserFixture removed = insertUser("removed-member");
		UserFixture stranger = insertUser("stranger");
		AccountSeed account = seedAccount(owner.userId(), "退出账户", Instant.parse("2026-08-15T01:00:00Z"));
		addMembership(account.accountId(), left.userId(), "VIEWER", "LEFT",
			Instant.parse("2026-08-15T02:00:00Z"), Instant.parse("2026-08-15T03:00:00Z"));
		addMembership(account.accountId(), removed.userId(), "EDITOR", "REMOVED",
			Instant.parse("2026-08-15T02:00:00Z"), Instant.parse("2026-08-15T03:00:00Z"));

		assertInvisibleInListAndDetail(left, account.accountId());
		assertInvisibleInListAndDetail(removed, account.accountId());
		assertInvisibleInListAndDetail(stranger, account.accountId());
		assertQueryRole(owner, "OWNER", account.accountId());
	}

	@Test
	void createdByIsNotUsedAsAuthorizationShortcut() throws Exception {
		UserFixture owner = insertUser("creator-owner");
		UserFixture differentCreator = insertUser("creator-other");
		AccountSeed account = seedAccount(owner.userId(), "创建者分离", Instant.parse("2026-08-15T01:00:00Z"));
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", differentCreator.userId(), account.accountId());

		assertQueryRole(owner, "OWNER", account.accountId());
		assertInvisibleInListAndDetail(differentCreator, account.accountId());
	}

	@Test
	void listUsesStableKeysetPaginationWithoutDuplicatesOrOmissions() throws Exception {
		UserFixture owner = insertUser("paging-owner");
		Instant base = Instant.parse("2026-08-15T01:00:00Z");
		List<AccountSeed> seeds = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			seeds.add(seedAccount(owner.userId(), "分页账户-" + index, base.plusSeconds(index)));
		}
		String token = bearer(owner);

		List<String> collectedIds = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		while (true) {
			var request = get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "2");
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}
			MvcResult result = mvc.perform(request)
				.andExpect(status().isOk())
				.andReturn();
			JsonNode body = json(result);
			body.at("/data").forEach(node -> collectedIds.add(node.get("id").asString()));
			pages++;
			boolean hasMore = body.at("/meta/hasMore").asBoolean();
			if (!hasMore) {
				break;
			}
			cursor = body.at("/meta/nextCursor").asString();
		}

		assertEquals(3, pages);
		assertEquals(5, collectedIds.size());
		assertEquals(5, new HashSet<>(collectedIds).size());
		List<String> expectedOrder = jdbc.queryForList("""
			SELECT id::text FROM accounts WHERE created_by = ?
			ORDER BY created_at DESC, id DESC
			""", String.class, owner.userId());
		assertEquals(expectedOrder, collectedIds);

		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "2")
				.param("cursor", "tampered-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void listRejectsForeignCursorAndInvalidLimit() throws Exception {
		UserFixture owner = insertUser("cursor-owner");
		UserFixture other = insertUser("cursor-other");
		seedAccount(owner.userId(), "游标账户", Instant.parse("2026-08-15T01:00:00Z"));
		seedAccount(other.userId(), "他人账户一", Instant.parse("2026-08-15T02:00:00Z"));
		seedAccount(other.userId(), "他人账户二", Instant.parse("2026-08-15T03:00:00Z"));
		String ownerToken = bearer(owner);
		String otherToken = bearer(other);

		MvcResult otherFirst = mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
				.param("limit", "1"))
			.andExpect(status().isOk())
			.andReturn();
		String foreignCursor = json(otherFirst).at("/meta/nextCursor").asString();
		assertTrue(!foreignCursor.isBlank());

		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "1")
				.param("cursor", foreignCursor))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "0"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "201"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "abc"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void patchUpdatesNameAndInstitutionWithStrictOptimisticLock() throws Exception {
		UserFixture owner = insertUser("patch-owner");
		AccountSeed account = seedAccount(owner.userId(), "更新账户", Instant.parse("2026-08-15T01:00:00Z"));
		String token = bearer(owner);
		String resource = "/api/v1/accounts/" + account.accountId();

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"新名称\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.name").value("新名称"))
			.andExpect(jsonPath("$.data.version").value(2));

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"2\"")
				.contentType("application/merge-patch+json")
				.content("{\"institution\":null}"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
			.andExpect(jsonPath("$.data.institution").value(org.hamcrest.Matchers.nullValue()));

		assertEquals("新名称", jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(null, jdbc.queryForObject("SELECT institution FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(3, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
	}

	@Test
	void patchEnforcesOwnerRoleAndUniformNotFound() throws Exception {
		UserFixture owner = insertUser("perm-owner");
		UserFixture editor = insertUser("perm-editor");
		UserFixture viewer = insertUser("perm-viewer");
		UserFixture stranger = insertUser("perm-stranger");
		AccountSeed account = seedAccount(owner.userId(), "权限账户", Instant.parse("2026-08-15T01:00:00Z"));
		addMembership(account.accountId(), editor.userId(), "EDITOR", "ACTIVE",
			Instant.parse("2026-08-15T02:00:00Z"), null);
		addMembership(account.accountId(), viewer.userId(), "VIEWER", "ACTIVE",
			Instant.parse("2026-08-15T03:00:00Z"), null);
		String resource = "/api/v1/accounts/" + account.accountId();

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(editor))
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"编辑者改名\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(viewer))
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"查看者改名\"}"))
			.andExpect(status().isForbidden());

		mvc.perform(get(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(stranger)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(stranger)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		assertEquals("权限账户", jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
	}

	@Test
	void patchRejectsMissingIllegalAndStaleIfMatchWithoutMutatingAccount() throws Exception {
		UserFixture owner = insertUser("ifmatch-owner");
		AccountSeed account = seedAccount(owner.userId(), "IfMatch账户", Instant.parse("2026-08-15T01:00:00Z"));
		String token = bearer(owner);
		String resource = "/api/v1/accounts/" + account.accountId();

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"缺少IfMatch\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "1")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"未加引号\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"0\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"零版本\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"-1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"负版本\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"2147483648\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"溢出版本\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"accountClass\":\"LIABILITY\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"" + "A".repeat(101) + "\"}"))
			.andExpect(status().isBadRequest());
		assertUnchanged(account.accountId(), "IfMatch账户", 1);

		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"第一次成功\"}"))
			.andExpect(status().isOk());
		mvc.perform(patch(resource)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"过期版本\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(2))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"2\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(resource))
			.andExpect(jsonPath("$.data").doesNotExist());
		assertEquals("第一次成功", jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, account.accountId()));
		assertEquals(2, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
	}

	@Test
	void concurrentPatchAllowsOnlyOneWinnerAndLosingUpdateIsVersionConflict() throws Exception {
		UserFixture owner = insertUser("concurrent-owner");
		AccountSeed account = seedAccount(owner.userId(), "并发账户", Instant.parse("2026-08-15T01:00:00Z"));
		String token = bearer(owner);
		String resource = "/api/v1/accounts/" + account.accountId();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (String name : List.of("并发更新A", "并发更新B")) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return mvc.perform(patch(resource)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.header("If-Match", "\"1\"")
							.contentType("application/merge-patch+json")
							.content("{\"name\":\"" + name + "\"}"))
						.andReturn().getResponse().getStatus();
				}));
			}
			ready.await();
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get());
			}
			assertEquals(1, statuses.stream().filter(value -> value == 200).count());
			assertEquals(1, statuses.stream().filter(value -> value == 409).count());
		} finally {
			executor.shutdownNow();
		}

		assertEquals(2, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, account.accountId()));
		String winnerName = jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, account.accountId());
		assertTrue(Set.of("并发更新A", "并发更新B").contains(winnerName));
	}

	@Test
	void unauthenticatedAccountRoutesReturn401() throws Exception {
		mvc.perform(get("/api/v1/accounts"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
		mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/v1/accounts/" + UUID.randomUUID())
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"name\":\"未认证\"}"))
			.andExpect(status().isUnauthorized());
	}

	private void assertQueryRole(UserFixture user, String expectedRole, UUID accountId) throws Exception {
		String token = bearer(user);
		mvc.perform(get("/api/v1/accounts/{id}", accountId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(accountId.toString()))
			.andExpect(jsonPath("$.data.currentUserRole").value(expectedRole))
			.andExpect(jsonPath("$.data.inclusionRatio").isNotEmpty());

		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].id").value(accountId.toString()))
			.andExpect(jsonPath("$.data[0].currentUserRole").value(expectedRole));
	}

	private void assertInvisibleInListAndDetail(UserFixture user, UUID accountId) throws Exception {
		String token = bearer(user);
		mvc.perform(get("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0))
			.andExpect(jsonPath("$.meta.hasMore").value(false));
		mvc.perform(get("/api/v1/accounts/{id}", accountId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	private void assertUnchanged(UUID accountId, String expectedName, int expectedVersion) {
		assertEquals(expectedName, jdbc.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, accountId));
		assertEquals(expectedVersion, jdbc.queryForObject("SELECT version FROM accounts WHERE id = ?", Integer.class, accountId));
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "account-test", "account-test-device"));
		return session.accessToken();
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "acc-http-" + suffix + "-" + UUID.randomUUID() + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '账户 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId, email);
	}

	private AccountSeed seedAccount(UUID ownerId, String suffix, Instant createdAt) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		transactionRunner.required(() -> {
			jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, institution, currency, note, status,
					 archived_at, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, ?, 'CNY', NULL, 'ACTIVE', NULL, ?, ?, ?, 1)
				""", accountId, suffix, "机构-" + suffix, ownerId, ts(createdAt), ts(createdAt));
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, ownerId, ts(createdAt));
			jdbc.update("""
				INSERT INTO account_inclusion_settings
					(id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(createdAt), ownerId, ts(createdAt));
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, ts(createdAt));
		});
		return new AccountSeed(accountId, ownerId);
	}

	private void addMembership(
		UUID accountId,
		UUID userId,
		String role,
		String status,
		Instant joinedAt,
		Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		transactionRunner.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status, ts(joinedAt),
				endedAt == null ? null : ts(endedAt));
			if ("ACTIVE".equals(status)) {
				jdbc.update("""
					INSERT INTO account_inclusion_settings
						(id, membership_id, included, ratio, valid_from, created_by, created_at)
					VALUES (?, ?, TRUE, 0.500000, ?, ?, ?)
					""", UUID.randomUUID(), membershipId, ts(joinedAt), userId, ts(joinedAt));
			}
		});
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private java.sql.Timestamp ts(Instant instant) {
		return java.sql.Timestamp.from(instant);
	}

	private record UserFixture(UUID userId, String email) {
	}

	private record AccountSeed(UUID accountId, UUID ownerId) {
	}
}
