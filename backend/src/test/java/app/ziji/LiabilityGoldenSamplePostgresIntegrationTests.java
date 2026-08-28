package app.ziji;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * QA-LIA-001 / T-LIA-002～005：真实 HTTP 与 PostgreSQL 下的负债账务金标准。
 * 信用卡消费、借款到账、本金+利息+手续费还款全部经公共语义命令提交，
 * 客户端不接触内部科目或分录；断言精确分录、余额、收支边界与净资产双口径，
 * 并覆盖利率/账单日范围与利息分类必填的失败边界。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LiabilityGoldenSamplePostgresIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private DeviceSessionApplicationService deviceSessions;

	@Test
	void liabilityGoldenSampleKeepsExactEntriesBalancesAndExpenseBoundariesThroughPublicApi() throws Exception {
		User owner = user("lia-golden-owner");
		String token = bearer(owner);
		UUID bank = createAccount(token, "lia-golden-asset-0001", "ASSET", "BANK", "工资卡", "20000.00");
		UUID card = createAccount(token, "lia-golden-card-0001", "LIABILITY", "CREDIT_CARD", "信用卡", "2000.00");
		UUID loan = createAccount(token, "lia-golden-loan-0001", "LIABILITY", "LOAN", "消费贷款", null);
		UUID expenseCategory = category(owner.id(), "EXPENSE");
		UUID feeCategory = category(owner.id(), "EXPENSE");

		// T-LIA-002 信用卡消费 300：借费用/分类科目、贷信用卡 PRIMARY；负债 +300、支出 +300。
		UUID cardExpense = postTransaction(token, "lia-golden-card-spend-01", """
			{"type":"EXPENSE","businessAt":"2026-08-18T02:00:00Z","timezone":"Asia/Shanghai",
			 "accountId":"%s","amount":"300.00","currency":"CNY","categoryId":"%s","merchant":"商户"}
			""".formatted(card, expenseCategory), "EXPENSE");
		assertEntry(cardExpense, 1, categoryLedger(owner.id(), expenseCategory), "D", "300.00");
		assertEntry(cardExpense, 2, primaryLedger(card), "C", "300.00");
		assertEquals(1, count("""
			SELECT count(*) FROM transaction_categories
			WHERE transaction_id = ? AND category_id = ? AND role = 'PRIMARY'
			""", cardExpense, expenseCategory));
		assertEquals(0, primaryBalance(card).compareTo(new BigDecimal("-2300.00")));
		assertEquals(0, expenseTotal(cardExpense).compareTo(new BigDecimal("300.00")));
		assertEquals(0, incomeTotal(cardExpense).compareTo(BigDecimal.ZERO));

		// T-LIA-003 借款到账 10,000：借资产 PRIMARY、贷负债 PRIMARY，内部 TRANSFER，不计收入。
		UUID borrowing = postTransaction(token, "lia-golden-borrow-0001", """
			{"type":"LIABILITY_BORROWING","businessAt":"2026-08-18T03:00:00Z","timezone":"Asia/Shanghai",
			 "assetAccountId":"%s","liabilityAccountId":"%s","currency":"CNY","amount":"10000.00"}
			""".formatted(bank, loan), "TRANSFER");
		assertEquals("TRANSFER", transactionType(borrowing));
		assertEntry(borrowing, 1, primaryLedger(bank), "D", "10000.00");
		assertEntry(borrowing, 2, primaryLedger(loan), "C", "10000.00");
		Map<String, Object> transfer = jdbc.queryForMap("""
			SELECT from_account_id, to_account_id, from_amount, to_amount, fee_amount
			FROM transfer_details WHERE transaction_id = ?
			""", borrowing);
		assertEquals(loan, transfer.get("from_account_id"));
		assertEquals(bank, transfer.get("to_account_id"));
		assertEquals(0, new BigDecimal("10000.00").compareTo((BigDecimal) transfer.get("from_amount")));
		assertEquals(0, new BigDecimal("10000.00").compareTo((BigDecimal) transfer.get("to_amount")));
		assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) transfer.get("fee_amount")));
		assertEquals(0, primaryBalance(bank).compareTo(new BigDecimal("30000.00")));
		assertEquals(0, primaryBalance(loan).compareTo(new BigDecimal("-10000.00")));
		assertEquals(0, expenseTotal(borrowing).compareTo(BigDecimal.ZERO));
		assertEquals(0, incomeTotal(borrowing).compareTo(BigDecimal.ZERO));

		// T-LIA-004 还款本金 1,000 + 利息 50 + 手续费 10：本金不计支出，利息/手续费计入。
		UUID repayment = postTransaction(token, "lia-golden-repay-0001", """
			{"type":"LIABILITY_REPAYMENT","businessAt":"2026-08-18T04:00:00Z","timezone":"Asia/Shanghai",
			 "cashAccountId":"%s","liabilityAccountId":"%s","currency":"CNY",
			 "principalAmount":"1000.00","interestAmount":"50.00","feeAmount":"10.00",
			 "interestCategoryId":"%s","feeCategoryId":"%s"}
			""".formatted(bank, loan, expenseCategory, feeCategory), "REPAYMENT");
		assertEquals("REPAYMENT", transactionType(repayment));
		assertEntry(repayment, 1, primaryLedger(loan), "D", "1000.00");
		assertEntry(repayment, 2, primaryLedger(bank), "C", "1000.00");
		assertEntry(repayment, 3, categoryLedger(owner.id(), expenseCategory), "D", "50.00");
		assertEntry(repayment, 4, primaryLedger(bank), "C", "50.00");
		assertEntry(repayment, 5, categoryLedger(owner.id(), feeCategory), "D", "10.00");
		assertEntry(repayment, 6, primaryLedger(bank), "C", "10.00");
		Map<String, Object> repaymentDetails = jdbc.queryForMap("""
			SELECT liability_account_id, cash_account_id, principal_amount, interest_amount, fee_amount
			FROM repayment_details WHERE transaction_id = ?
			""", repayment);
		assertEquals(loan, repaymentDetails.get("liability_account_id"));
		assertEquals(bank, repaymentDetails.get("cash_account_id"));
		assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) repaymentDetails.get("principal_amount")));
		assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) repaymentDetails.get("interest_amount")));
		assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) repaymentDetails.get("fee_amount")));
		// 资产 -1,060、负债本金归 -1,000、支出只含利息与手续费 60。
		assertEquals(0, primaryBalance(bank).compareTo(new BigDecimal("28940.00")));
		assertEquals(0, primaryBalance(loan).compareTo(new BigDecimal("-9000.00")));
		assertEquals(0, primaryBalance(card).compareTo(new BigDecimal("-2300.00")));
		assertEquals(0, expenseTotal(repayment).compareTo(new BigDecimal("60.00")));
		assertEquals(0, incomeTotal(repayment).compareTo(BigDecimal.ZERO));
		assertEquals(0, userNatureTotal(owner.id(), "EXPENSE", "D").compareTo(new BigDecimal("360.00")));
		assertEquals(0, userNatureTotal(owner.id(), "INCOME", "C").compareTo(BigDecimal.ZERO));

		// 每笔已入账交易（含两笔期初）在 CNY 内借贷平衡。
		assertEquals(0, count("""
			SELECT count(*) FROM (
				SELECT e.transaction_id
				FROM ledger_entries e
				JOIN transactions t ON t.id = e.transaction_id
				WHERE t.created_by = ? AND t.status = 'POSTED'
				GROUP BY e.transaction_id
				HAVING SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END) <> 0
			) unbalanced
			""", owner.id()));
		assertEquals(5, count("SELECT count(*) FROM transactions WHERE created_by = ?", owner.id()));

		// 净资产 17,640 与独立口径（期初权益净额 18,000 + 收入 0 - 支出 360）一致。
		// userNatureTotal 已按方向净额聚合：EQUITY「C」= 贷 - 借，EXPENSE「D」= 借 - 贷。
		BigDecimal netWorth = userNatureTotal(owner.id(), "ASSET", "D")
			.add(userNatureTotal(owner.id(), "LIABILITY", "D"));
		BigDecimal independent = userNatureTotal(owner.id(), "EQUITY", "C")
			.add(userNatureTotal(owner.id(), "INCOME", "C"))
			.subtract(userNatureTotal(owner.id(), "EXPENSE", "D"));
		assertEquals(0, netWorth.compareTo(new BigDecimal("17640.00")));
		assertEquals(0, independent.compareTo(new BigDecimal("17640.00")));
		assertEquals(0, netWorth.compareTo(independent));
	}

	@Test
	void liabilityDetailRangesAndRepaymentCategoryRulesFailClosedWithoutIdempotency() throws Exception {
		User owner = user("lia-golden-rules-owner");
		String token = bearer(owner);
		UUID bank = createAccount(token, "lia-golden-rules-asset-1", "ASSET", "BANK", "付款卡", null);
		UUID loan = createAccount(token, "lia-golden-rules-loan-1", "LIABILITY", "LOAN", "借款", null);
		UUID card = createAccount(token, "lia-golden-rules-card-1", "LIABILITY", "CREDIT_CARD", "规则卡", null);
		UUID expenseCategory = category(owner.id(), "EXPENSE");

		// T-LIA-005 利率与账单/还款日范围错误在幂等占用前拒绝。
		assertDetailFailure(token, owner.id(), card, "lia-golden-rate-negative-1",
			creditCardJson("-0.01", 8), 400);
		assertDetailFailure(token, owner.id(), card, "lia-golden-rate-over-0001",
			creditCardJson("1.01", 8), 400);
		assertDetailFailure(token, owner.id(), card, "lia-golden-day-zero-001",
			creditCardJson("0.05", 0), 400);
		assertDetailFailure(token, owner.id(), card, "lia-golden-day-32-0001",
			creditCardJson("0.05", 32), 400);

		// 合法边界值按原样保存（短月取月末属于提醒语义，不改变账务日期）。
		mvc.perform(put(path(card)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-None-Match", "*").header("Idempotency-Key", "lia-golden-day-31-0001")
				.contentType(MediaType.APPLICATION_JSON).content(creditCardJson("0.05", 31)))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		mvc.perform(get(path(card)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.billingDay").value(31))
			.andExpect(jsonPath("$.data.repaymentDay").value(20));

		// 利息大于 0 时利息分类必填；缺失返回 422 且不产生事实或幂等记录。
		int transactionsBefore = count("SELECT count(*) FROM transactions WHERE created_by = ?", owner.id());
		assertRepaymentFailure(token, owner.id(), bank, loan, expenseCategory, "lia-golden-missing-cat-1");
		assertEquals(transactionsBefore, count("SELECT count(*) FROM transactions WHERE created_by = ?", owner.id()));
	}

	private void assertDetailFailure(
		String token, UUID userId, UUID accountId, String key, String body, int statusCode) throws Exception {
		mvc.perform(put(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-None-Match", "*").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().is(statusCode)).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertRepaymentFailure(
		String token, UUID userId, UUID cashAccountId, UUID liabilityAccountId,
		UUID expenseCategory, String key) throws Exception {
		mvc.perform(post("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"type":"LIABILITY_REPAYMENT","businessAt":"2026-08-18T05:00:00Z","timezone":"Asia/Shanghai",
					 "cashAccountId":"%s","liabilityAccountId":"%s","currency":"CNY",
					 "principalAmount":"100.00","interestAmount":"50.00","feeAmount":"0.00",
					 "interestCategoryId":null,"feeCategoryId":"%s"}
					""".formatted(cashAccountId, liabilityAccountId, expenseCategory)))
			.andExpect(status().isUnprocessableEntity()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private User user(String suffix) {
		UUID id = UUID.randomUUID();
		String email = suffix + "-" + id + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-hash', 1, '负债金标准', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", id, email, email, ts(now), ts(now), ts(now));
		return new User(id);
	}

	private String bearer(User user) {
		SessionTokenResult session = deviceSessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.id(), "lia-golden-http", "lia-golden-device"));
		return session.accessToken();
	}

	/** 经公共 createAccount 提交期初余额；amount 为 null 时不创建期初交易。 */
	private UUID createAccount(
		String token, String key, String accountClass, String accountType, String name, String openingAmount)
		throws Exception {
		String opening = openingAmount == null ? "null"
			: "{\"amount\":\"%s\",\"businessAt\":\"2026-08-17T16:00:00Z\",\"note\":\"期初录入\"}".formatted(openingAmount);
		MvcResult result = mvc.perform(post("/api/v1/accounts")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("""
					{"accountClass":"%s","accountType":"%s","name":"%s","currency":"CNY","openingBalance":%s}
					""".formatted(accountClass, accountType, name, opening)))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.account.id").isString()).andReturn();
		return UUID.fromString(json(result).at("/data/account/id").asString());
	}

	private UUID category(UUID ownerId, String categoryType) {
		UUID categoryId = UUID.randomUUID();
		Instant now = Instant.now();
		String name = categoryType + "-" + categoryId;
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, categoryType, name, name, ts(now), ts(now));
		return categoryId;
	}

	private UUID postTransaction(String token, String key, String body, String expectedType) throws Exception {
		MvcResult result = mvc.perform(post("/api/v1/transactions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.type").value(expectedType)).andReturn();
		return UUID.fromString(json(result).at("/data/id").asString());
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private void assertEntry(UUID transactionId, int sequenceNo, UUID ledgerId, String direction, String amount) {
		Map<String, Object> entry = jdbc.queryForMap("""
			SELECT ledger_account_id, direction, amount
			FROM ledger_entries
			WHERE transaction_id = ? AND sequence_no = ?
			""", transactionId, sequenceNo);
		assertEquals(ledgerId, entry.get("ledger_account_id"));
		assertEquals(direction, entry.get("direction"));
		assertEquals(0, new BigDecimal(amount).compareTo((BigDecimal) entry.get("amount")));
	}

	private UUID primaryLedger(UUID accountId) {
		return jdbc.queryForObject("""
			SELECT id FROM ledger_accounts
			WHERE visible_account_id = ? AND ledger_role = 'PRIMARY'
			""", UUID.class, accountId);
	}

	private UUID categoryLedger(UUID userId, UUID categoryId) {
		return jdbc.queryForObject("""
			SELECT id FROM ledger_accounts
			WHERE owner_user_id = ? AND code = ? AND currency = 'CNY' AND ledger_role = 'SYSTEM'
			""", UUID.class, userId, "EXPENSE_CATEGORY_" + categoryId);
	}

	private String transactionType(UUID transactionId) {
		return jdbc.queryForObject(
			"SELECT transaction_type FROM transactions WHERE id = ?", String.class, transactionId);
	}

	/** 资产/负债按「借-贷」符号返回；负债余额为负数表示正债务。 */
	private BigDecimal primaryBalance(UUID accountId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = 'D' THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE la.visible_account_id = ? AND la.ledger_role = 'PRIMARY'
			""", BigDecimal.class, accountId);
	}

	private BigDecimal expenseTotal(UUID transactionId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(e.amount), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND e.direction = 'D' AND la.account_nature = 'EXPENSE'
			""", BigDecimal.class, transactionId);
	}

	private BigDecimal incomeTotal(UUID transactionId) {
		return jdbc.queryForObject("""
			SELECT COALESCE(SUM(e.amount), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			WHERE e.transaction_id = ? AND e.direction = 'C' AND la.account_nature = 'INCOME'
			""", BigDecimal.class, transactionId);
	}

	/** 按科目性质与方向聚合该用户全部已入账分录。 */
	private BigDecimal userNatureTotal(UUID userId, String nature, String direction) {
		BigDecimal total = jdbc.queryForObject("""
			SELECT COALESCE(SUM(CASE WHEN e.direction = ? THEN e.amount ELSE -e.amount END), 0)
			FROM ledger_entries e
			JOIN ledger_accounts la ON la.id = e.ledger_account_id
			JOIN transactions t ON t.id = e.transaction_id
			WHERE t.created_by = ? AND t.status = 'POSTED' AND la.account_nature = ?
			""", BigDecimal.class, direction, userId, nature);
		return total == null ? BigDecimal.ZERO : total;
	}

	private int count(String sql, Object... arguments) {
		Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
		return count == null ? 0 : count;
	}

	private int idempotencyCount(UUID userId, String key) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
			Integer.class, userId, key);
	}

	private java.sql.Timestamp ts(Instant value) {
		return java.sql.Timestamp.from(value);
	}

	private static String path(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liability-details";
	}

	private static String creditCardJson(String rate, int billingDay) {
		return "{\"interestRate\":\"" + rate + "\",\"loanDate\":null,\"dueDate\":null,\"billingDay\":" + billingDay
			+ ",\"repaymentDay\":20,\"currentAmountDue\":\"100.00\"}";
	}

	private record User(UUID id) {
	}
}
