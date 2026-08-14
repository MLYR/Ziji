package app.ziji;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ZijiBackendApplicationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void contextLoads() {
		// 上下文启动会同时验证 Testcontainers 数据源和全部 Flyway 迁移。
	}

	@Test
	void healthIsPublicAndCarriesRequestId() throws Exception {
		// 运维健康端点必须可探测，同时保留请求链路标识。
		mvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(header().exists("X-Request-ID"));
	}

	@Test
	void applicationEndpointsAreDeniedByDefault() throws Exception {
		// 认证用例完成前，未知业务路径必须 fail closed。
		mvc.perform(get("/api/v1/not-yet-implemented"))
			.andExpect(status().isForbidden());
	}

	@Test
	void currentUserEndpointRequiresAuthentication() throws Exception {
		// 用户资料路径仅开放给已认证主体，匿名请求必须在安全过滤器处返回 401。
		mvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedUuidCanReadCurrentUserThroughSecurityFilterChain() throws Exception {
		UUID userId = insertUser();

		mvc.perform(get("/api/v1/users/me").with(user(userId.toString())))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.id").value(userId.toString()));
	}

	@Test
	void authenticatedUuidCanPatchCurrentUserWithCsrfThroughSecurityFilterChain() throws Exception {
		UUID userId = insertUser();

		mvc.perform(patch("/api/v1/users/me")
				.with(user(userId.toString()))
				.with(csrf())
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"nickname\":\"安全链路昵称\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.data.nickname").value("安全链路昵称"));
	}

	@Test
	void authenticatedPrincipalWithoutUserRowIsRejectedAsAuthenticationFailure() throws Exception {
		UUID missingUserId = UUID.randomUUID();

		mvc.perform(get("/api/v1/users/me").with(user(missingUserId.toString())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void invalidRequestIdIsReplacedBeforeUserProblemIsBuilt() throws Exception {
		UUID userId = insertUser();
		String maliciousRequestId = "x".repeat(101) + "<invalid>";

		MvcResult result = mvc.perform(patch("/api/v1/users/me")
				.with(user(userId.toString()))
				.with(csrf())
				.header("X-Request-ID", maliciousRequestId)
				.header("If-Match", "\"1\"")
				.contentType("application/merge-patch+json")
				.content("{\"unknown\":\"x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andReturn();

		String responseRequestId = result.getResponse().getHeader("X-Request-ID");
		String problemRequestId = objectMapper.readTree(result.getResponse().getContentAsString())
			.get("requestId").textValue();
		assertNotNull(responseRequestId);
		assertNotNull(problemRequestId);
		assertEquals(responseRequestId, problemRequestId);
		assertNotEquals(maliciousRequestId, responseRequestId);
		assertTrue(responseRequestId.length() <= 100);
	}

	@Test
	void postedLedgerEntriesCannotBeUpdated() {
		// 使用真实 PostgreSQL 证明 V007 的不可变事实约束生效，而不是只检查迁移脚本文本。
		UUID userId = UUID.randomUUID();
		UUID debitAccountId = UUID.randomUUID();
		UUID creditAccountId = UUID.randomUUID();
		UUID transactionId = UUID.randomUUID();
		UUID debitEntryId = UUID.randomUUID();
		Instant now = Instant.now();
		LocalDate businessDate = LocalDate.now();

		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash,
				password_hash_version, nickname, timezone, created_at, updated_at)
			VALUES (?, ?, ?, ?, 'test-hash', 1, 'test-user', 'Asia/Shanghai', ?, ?)
			""", userId, userId + "@example.test", userId + "@example.test",
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
		jdbc.update("""
			INSERT INTO ledger_accounts
				(id, owner_user_id, code, ledger_role, account_nature, currency, created_at)
			VALUES (?, ?, 'TEST_DEBIT', 'SYSTEM', 'ASSET', 'CNY', ?),
				(?, ?, 'TEST_CREDIT', 'SYSTEM', 'EQUITY', 'CNY', ?)
			""", debitAccountId, userId, Timestamp.from(now), creditAccountId, userId, Timestamp.from(now));
		jdbc.update("""
			INSERT INTO transactions
				(id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
			VALUES (?, 'ADJUSTMENT', 'DRAFT', ?, ?, 'Asia/Shanghai', 'ADJUSTMENT', ?, 1, ?, ?, ?, ?)
			""", transactionId, Timestamp.from(now), Date.valueOf(businessDate), transactionId,
			userId, userId, Timestamp.from(now), Timestamp.from(now));
		jdbc.update("""
			INSERT INTO ledger_entries
				(id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
			VALUES (?, ?, ?, 1, 'D', 10.00, 'CNY', ?, ?),
				(?, ?, ?, 2, 'C', 10.00, 'CNY', ?, ?)
			""", debitEntryId, transactionId, debitAccountId, Date.valueOf(businessDate), Timestamp.from(now),
			UUID.randomUUID(), transactionId, creditAccountId, Date.valueOf(businessDate), Timestamp.from(now));
		jdbc.update("UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
			Timestamp.from(now), Timestamp.from(now), transactionId);

		assertThrows(DataAccessException.class,
			() -> jdbc.update("UPDATE ledger_entries SET amount = 11.00 WHERE id = ?", debitEntryId));
		BigDecimal storedAmount = jdbc.queryForObject(
			"SELECT amount FROM ledger_entries WHERE id = ?", BigDecimal.class, debitEntryId);
		org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal("10.00").compareTo(storedAmount));
	}

	private UUID insertUser() {
		UUID userId = UUID.randomUUID();
		Instant now = Instant.parse("2026-08-14T00:00:00Z");
		String email = userId + "@example.test";
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash,
				 password_hash_version, nickname, timezone, base_currency, locale, amount_format,
				 status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-only-hash', 1, '原昵称', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', ?, ?, 1)
			""", userId, email, email, java.sql.Timestamp.from(now),
			java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
		return userId;
	}
}
