package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T-DASH-001/002/009 金标准：混合普通资产、投资（券商现金）与负债账户经真实 HTTP 与
 * PostgreSQL 重建五个核心指标；基准币种、asOf、asOfSequence 与质量告警显式呈现；
 * 非基准币种账户绝不按 0 或 1 静默折算；归档账户退出统计。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DashboardHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DeviceSessionApplicationService sessions;
	@Autowired private SyncOutboxConsumer outboxConsumer;
	@Autowired private tools.jackson.databind.ObjectMapper objectMapper;
	@Autowired private app.ziji.investment.application.InvestmentApplicationService investments;

	@Test
	void dashboardRebuildsCoreMetricsFromPostedFactsAndWarnsOnMissingRates() throws Exception {
		User owner = user("dash-golden-owner");
		String token = bearer(owner);
		UUID bank = createAccount(token, "dash-golden-bank-0001", "ASSET", "BANK", "工资卡", "CNY", "20000.00");
		createAccount(token, "dash-golden-fund-0001", "INVESTMENT", "FUND", "券商现金", "CNY", "50000.00");
		UUID card = createAccount(token, "dash-golden-card-0001", "LIABILITY", "CREDIT_CARD", "信用卡", "CNY", "2000.00");
		UUID usdBank = createAccount(token, "dash-golden-usd-00001", "ASSET", "BANK", "美元卡", "USD", "100");

		UUID expenseCategory = category(owner.id());
		postTransaction(token, "dash-golden-spend-0001", """
			{"type":"EXPENSE","businessAt":"2026-08-18T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"300.00","currency":"CNY","categoryId":"%s"}
			""".formatted(card, expenseCategory));
		// 第二笔支出业务日期在真实时钟 +10 天，用于验证 projectionAsOf 的业务日期截止。
		String futureBusinessAt = Instant.now().plusSeconds(10 * 24 * 3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
		postTransaction(token, "dash-golden-spend-0002", """
			{"type":"EXPENSE","businessAt":"%s","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"200.00","currency":"CNY","categoryId":"%s"}
			""".formatted(futureBusinessAt, card, expenseCategory));

		drainOutbox();
		MvcResult result = mvc.perform(get("/api/v1/dashboard")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meta.requestId").isNotEmpty())
			.andExpect(jsonPath("$.data.baseCurrency").value("CNY"))
			.andExpect(jsonPath("$.data.asOf").isNotEmpty())
			.andExpect(jsonPath("$.data.valuationRevision").value(1))
			.andExpect(jsonPath("$.data.projectionStatus").value("CURRENT"))
			// 总资产 = 普通资产 20,000 + 投资券商现金 50,000；信用卡消费只增加负债；美元账户不按 0/1 折算。
			.andExpect(jsonPath("$.data.summary.totalAssets").value("70000.00"))
			// 可用资金只包含普通 ASSET 账户可用余额：20,000；投资与负债不计入。
			.andExpect(jsonPath("$.data.summary.availableFunds").value("20000.00"))
			.andExpect(jsonPath("$.data.summary.investmentAssets").value("50000.00"))
			.andExpect(jsonPath("$.data.summary.totalLiabilities").value("2300.00"))
			.andExpect(jsonPath("$.data.summary.netAssets").value("67700.00"))
			.andExpect(jsonPath("$.data.changeAttribution.income").value("0.00"))
			.andExpect(jsonPath("$.data.changeAttribution.expense").value("0.00"))
			.andExpect(jsonPath("$.data.changeAttribution.market").value("0.00"))
			.andExpect(jsonPath("$.data.changeAttribution.fx").value("0.00"))
			.andExpect(jsonPath("$.data.changeAttribution.adjustment").value("0.00"))
			.andExpect(jsonPath("$.data.changeAttribution.inclusion").value("0.00"))
			.andExpect(jsonPath("$.data.distribution").isEmpty())
			.andExpect(jsonPath("$.data.investmentOverview.baseCurrency").value("CNY"))
			.andExpect(jsonPath("$.data.investmentOverview.brokerCash").value("50000.00"))
			.andExpect(jsonPath("$.data.investmentOverview.positionMarketValue").value("0.00"))
			.andExpect(jsonPath("$.data.investmentOverview.totalInvestmentAssets").value("50000.00"))
			.andExpect(jsonPath("$.data.investmentOverview.unpricedInstrumentCount").value(0))
			.andExpect(jsonPath("$.data.dataQualityWarnings[0].code").value("MISSING_EXCHANGE_RATES"))
			.andExpect(jsonPath("$.data.dataQualityWarnings[0].affectedCount").value(1))
			.andReturn();

		String asOf = json(result).at("/data/asOf").asString();
		assertTrue(!asOf.isBlank());
		long asOfSequence = json(result).at("/data/asOfSequence").asLong();
		assertTrue(asOfSequence >= 1, "outbox 消费后 asOfSequence 应至少为 1");

		// 未认证请求继续 fail closed；非法 asOf 显式 400。
		mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("asOf", "not-a-timestamp"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		// projectionAsOf：asOf = 真实时钟 +15 天，晚于第二笔支出业务日期（+10 天）但早于未来更多事实，
		// 该时点负债含第二笔支出（2,500、净资产 67,500），与当前值（未来业务日期未到期，2,300）不同。
		String historicalAsOf = Instant.now().plusSeconds(15 * 24 * 3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
		mvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("asOf", historicalAsOf))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.asOf").value(historicalAsOf))
			.andExpect(jsonPath("$.data.summary.totalAssets").value("70000.00"))
			.andExpect(jsonPath("$.data.summary.totalLiabilities").value("2500.00"))
			.andExpect(jsonPath("$.data.summary.netAssets").value("67500.00"))
			.andExpect(jsonPath("$.data.dataQualityWarnings[0].code").value("MISSING_EXCHANGE_RATES"))
			.andExpect(jsonPath("$.data.dataQualityWarnings[0].affectedCount").value(1));

		// 归档美元账户后统计立即排除，质量告警随之消失。
		mvc.perform(post("/api/v1/accounts/" + usdBank + "/archive")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "dash-golden-archive-0001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"清理测试账户\",\"confirmNonZeroBalance\":true}"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/dashboard").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary.totalAssets").value("70000.00"))
			.andExpect(jsonPath("$.data.dataQualityWarnings").isEmpty());
	}

	/** BE-DASH-004：券商现金、持仓市值、投资收益与行情质量完整纳入 Dashboard 且与投资 API 一致。 */
	@Test
	void dashboardIntegratesInvestmentPositionsAndMarketDataQualityWarnings() throws Exception {
		User owner = user("dash-inv-owner");
		String token = bearer(owner);
		UUID account = createAccount(token, "dash-inv-account-0001", "INVESTMENT", "FUND", "券商现金", "CNY", "50000.00");
		String instrumentId = createInstrument(token, "dash-inv-inst-0001", "STOCK", "投资整合股票");
		createManualPrice(token, instrumentId, "CLOSE", "15.00");
		String unpricedId = createInstrument(token, "dash-inv-inst-0002", "STOCK", "无价格股票");
		postInvestmentTrade(token, "dash-inv-buy-0001", """
			{"side":"BUY","investmentAccountId":"%s","instrumentId":"%s","quantity":"60",
			 "unitPrice":"10.00","currency":"CNY","feeAmount":"0.00","taxAmount":"0.00",
			 "tradeAt":"2026-08-18T02:00:00Z"}
			""".formatted(account, instrumentId));
		postInvestmentTrade(token, "dash-inv-buy-0002", """
			{"side":"BUY","investmentAccountId":"%s","instrumentId":"%s","quantity":"10",
			 "unitPrice":"5.00","currency":"CNY","feeAmount":"0.00","taxAmount":"0.00",
			 "tradeAt":"2026-08-18T03:00:00Z"}
			""".formatted(account, unpricedId));
		drainOutbox();

		MvcResult result = mvc.perform(get("/api/v1/dashboard")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			// 券商现金 49,350（50,000 - 600 - 50）+ 已估值持仓市值 900（60×15）；无价格持仓不计 0。
			.andExpect(jsonPath("$.data.summary.investmentAssets").value("50250.00"))
			.andExpect(jsonPath("$.data.summary.totalAssets").value("50250.00"))
			.andExpect(jsonPath("$.data.summary.availableFunds").value("0.00"))
			.andExpect(jsonPath("$.data.investmentOverview.brokerCash").value("49350.00"))
			.andExpect(jsonPath("$.data.investmentOverview.positionMarketValue").value("900.00"))
			.andExpect(jsonPath("$.data.investmentOverview.totalInvestmentAssets").value("50250.00"))
			.andExpect(jsonPath("$.data.investmentOverview.unpricedInstrumentCount").value(1))
			.andReturn();
		assertTrue(hasWarning(json(result), "UNPRICED_INSTRUMENTS", 1), "无价格持仓必须提示 UNPRICED_INSTRUMENTS。");
		assertTrue(!hasWarning(json(result), "STALE_MARKET_DATA", 1), "新建价格初始必须为 FRESH。");

		// 将已估值产品的行情获取时间回拨到 10 天前：估值使用旧快照并提示 STALE_MARKET_DATA。
		jdbc.update("UPDATE price_snapshots SET fetched_at = ? WHERE instrument_id = ?",
			java.sql.Timestamp.from(Instant.now().minusSeconds(10L * 24 * 3600)), UUID.fromString(instrumentId));
		MvcResult stale = mvc.perform(get("/api/v1/dashboard")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary.investmentAssets").value("50250.00"))
			.andReturn();
		assertTrue(hasWarning(json(stale), "STALE_MARKET_DATA", 1), "过期行情必须提示 STALE_MARKET_DATA。");
	}

	private String createInstrument(String token, String key, String type, String name) throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/instruments")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"instrumentType\":\"%s\",\"name\":\"%s\",\"market\":\"CN\",\"currency\":\"CNY\"}".formatted(type, name)))
			.andExpect(status().isCreated()).andReturn();
		return json(result).at("/data/id").asString();
	}

	private void createManualPrice(String token, String instrumentId, String priceType, String price) throws Exception {
		mvc.perform(post("/api/v1/instruments/" + instrumentId + "/manual-prices")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "dash-inv-price-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"priceType\":\"%s\",\"businessDate\":\"%s\",\"price\":\"%s\",\"currency\":\"CNY\",\"reason\":\"投资整合估值\"}".formatted(priceType, java.time.LocalDate.now(), price)))
			.andExpect(status().isCreated());
	}

	private void postInvestmentTrade(String token, String key, String body) throws Exception {
		MvcResult tradeResult = mvc.perform(post("/api/v1/investment-trades")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn();
		if (tradeResult.getResponse().getStatus() != 201) {
			throw new AssertionError("trade 失败：" + tradeResult.getResponse().getStatus() + " "
				+ tradeResult.getResponse().getContentAsString());
		}
	}

	private boolean hasWarning(tools.jackson.databind.JsonNode result, String code, int affectedCount) {
		var warnings = result.at("/data/dataQualityWarnings");
		if (!warnings.isArray()) {
			return false;
		}
		for (var warning : warnings) {
			if (code.equals(warning.at("/code").asString())
				&& affectedCount == warning.at("/affectedCount").asInt()) {
				return true;
			}
		}
		return false;
	}

	private void drainOutbox() {
		int guard = 0;
		while (outboxConsumer.consumeNext()) {
			if (++guard > 100) throw new AssertionError("outbox 消费未在保护上限内收敛");
		}
	}

	private User user(String suffix) {
		UUID id = UUID.randomUUID();
		String email = suffix + "-" + id + "@example.test";
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-hash', 1, 'Dashboard', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", id, email, email, now, now, now);
		return new User(id);
	}

	private String bearer(User user) {
		SessionTokenResult session = sessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.id(), "dash-http", "dash-device-" + user.id()));
		return session.accessToken();
	}

	private UUID createAccount(
		String token, String key, String accountClass, String accountType, String name, String currency,
		String openingAmount)
		throws Exception {
		String opening = openingAmount == null ? "null"
			: "{\"amount\":\"%s\",\"businessAt\":\"2026-08-17T16:00:00Z\",\"note\":\"期初录入\"}".formatted(openingAmount);
		MvcResult result = mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"accountClass":"%s","accountType":"%s","name":"%s","currency":"%s","openingBalance":%s}
					""".formatted(accountClass, accountType, name, currency, opening)))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.account.id").isString()).andReturn();
		return UUID.fromString(json(result).at("/data/account/id").asString());
	}

	private UUID category(UUID ownerId) {
		UUID categoryId = UUID.randomUUID();
		java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
		String name = "EXPENSE-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, 'EXPENSE', ?, ?, 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, name, name, now, now);
		return categoryId;
	}

	private void postTransaction(String token, String key, String body) throws Exception {
		mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.type").value("EXPENSE"));
	}

	private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private record User(UUID id) {
	}
}
