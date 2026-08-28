package app.ziji;

import java.time.Instant;
import java.util.UUID;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-DASH-003/006 金标准：真实 HTTP 与 PostgreSQL 下的月度收支、资产/净资产趋势与账户结构。
 * 聚合边界验证时区规则：跨月交易按入账时固化的业务日期（用户时区）归桶；
 * 收支只包含真实收入与支出；账户趋势按期末累计余额。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class StatisticsHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DeviceSessionApplicationService sessions;
	@Autowired private tools.jackson.databind.ObjectMapper objectMapper;

	@Test
	void statisticsBucketsFollowFrozenBusinessDatesAndKeepSeriesConsistentWithFacts() throws Exception {
		User owner = user("stat-golden-owner");
		String token = bearer(owner);
		UUID bank = createAccount(token, "stat-golden-bank-0001", "ASSET", "BANK", "工资卡", "20000.00");
		createAccount(token, "stat-golden-fund-0001", "INVESTMENT", "FUND", "券商现金", "50000.00");
		UUID card = createAccount(token, "stat-golden-card-0001", "LIABILITY", "CREDIT_CARD", "信用卡", "2000.00");
		UUID incomeCategory = category(owner.id(), "INCOME");
		UUID expenseCategory = category(owner.id(), "EXPENSE");

		// 真实支出 300：业务日期 2026-08-18（上海）。
		postTransaction(token, "stat-golden-spend-0001", """
			{"type":"EXPENSE","businessAt":"2026-08-18T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"300.00","currency":"CNY","categoryId":"%s"}
			""".formatted(card, expenseCategory));
		// 真实收入 8,000：2026-08-31T20:00Z 在上海时区已跨入 2026-09-01，按固化业务日期归入 9 月桶。
		postTransaction(token, "stat-golden-income-0001", """
			{"type":"INCOME","businessAt":"2026-08-31T20:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"8000.00","currency":"CNY","categoryId":"%s"}
			""".formatted(bank, incomeCategory));

		// 月度收支：8 月桶只有支出 300；9 月桶只有收入 8,000（时区跨月归属）。
		mvc.perform(get("/api/v1/statistics/cash-flow")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-01").param("dateTo", "2026-09-30").param("granularity", "MONTH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.baseCurrency").value("CNY"))
			.andExpect(jsonPath("$.data.valuationRevision").value(1))
			.andExpect(jsonPath("$.data.points[0].businessDate").value("2026-08-01"))
			.andExpect(jsonPath("$.data.points[0].values.income").value("0.00"))
			.andExpect(jsonPath("$.data.points[0].values.expense").value("300.00"))
			.andExpect(jsonPath("$.data.points[0].values.netCashFlow").value("-300.00"))
			.andExpect(jsonPath("$.data.points[1].businessDate").value("2026-09-01"))
			.andExpect(jsonPath("$.data.points[1].values.income").value("8000.00"))
			.andExpect(jsonPath("$.data.points[1].values.expense").value("0.00"))
			.andExpect(jsonPath("$.data.points[1].values.netCashFlow").value("8000.00"));

		// 资产与净资产趋势：8 月末 70,000/67,700（支出已入账，收入未跨月到期）；9 月末 78,000/75,700。
		mvc.perform(get("/api/v1/statistics/assets")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-01").param("dateTo", "2026-09-30").param("granularity", "MONTH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.points[0].businessDate").value("2026-08-01"))
			.andExpect(jsonPath("$.data.points[0].values.totalAssets").value("70000.00"))
			.andExpect(jsonPath("$.data.points[0].values.netAssets").value("67700.00"))
			.andExpect(jsonPath("$.data.points[1].businessDate").value("2026-09-01"))
			.andExpect(jsonPath("$.data.points[1].values.totalAssets").value("78000.00"))
			.andExpect(jsonPath("$.data.points[1].values.netAssets").value("75700.00"));

		// 账户结构趋势：values 以账户 ID 为键，期末累计余额；汇总与 cash-flow/资产口径一致。
		MvcResult accountsSeries = mvc.perform(get("/api/v1/statistics/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-01").param("dateTo", "2026-09-30").param("granularity", "MONTH"))
			.andExpect(status().isOk()).andReturn();
		JsonNode series = json(accountsSeries).at("/data/points");
		JsonNode august = series.get(0).at("/values");
		JsonNode september = series.get(1).at("/values");
		assertEquals("20000.00", august.at("/" + bank).asString());
		assertEquals("2300.00", august.at("/" + card).asString());
		assertEquals("28000.00", september.at("/" + bank).asString());
		assertEquals("2300.00", september.at("/" + card).asString());
		// 账户序列汇总与资产趋势一致：资产账户合计 78,000，负债账户 2,300。
		java.math.BigDecimal assetSum = java.math.BigDecimal.ZERO;
		java.math.BigDecimal liabilitySum = java.math.BigDecimal.ZERO;
		for (var field : september.properties()) {
			java.math.BigDecimal value = new java.math.BigDecimal(field.getValue().asString());
			if (field.getKey().equals(card.toString())) {
				liabilitySum = liabilitySum.add(value);
			} else if (!field.getKey().equals("others")) {
				assetSum = assetSum.add(value);
			}
		}
		assertEquals(0, assetSum.compareTo(new java.math.BigDecimal("78000")));
		assertEquals(0, liabilitySum.compareTo(new java.math.BigDecimal("2300")));

		// 校验失败边界与认证门禁。
		mvc.perform(get("/api/v1/statistics/cash-flow").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-09-30").param("dateTo", "2026-08-01"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/statistics/cash-flow").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-01").param("granularity", "HOUR"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/statistics/assets"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void granularityBucketsAlignToCalendarRulesAndKeepDenseSeries() throws Exception {
		User owner = user("stat-gran-owner");
		String token = bearer(owner);
		UUID bank = createAccount(token, "stat-gran-bank-0001", "ASSET", "BANK", "现金卡", "5000.00");
		UUID expenseCategory = category(owner.id(), "EXPENSE");
		// 2026-08-18（周二）支出 100、2026-08-20（周四）支出 200、2026-08-25（周二）支出 30。
		postTransaction(token, "stat-gran-spend-0001", """
			{"type":"EXPENSE","businessAt":"2026-08-18T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"100.00","currency":"CNY","categoryId":"%s"}
			""".formatted(bank, expenseCategory));
		postTransaction(token, "stat-gran-spend-0002", """
			{"type":"EXPENSE","businessAt":"2026-08-20T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"200.00","currency":"CNY","categoryId":"%s"}
			""".formatted(bank, expenseCategory));
		postTransaction(token, "stat-gran-spend-0003", """
			{"type":"EXPENSE","businessAt":"2026-08-25T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"30.00","currency":"CNY","categoryId":"%s"}
			""".formatted(bank, expenseCategory));

		// DAY：密集序列逐日归属，无事实日期也为 0。
		mvc.perform(get("/api/v1/statistics/cash-flow")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-18").param("dateTo", "2026-08-20").param("granularity", "DAY"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.points[0].businessDate").value("2026-08-18"))
			.andExpect(jsonPath("$.data.points[0].values.expense").value("100.00"))
			.andExpect(jsonPath("$.data.points[1].businessDate").value("2026-08-19"))
			.andExpect(jsonPath("$.data.points[1].values.expense").value("0.00"))
			.andExpect(jsonPath("$.data.points[2].businessDate").value("2026-08-20"))
			.andExpect(jsonPath("$.data.points[2].values.expense").value("200.00"));

		// WEEK：桶起始对齐周一（date_trunc week），08-18/08-20 都落入 08-17 起始桶。
		mvc.perform(get("/api/v1/statistics/cash-flow")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-18").param("dateTo", "2026-08-25").param("granularity", "WEEK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.points[0].businessDate").value("2026-08-17"))
			.andExpect(jsonPath("$.data.points[0].values.expense").value("300.00"))
			.andExpect(jsonPath("$.data.points[1].businessDate").value("2026-08-24"))
			.andExpect(jsonPath("$.data.points[1].values.expense").value("30.00"));

		// YEAR：单桶覆盖全年，收支合计与明细一致。
		mvc.perform(get("/api/v1/statistics/cash-flow")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("dateFrom", "2026-08-01").param("dateTo", "2026-09-30").param("granularity", "YEAR"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.points[0].businessDate").value("2026-01-01"))
			.andExpect(jsonPath("$.data.points[0].values.expense").value("330.00"))
			.andExpect(jsonPath("$.data.points[0].values.netCashFlow").value("-330.00"));
	}

	private User user(String suffix) {
		UUID id = UUID.randomUUID();
		String email = suffix + "-" + id + "@example.test";
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-hash', 1, '统计金标准', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", id, email, email, now, now, now);
		return new User(id);
	}

	private String bearer(User user) {
		SessionTokenResult session = sessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.id(), "stat-http", "stat-device-" + user.id()));
		return session.accessToken();
	}

	private UUID createAccount(
		String token, String key, String accountClass, String accountType, String name, String openingAmount)
		throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"accountClass":"%s","accountType":"%s","name":"%s","currency":"CNY",
					 "openingBalance":{"amount":"%s","businessAt":"2026-08-17T16:00:00Z","note":"期初录入"}}
					""".formatted(accountClass, accountType, name, openingAmount)))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.account.id").isString()).andReturn();
		return UUID.fromString(json(result).at("/data/account/id").asString());
	}

	private UUID category(UUID ownerId, String type) {
		UUID categoryId = UUID.randomUUID();
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		String name = type + "-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, type, name, name, now, now);
		return categoryId;
	}

	private void postTransaction(String token, String key, String body) throws Exception {
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private record User(UUID id) {
	}
}
