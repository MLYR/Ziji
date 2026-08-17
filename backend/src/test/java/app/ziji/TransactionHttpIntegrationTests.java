package app.ziji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.TransactionRunner;
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

/** 真实 SecurityFilterChain + PostgreSQL 验收 Ledger 读取的分页、ETag 与 404 防枚举。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TransactionHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private DeviceSessionApplicationService deviceSessions;
	@Autowired private TransactionRunner transactions;

	@Test
	void listsWithStableKeysetAndReturnsStrongEtagForVisibleDetail() throws Exception {
		User owner = user("ledger-owner");
		Account account = account(owner.id());
		List<UUID> seeded = List.of(
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z")),
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-15T01:00:00Z")),
			transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-14T01:00:00Z")));
		String token = bearer(owner);

		List<String> actual = new ArrayList<>();
		String cursor = null;
		while (true) {
			var request = get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("accountId", account.id().toString()).param("limit", "1");
			if (cursor != null) request = request.param("cursor", cursor);
			MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
			JsonNode body = json(result);
			actual.add(body.at("/data/0/id").asString());
			if (!body.at("/meta/hasMore").asBoolean()) break;
			cursor = body.at("/meta/nextCursor").asString();
		}
		assertEquals(seeded.stream().map(UUID::toString).toList(), actual);
		assertEquals(3, new HashSet<>(actual).size());

		mvc.perform(get("/api/v1/transactions/{id}", seeded.getFirst())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(seeded.getFirst().toString()))
			.andExpect(jsonPath("$.data.entries[0].ledgerAccountId").exists())
			.andExpect(jsonPath("$.data.internalLedgerAccountId").doesNotExist());
	}

	@Test
	void rejectsInvalidInputAndHidesTransactionsWithoutCurrentMembership() throws Exception {
		User owner = user("ledger-owner-hidden");
		User stranger = user("ledger-stranger");
		Account account = account(owner.id());
		UUID transactionId = transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z"));
		String ownerToken = bearer(owner);
		String strangerToken = bearer(stranger);

		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("dateFrom", "2026-08-17").param("dateTo", "2026-08-16"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("limit", "201")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
				.param("cursor", "invalid")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
				.param("accountId", account.id().toString()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		mvc.perform(get("/api/v1/transactions/{id}", transactionId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void activeRolesCanReadWhileEndedAndNonMembersReceiveTheSame404() throws Exception {
		User owner = user("ledger-roles-owner");
		User editor = user("ledger-roles-editor");
		User viewer = user("ledger-roles-viewer");
		User left = user("ledger-roles-left");
		User removed = user("ledger-roles-removed");
		User ended = user("ledger-roles-ended");
		User creatorOnly = user("ledger-roles-creator");
		User stranger = user("ledger-roles-stranger");
		Account account = account(owner.id());
		membership(account.id(), editor.id(), "EDITOR", "ACTIVE", null);
		membership(account.id(), viewer.id(), "VIEWER", "ACTIVE", null);
		membership(account.id(), left.id(), "VIEWER", "LEFT", Instant.now().minus(1, ChronoUnit.DAYS));
		membership(account.id(), removed.id(), "EDITOR", "REMOVED", Instant.now().minus(1, ChronoUnit.DAYS));
		membership(account.id(), ended.id(), "VIEWER", "LEFT", Instant.now().minus(1, ChronoUnit.DAYS));
		UUID transactionId = transaction(owner.id(), account.ledgerId(), Instant.parse("2026-08-16T01:00:00Z"));
		jdbc.update("UPDATE transactions SET created_by = ?, updated_by = ? WHERE id = ?", creatorOnly.id(), creatorOnly.id(), transactionId);

		for (User visible : List.of(owner, editor, viewer)) {
			String token = bearer(visible);
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(transactionId.toString()));
			mvc.perform(get("/api/v1/transactions/{id}", transactionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		}
		for (User invisible : List.of(left, removed, ended, creatorOnly, stranger)) {
			String token = bearer(invisible);
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param("accountId", account.id().toString()))
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("transactions"))));
			mvc.perform(get("/api/v1/transactions/{id}", transactionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		}
	}

	@Test
	void filtersLimitsSameDayOrderAndCursorBindingsAreValidated() throws Exception {
		User owner = user("ledger-filter-owner");
		User other = user("ledger-filter-other");
		Account first = account(owner.id());
		Account second = account(owner.id());
		Account otherAccount = account(other.id());
		UUID categoryId = category(owner.id());
		UUID sameDayLower = UUID.fromString("00000000-0000-0000-0000-000000000011");
		UUID sameDayHigher = UUID.fromString("00000000-0000-0000-0000-000000000099");
		transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-15T01:00:00Z"), sameDayLower, "EXPENSE", categoryId);
		transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-15T02:00:00Z"), sameDayHigher, "EXPENSE", categoryId);
		UUID income = transaction(owner.id(), first.ledgerId(), Instant.parse("2026-08-14T01:00:00Z"), UUID.randomUUID(), "INCOME", null);
		transaction(owner.id(), second.ledgerId(), Instant.parse("2026-08-13T01:00:00Z"));
		transaction(other.id(), otherAccount.ledgerId(), Instant.parse("2026-08-12T01:00:00Z"));
		String token = bearer(owner);

		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("accountId", first.id().toString()).param("type", "EXPENSE")
				.param("dateFrom", "2026-08-15").param("dateTo", "2026-08-15").param("categoryId", categoryId.toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].id").value(sameDayHigher.toString()))
			.andExpect(jsonPath("$.data[1].id").value(sameDayLower.toString()));
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("type", "INCOME").param("dateFrom", "2026-08-14").param("dateTo", "2026-08-14"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(income.toString()));
		for (String limit : List.of("1", "200")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", limit))
				.andExpect(status().isOk());
		}
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4));
		for (String limit : List.of("0", "201", "abc", "999999999999999999999")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", limit))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		for (String parameter : List.of("accountId", "categoryId", "dateFrom", "type")) {
			mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.param(parameter, "invalid")).andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1").param("limit", "2")).andExpect(status().isBadRequest());

		MvcResult firstPage = mvc.perform(get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.param("limit", "1")).andExpect(status().isOk()).andReturn();
		String cursor = json(firstPage).at("/meta/nextCursor").asString();
		assertTrue(!cursor.isBlank());
		for (var request : List.of(
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("type", "INCOME").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("accountId", second.id().toString()).param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("dateFrom", "2026-08-14").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1").param("categoryId", categoryId.toString()).param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(other)).param("limit", "1").param("cursor", cursor),
			get("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "1")
				.param("cursor", cursor.substring(0, cursor.length() - 1) + (cursor.endsWith("A") ? "B" : "A")))) {
			mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
	}

	private User user(String suffix) {
		UUID id = UUID.randomUUID();
		String email = suffix + "-" + id + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users (id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
			 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, ?, 'test-hash', 1, 'Ledger', 'Asia/Shanghai', 'CNY', 'zh-CN', 'STANDARD', 'ACTIVE', ?, ?, 1)
			""", id, email, email, ts(now), ts(now), ts(now));
		return new User(id);
	}

	private Account account(UUID ownerId) {
		UUID accountId = UUID.randomUUID();
		UUID ledgerId = UUID.randomUUID();
		Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts (id, account_class, account_type, name, currency, status, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', 'Ledger account', 'CNY', 'ACTIVE', ?, ?, ?, 1)
				""", accountId, ownerId, ts(now), ts(now));
			UUID membershipId = UUID.randomUUID();
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', ?, 1, 1)
				""", membershipId, accountId, ownerId, ts(now));
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(now), ownerId, ts(now));
			jdbc.update("""
				INSERT INTO ledger_accounts (id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', ?)
				""", ledgerId, accountId, "PRIMARY_" + accountId, ts(now));
		});
		return new Account(accountId, ledgerId);
	}

	private void membership(UUID accountId, UUID userId, String role, String status, Instant endedAt) {
		UUID membershipId = UUID.randomUUID();
		Instant joinedAt = Instant.now().minus(2, ChronoUnit.DAYS);
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members (id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status, ts(joinedAt), endedAt == null ? null : ts(endedAt));
			jdbc.update("""
				INSERT INTO account_inclusion_settings (id, membership_id, included, ratio, valid_from, created_by, created_at)
				VALUES (?, ?, TRUE, 1.000000, ?, ?, ?)
				""", UUID.randomUUID(), membershipId, ts(joinedAt), userId, ts(joinedAt));
		});
	}

	private UUID category(UUID ownerId) {
		UUID categoryId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO categories (id, owner_user_id, category_type, name, name_normalized, status, created_at, updated_at, version)
			VALUES (?, ?, 'EXPENSE', '筛选分类', '筛选分类', 'ACTIVE', ?, ?, 1)
			""", categoryId, ownerId, ts(now), ts(now));
		return categoryId;
	}

	private UUID transaction(UUID ownerId, UUID visibleLedgerId, Instant businessAt) {
		return transaction(ownerId, visibleLedgerId, businessAt, UUID.randomUUID(), "EXPENSE", null);
	}

	private UUID transaction(UUID ownerId, UUID visibleLedgerId, Instant businessAt, UUID transactionId, String type, UUID categoryId) {
		UUID systemLedgerId = UUID.randomUUID();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO ledger_accounts (id, owner_user_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'SYSTEM', 'EXPENSE', 'CNY', 'ACTIVE', ?)
				""", systemLedgerId, ownerId, "EXPENSE_" + systemLedgerId, ts(businessAt));
			jdbc.update("""
				INSERT INTO transactions (id, transaction_type, status, business_at, business_date, timezone, source,
				 root_transaction_id, version_no, created_by, updated_by, created_at, updated_at)
				VALUES (?, ?, 'DRAFT', ?, ?, 'UTC', 'MANUAL', ?, 1, ?, ?, ?, ?)
				""", transactionId, type, ts(businessAt), java.sql.Date.valueOf(businessAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()),
				transactionId, ownerId, ownerId, ts(businessAt), ts(businessAt));
			for (Object[] entry : List.of(new Object[] {systemLedgerId, "D"}, new Object[] {visibleLedgerId, "C"})) {
				jdbc.update("""
					INSERT INTO ledger_entries (id, transaction_id, ledger_account_id, sequence_no, direction, amount, currency, business_date, created_at)
					VALUES (?, ?, ?, ?, ?, 10.00, 'CNY', ?, ?)
					""", UUID.randomUUID(), transactionId, entry[0], "D".equals(entry[1]) ? 1 : 2, entry[1],
					java.sql.Date.valueOf(businessAt.atZone(java.time.ZoneOffset.UTC).toLocalDate()), ts(businessAt));
			}
			jdbc.update("UPDATE transactions SET status = 'POSTED', posted_at = ?, updated_at = ? WHERE id = ?",
				ts(businessAt), ts(businessAt), transactionId);
			if (categoryId != null) {
				jdbc.update("INSERT INTO transaction_categories (transaction_id, category_id, role) VALUES (?, ?, 'PRIMARY')",
					transactionId, categoryId);
			}
		});
		return transactionId;
	}

	private String bearer(User user) {
		SessionTokenResult session = deviceSessions.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.id(), "ledger-http", "ledger-http-device"));
		return session.accessToken();
	}

	private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
	private java.sql.Timestamp ts(Instant value) { return java.sql.Timestamp.from(value); }
	private record User(UUID id) {}
	private record Account(UUID id, UUID ledgerId) {}
}
