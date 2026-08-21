package app.ziji;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.application.AccountStore;
import app.ziji.account.application.LiquidityHoldCommand;
import app.ziji.account.application.LiquidityHoldCursorCodec;
import app.ziji.account.application.LiquidityHoldService;
import app.ziji.account.application.LiquidityHoldStore;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.auth.application.CreateDeviceSessionCommand;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BUG-API-006：真实 SecurityFilterChain 与 PostgreSQL 验收 LiquidityHold 授权、防枚举和幂等前置。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LiquidityHoldHttpIntegrationTests extends PostgresIntegrationTestSupport {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DeviceSessionApplicationService deviceSessionService;

	@Autowired
	private TransactionRunner transactions;

	@Autowired
	private AccountStore accounts;

	@Autowired
	private AccountMembershipReadPort memberships;

	@Autowired
	private LiquidityHoldStore holds;

	@Autowired
	private LiquidityHoldCursorCodec cursors;

	@Autowired
	private UnifiedIdempotencyService idempotency;

	@Autowired
	private Clock clock;

	@Test
	void activeRoleMatrixUsesMembershipFactsAndHidesInactiveOrUnrelatedAccountsBeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("hold-owner");
		UserFixture editor = insertUser("hold-editor");
		UserFixture viewer = insertUser("hold-viewer");
		UserFixture left = insertUser("hold-left");
		UserFixture removed = insertUser("hold-removed");
		UserFixture ended = insertUser("hold-ended");
		UserFixture stranger = insertUser("hold-stranger");
		UserFixture createdByOnly = insertUser("hold-created-by-only");
		AccountFixture account = seedAccount(owner.userId(), "授权矩阵");
		addMembership(account.accountId(), editor.userId(), "EDITOR", "ACTIVE");
		addMembership(account.accountId(), viewer.userId(), "VIEWER", "ACTIVE");
		addMembership(account.accountId(), left.userId(), "VIEWER", "LEFT");
		addMembership(account.accountId(), removed.userId(), "EDITOR", "REMOVED");
		// 历史成员周期在冻结机器基线中必须以非 ACTIVE 状态和 ended_at 表达。
		addMembership(account.accountId(), ended.userId(), "VIEWER", "LEFT");
		jdbc.update("UPDATE accounts SET created_by = ? WHERE id = ?", createdByOnly.userId(), account.accountId());

		assertAllowedAllRoutes(owner, account.accountId(), "owner", seedHold(account.accountId(), owner.userId()),
			seedHold(account.accountId(), owner.userId()));
		assertAllowedAllRoutes(editor, account.accountId(), "editor", seedHold(account.accountId(), owner.userId()),
			seedHold(account.accountId(), owner.userId()));
		assertViewerCanReadButCannotWrite(viewer, account.accountId(), seedHold(account.accountId(), owner.userId()));
		AccountFixture other = seedAccount(owner.userId(), "其他账户");
		assertViewerCannotEnumerateMissingOrForeignHold(viewer, account.accountId(), seedHold(other.accountId(), owner.userId()));
		assertInvisibleAllRoutes(left, account.accountId(), seedHold(account.accountId(), owner.userId()), "left");
		assertInvisibleAllRoutes(removed, account.accountId(), seedHold(account.accountId(), owner.userId()), "removed");
		assertInvisibleAllRoutes(ended, account.accountId(), seedHold(account.accountId(), owner.userId()), "ended");
		assertInvisibleAllRoutes(stranger, account.accountId(), seedHold(account.accountId(), owner.userId()), "stranger");
		assertInvisibleAllRoutes(createdByOnly, account.accountId(), seedHold(account.accountId(), owner.userId()), "created-by");
	}

	@Test
	void holdOutsideUrlAccountReturnsNotFoundBeforeIdempotency() throws Exception {
		UserFixture owner = insertUser("hold-account-mismatch-owner");
		AccountFixture first = seedAccount(owner.userId(), "归属账户一");
		AccountFixture second = seedAccount(owner.userId(), "归属账户二");
		UUID holdId = seedHold(first.accountId(), owner.userId());
		String token = bearer(owner);

		assertNotFoundWithoutIdempotency(post(path(second.accountId()) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(commandJson()), owner.userId(), "mismatch-revise-key-001");
		assertNotFoundWithoutIdempotency(post(path(second.accountId()) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("If-Match", "\"1\""), owner.userId(), "mismatch-release-key-01");
	}

	@Test
	void malformedIfMatchFailuresDoNotWriteIdempotencyRecords() throws Exception {
		UserFixture owner = insertUser("hold-if-match-owner");
		AccountFixture account = seedAccount(owner.userId(), "If-Match");
		String token = bearer(owner);
		List<String> invalidValues = List.of("W/\"1\"", "*", "1", "\"0\"", "\"-1\"", "\"abc\"", "\"2147483648\"");
		for (String value : invalidValues) {
			assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", value).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "invalid-revise-" + UUID.randomUUID());
			assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("If-Match", value), "invalid-release-" + UUID.randomUUID());
		}
		assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "missing-revise-key-001");
		assertInvalidIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token), "missing-release-key-01");
		assertDuplicateIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/revisions", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(commandJson()), "duplicate-revise-key");
		assertDuplicateIfMatch(owner.userId(), post(path(account.accountId()) + "/{holdId}/release", seedHold(account.accountId(), owner.userId()))
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token), "duplicate-release-key");
	}

	@Test
	void staleIfMatchIsPersistedAndReplayedWithItsFirstSafeVersionConflictSummary() throws Exception {
		UserFixture owner = insertUser("hold-stale-replay-owner");
		AccountFixture account = seedAccount(owner.userId(), "冲突重放");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String key = "hold-stale-replay-key-01";
		String body = commandJson();

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"1\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(path(account.accountId())));
		assertEquals(1, idempotencyCount(owner.userId(), key));
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), key));

		Instant changedAt = Instant.now();
		jdbc.update("UPDATE liquidity_holds SET version = 3, expires_at = CAST(? AS timestamptz), updated_at = CAST(? AS timestamptz) WHERE id = ?",
			changedAt.minusSeconds(1).toString(), changedAt.toString(), holdId);
		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"1\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value(path(account.accountId())));
		assertEquals(1, idempotencyCount(owner.userId(), key));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body.replace("10.00", "11.00")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertEquals(1, idempotencyCount(owner.userId(), key));
	}

	@Test
	void archivedAccountRejectsCreateAndReviseButAllowsReleaseOfTheExistingHold() throws Exception {
		UserFixture owner = insertUser("hold-archived-owner");
		AccountFixture account = seedAccount(owner.userId(), "已归档账户");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String body = commandJson();
		Instant archivedAt = Instant.now();
		jdbc.update("UPDATE accounts SET status = 'ARCHIVED', archived_at = CAST(? AS timestamptz) WHERE id = ?",
			archivedAt.toString(), account.accountId());

		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "archived-create-key-01")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "archived-create-key-01")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), "archived-create-key-01"));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "archived-revise-key-01")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "archived-revise-key-01")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), "archived-revise-key-01"));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/release", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", "archived-release-key-1")
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.status").value("RELEASED"));
		assertEquals("RELEASED", jdbc.queryForObject(
			"SELECT end_reason FROM liquidity_holds WHERE id = ?", String.class, holdId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE id = ? AND released_at IS NOT NULL AND ended_at IS NOT NULL",
			Integer.class, holdId));
	}

	@Test
	void expiredRevisionAndReleaseBusinessRulesAreFailedFinalAndSafelyReplayed() throws Exception {
		UserFixture owner = insertUser("hold-expired-business-rule-owner");
		AccountFixture account = seedAccount(owner.userId(), "过期业务规则");
		UUID revisionHoldId = seedHold(account.accountId(), owner.userId());
		UUID releaseHoldId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String body = commandJson();
		Instant expiredAt = Instant.now().minusSeconds(1);
		jdbc.update("UPDATE liquidity_holds SET expires_at = CAST(? AS timestamptz) WHERE id = ?",
			expiredAt.toString(), revisionHoldId);
		jdbc.update("UPDATE liquidity_holds SET expires_at = CAST(? AS timestamptz) WHERE id = ?",
			expiredAt.toString(), releaseHoldId);

		String revisionKey = "expired-revision-key-01";
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", revisionHoldId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("Idempotency-Key", revisionKey)
					.header("If-Match", "\"1\"")
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		}
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), revisionKey));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE id = ? AND ended_at IS NULL", Integer.class, revisionHoldId));

		String releaseKey = "expired-release-key-01";
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post(path(account.accountId()) + "/{holdId}/release", releaseHoldId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("Idempotency-Key", releaseKey)
					.header("If-Match", "\"1\""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(header().doesNotExist(HttpHeaders.ETAG))
				.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		}
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), releaseKey));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE id = ? AND ended_at IS NULL", Integer.class, releaseHoldId));
	}

	@Test
	void successfulRevisionAndReleaseAreSafelyReplayedByTheProductionPostgresPath() throws Exception {
		UserFixture owner = insertUser("hold-success-replay-owner");
		AccountFixture account = seedAccount(owner.userId(), "成功重放");
		UUID revisionHoldId = seedHold(account.accountId(), owner.userId());
		UUID releaseHoldId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String revisionKey = "hold-success-revision-replay-key";
		String revisionBody = commandJson();

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", revisionHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", revisionKey)
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(revisionBody))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		String revisedId = jdbc.queryForObject(
			"SELECT id::text FROM liquidity_holds WHERE previous_revision_id = ?", String.class, revisionHoldId);
		assertEquals("SUCCEEDED", idempotencyStatus(owner.userId(), revisionKey));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE action = 'LIQUIDITY_HOLD_REVISED' AND resource_id = ?",
			Integer.class, UUID.fromString(revisedId)));
		assertEquals(3, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", revisionHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", revisionKey)
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(revisionBody))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(revisedId));
		assertEquals(3, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE action = 'LIQUIDITY_HOLD_REVISED' AND resource_id = ?",
			Integer.class, UUID.fromString(revisedId)));

		String releaseKey = "hold-success-release-replay-key";
		mvc.perform(post(path(account.accountId()) + "/{holdId}/release", releaseHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", releaseKey)
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
		assertEquals("SUCCEEDED", idempotencyStatus(owner.userId(), releaseKey));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE action = 'LIQUIDITY_HOLD_RELEASED' AND resource_id = ?",
			Integer.class, releaseHoldId));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/release", releaseHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", releaseKey)
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andExpect(jsonPath("$.data.id").value(releaseHoldId.toString()));
		assertEquals(3, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE action = 'LIQUIDITY_HOLD_RELEASED' AND resource_id = ?",
			Integer.class, releaseHoldId));
	}

	@Test
	void createPersistsTheTwentyTwoDigitPositiveMoneyBoundaryWithoutNumericDrift() throws Exception {
		UserFixture owner = insertUser("hold-max-money-owner");
		AccountFixture account = seedAccount(owner.userId(), "金额上限");
		String token = bearer(owner);
		String maximum = "9999999999999999999999";
		String key = "hold-max-money-key-0001";

		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(commandJson(maximum, "CNY")))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.amount").value(maximum))
			.andExpect(jsonPath("$.data.currency").value("CNY"));
		BigDecimal persisted = jdbc.queryForObject(
			"SELECT amount FROM liquidity_holds WHERE account_id = ?", BigDecimal.class, account.accountId());
		assertEquals(0, persisted.compareTo(new BigDecimal(maximum)));
		assertEquals(maximum, persisted.stripTrailingZeros().toPlainString());

		int holdsBefore = jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId());
		int auditsBefore = jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE account_id = ?", Integer.class, account.accountId());
		String rejectedKey = "hold-overflow-money-key";
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", rejectedKey).contentType(MediaType.APPLICATION_JSON)
				.content(commandJson("10000000000000000000000", "CNY")))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(holdsBefore, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(auditsBefore, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(0, idempotencyCount(owner.userId(), rejectedKey));
	}

	@Test
	void archivedAccountReplaysTheExistingCreateTerminalBeforeNewBusinessEligibility() throws Exception {
		UserFixture owner = insertUser("hold-archived-replay-owner");
		AccountFixture account = seedAccount(owner.userId(), "归档重放");
		String token = bearer(owner);
		String body = commandJson();
		String key = "hold-archived-replay-key";

		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		UUID holdId = UUID.fromString(jdbc.queryForObject(
			"SELECT id::text FROM liquidity_holds WHERE account_id = ?", String.class, account.accountId()));
		archive(account.accountId());

		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andExpect(jsonPath("$.data.id").value(holdId.toString()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE account_id = ?", Integer.class, account.accountId()));
		assertEquals(1, idempotencyCount(owner.userId(), key));

		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(body.replace("10.00", "11.00")))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		String newKey = "hold-archived-new-key-01";
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", newKey).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), newKey));
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", newKey).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals(1, idempotencyCount(owner.userId(), newKey));

		// 迁移约束要求至少一个 ACTIVE OWNER；保留替代所有者后才撤销当前重放用户。
		UserFixture retainedOwner = insertUser("hold-archived-replay-retained-owner");
		addMembership(account.accountId(), retainedOwner.userId(), "OWNER", "ACTIVE");
		jdbc.update("UPDATE account_members SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP WHERE account_id = ? AND user_id = ?",
			account.accountId(), owner.userId());
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertEquals(1, idempotencyCount(owner.userId(), key));
	}

	@Test
	void archivedAccountReplaysTheFirstVersionConflictWithoutRecheckingMutableEligibility() throws Exception {
		UserFixture owner = insertUser("hold-archived-conflict-owner");
		AccountFixture account = seedAccount(owner.userId(), "归档冲突重放");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String key = "hold-archived-conflict-key";
		String body = commandJson();

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));
		archive(account.accountId());

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(1));
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), key));

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key).header("If-Match", "\"2\"")
				.contentType(MediaType.APPLICATION_JSON).content(body.replace("10.00", "11.00")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void revisionAtomicallySupersedesTheOldFactAndAppendsTheNewFactAndAudit() throws Exception {
		UserFixture owner = insertUser("hold-revision-atomic-owner");
		AccountFixture account = seedAccount(owner.userId(), "修订原子性");
		UUID originalId = seedHold(account.accountId(), owner.userId());

		mvc.perform(post(path(account.accountId()) + "/{holdId}/revisions", originalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer(owner))
				.header("Idempotency-Key", "revision-atomic-key-001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

		assertEquals("SUPERSEDED", jdbc.queryForObject(
			"SELECT end_reason FROM liquidity_holds WHERE id = ?", String.class, originalId));
		assertEquals(2, jdbc.queryForObject(
			"SELECT version FROM liquidity_holds WHERE id = ?", Integer.class, originalId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE id = ? AND ended_at IS NOT NULL", Integer.class, originalId));
		UUID revisedId = UUID.fromString(jdbc.queryForObject(
			"SELECT id::text FROM liquidity_holds WHERE previous_revision_id = ?", String.class, originalId));
		assertNotNull(revisedId);
		assertEquals(2, jdbc.queryForObject(
			"SELECT revision_no FROM liquidity_holds WHERE id = ?", Integer.class, revisedId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT version FROM liquidity_holds WHERE id = ?", Integer.class, revisedId));
		assertEquals(1, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE action = 'LIQUIDITY_HOLD_REVISED' AND resource_id = ?",
			Integer.class, revisedId));
		assertEquals(originalId.toString(), jdbc.queryForObject(
			"SELECT metadata ->> 'previousHoldId' FROM audit_logs WHERE resource_id = ?", String.class, revisedId));
		assertEquals("1", jdbc.queryForObject(
			"SELECT metadata ->> 'expectedVersion' FROM audit_logs WHERE resource_id = ?", String.class, revisedId));
	}

	@Test
	void concurrentRevisionAndReleaseHaveOneWinnerAndPersistTheLosersFirstVersionConflict() throws Exception {
		UserFixture owner = insertUser("hold-lifecycle-race-owner");
		AccountFixture account = seedAccount(owner.userId(), "生命周期竞争");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		String revisionKey = "revision-race-key-0001";
		String releaseKey = "release-race-key-00001";
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<MvcResult> results = new ArrayList<>();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Future<MvcResult>> futures = new ArrayList<>();
		Throwable failure = null;
		try {
			futures = List.of(
				executor.submit(() -> mutateAfter(ready, start, post(path(account.accountId()) + "/{holdId}/revisions", holdId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("Idempotency-Key", revisionKey)
					.header("If-Match", "\"1\"")
					.contentType(MediaType.APPLICATION_JSON).content(commandJson()))),
				executor.submit(() -> mutateAfter(ready, start, post(path(account.accountId()) + "/{holdId}/release", holdId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("Idempotency-Key", releaseKey)
					.header("If-Match", "\"1\""))));
			assertTrue(ready.await(10, TimeUnit.SECONDS), "两个生命周期写入未同时就绪");
			start.countDown();
			for (Future<MvcResult> future : futures) {
				try {
					results.add(future.get(10, TimeUnit.SECONDS));
				} catch (ExecutionException exception) {
					throw new AssertionError("并发生命周期请求失败", exception.getCause());
				}
			}
		} catch (Exception | AssertionError exception) {
			failure = exception;
			throw exception;
		} finally {
			// ready 失败、超时或请求异常也必须释放另一个等待者并终止测试线程。
			start.countDown();
			for (Future<MvcResult> future : futures) {
				if (!future.isDone()) {
					future.cancel(true);
				}
			}
			executor.shutdownNow();
			try {
				if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
					AssertionError termination = new AssertionError("并发测试线程未在超时内终止");
					if (failure != null) {
						failure.addSuppressed(termination);
					} else {
						throw termination;
					}
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				if (failure != null) {
					failure.addSuppressed(exception);
				} else {
					throw exception;
				}
			}
		}

		assertEquals(2, results.size());
		assertEquals(1, results.stream().filter(result -> {
			int status = result.getResponse().getStatus();
			return status == 200 || status == 201;
		}).count());
		assertEquals(1, results.stream().filter(result -> result.getResponse().getStatus() == 409).count());
		MvcResult conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
		assertTrue(conflict.getResponse().getContentAsString().contains("\"code\":\"VERSION_CONFLICT\""));
		assertTrue(conflict.getResponse().getContentAsString().contains("\"currentVersion\":2"));
		String conflictKey = conflict.getRequest().getHeader("Idempotency-Key");
		assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), conflictKey));
		assertEquals(2, jdbc.queryForObject(
			"SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key IN (?, ?)",
			Integer.class, owner.userId(), revisionKey, releaseKey));
		String endReason = jdbc.queryForObject(
			"SELECT end_reason FROM liquidity_holds WHERE id = ?", String.class, holdId);
		assertTrue(List.of("SUPERSEDED", "RELEASED").contains(endReason));
		assertEquals("SUPERSEDED".equals(endReason) ? 1 : 0, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE previous_revision_id = ?", Integer.class, holdId));
	}

	@Test
	void membershipRevocationSerializesBeforeLiquidityHoldCreateAndLeavesNoFactOrIdempotencyRecord() throws Exception {
		UserFixture owner = insertUser("hold-membership-revocation-race");
		AccountFixture account = seedAccount(owner.userId(), "撤权竞态");
		addRetainedOwner(account, "hold-membership-revocation-create-retained-owner");
		String token = bearer(owner);
		String key = "hold-membership-revocation-race-key";
		CountDownLatch revocationLocked = new CountDownLatch(1);
		CountDownLatch releaseRevocation = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicReference<Integer> revocationPid = new AtomicReference<>();
		Future<?> revocation = null;
		Future<MvcResult> request = null;
		try {
			revocation = executor.submit(() -> transactions.required(() -> {
				revocationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				int updated = jdbc.update("""
					UPDATE account_members
					SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP, version = version + 1
					WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
					""", account.accountId(), owner.userId());
				assertEquals(1, updated);
				revocationLocked.countDown();
				awaitLatch(releaseRevocation, "撤权事务释放栅栏超时");
			}));
			assertTrue(revocationLocked.await(10, TimeUnit.SECONDS), "撤权事务未取得 membership 行锁");
			request = executor.submit(() -> mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(commandJson())).andReturn());
			assertTrue(awaitLockWait("account_members", revocationPid.get()), "LiquidityHold 创建未等待 membership 行锁");
			releaseRevocation.countDown();
			MvcResult result = request.get(10, TimeUnit.SECONDS);
			assertEquals(404, result.getResponse().getStatus());
			assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"RESOURCE_NOT_FOUND\""));
			assertEquals(0, jdbc.queryForObject(
				"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
			assertEquals(0, idempotencyCount(owner.userId(), key));
			revocation.get(10, TimeUnit.SECONDS);
		} finally {
			releaseRevocation.countDown();
			if (request != null && !request.isDone()) {
				request.cancel(true);
			}
			if (revocation != null && !revocation.isDone()) {
				revocation.cancel(true);
			}
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "撤权竞态测试线程未在超时内终止");
		}
	}

	@Test
	void listSerializesBeforeMembershipRevocationAndDoesNotReadAfterAccessEnds() throws Exception {
		UserFixture owner = insertUser("hold-membership-revocation-list-race");
		AccountFixture account = seedAccount(owner.userId(), "撤权列表竞态");
		addRetainedOwner(account, "hold-membership-revocation-list-retained-owner");
		seedHold(account.accountId(), owner.userId());
		String token = bearer(owner);
		CountDownLatch revocationLocked = new CountDownLatch(1);
		CountDownLatch releaseRevocation = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicReference<Integer> revocationPid = new AtomicReference<>();
		Future<?> revocation = null;
		Future<MvcResult> request = null;
		try {
			revocation = executor.submit(() -> transactions.required(() -> {
				revocationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				int updated = jdbc.update("""
					UPDATE account_members
					SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP, version = version + 1
					WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
					""", account.accountId(), owner.userId());
				assertEquals(1, updated);
				revocationLocked.countDown();
				awaitLatch(releaseRevocation, "列表撤权事务释放栅栏超时");
			}));
			assertTrue(revocationLocked.await(10, TimeUnit.SECONDS), "列表撤权事务未取得 membership 行锁");
			request = executor.submit(() -> mvc.perform(get(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn());
			assertTrue(awaitLockWait("account_members", revocationPid.get()), "LiquidityHold 列表未等待 membership 行锁");
			releaseRevocation.countDown();
			MvcResult result = request.get(10, TimeUnit.SECONDS);
			assertEquals(404, result.getResponse().getStatus());
			assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"RESOURCE_NOT_FOUND\""));
			revocation.get(10, TimeUnit.SECONDS);
		} finally {
			releaseRevocation.countDown();
			if (request != null && !request.isDone()) {
				request.cancel(true);
			}
			if (revocation != null && !revocation.isDone()) {
				revocation.cancel(true);
			}
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "列表撤权竞态测试线程未在超时内终止");
		}
	}

	@Test
	void replaySerializesBeforeMembershipRevocationAndDoesNotReadAfterAccessEnds() throws Exception {
		UserFixture owner = insertUser("hold-membership-revocation-replay-race");
		AccountFixture account = seedAccount(owner.userId(), "撤权重放竞态");
		addRetainedOwner(account, "hold-membership-revocation-replay-retained-owner");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		LiquidityHoldService service = new LiquidityHoldService(
			accounts, memberships, holds, cursors, entry -> {}, transactions, clock, UUID::randomUUID);
		CountDownLatch revocationLocked = new CountDownLatch(1);
		CountDownLatch releaseRevocation = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicReference<Integer> revocationPid = new AtomicReference<>();
		Future<?> revocation = null;
		Future<LiquidityHold> replay = null;
		try {
			revocation = executor.submit(() -> transactions.required(() -> {
				revocationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				int updated = jdbc.update("""
					UPDATE account_members
					SET status = 'REMOVED', ended_at = CURRENT_TIMESTAMP, version = version + 1
					WHERE account_id = ? AND user_id = ? AND status = 'ACTIVE'
					""", account.accountId(), owner.userId());
				assertEquals(1, updated);
				revocationLocked.countDown();
				awaitLatch(releaseRevocation, "重放撤权事务释放栅栏超时");
			}));
			assertTrue(revocationLocked.await(10, TimeUnit.SECONDS), "重放撤权事务未取得 membership 行锁");
			replay = executor.submit(() -> service.replay(owner.userId(), account.accountId(), holdId, 1));
			assertTrue(awaitLockWait("account_members", revocationPid.get()), "LiquidityHold 重放未等待 membership 行锁");
			releaseRevocation.countDown();
			ExecutionException failure;
			try {
				replay.get(10, TimeUnit.SECONDS);
				throw new AssertionError("撤权后的 LiquidityHold 重放不应成功");
			} catch (ExecutionException exception) {
				failure = exception;
			}
			assertTrue(failure.getCause() instanceof AccountNotVisibleException);
			revocation.get(10, TimeUnit.SECONDS);
		} finally {
			releaseRevocation.countDown();
			if (replay != null && !replay.isDone()) {
				replay.cancel(true);
			}
			if (revocation != null && !revocation.isDone()) {
				revocation.cancel(true);
			}
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "重放撤权竞态测试线程未在超时内终止");
		}
	}

	@Test
	void accountArchivalSerializesBeforeLiquidityHoldCreateAndPersistsOnlyTheStableBusinessFailure() throws Exception {
		UserFixture owner = insertUser("hold-account-archive-race");
		AccountFixture account = seedAccount(owner.userId(), "归档竞态");
		String token = bearer(owner);
		String key = "hold-account-archive-race-key";
		String body = commandJson();
		CountDownLatch archiveLocked = new CountDownLatch(1);
		CountDownLatch releaseArchive = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		AtomicReference<Integer> archivePid = new AtomicReference<>();
		Future<?> archive = null;
		Future<MvcResult> request = null;
		try {
			archive = executor.submit(() -> transactions.required(() -> {
				archivePid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				int updated = jdbc.update("""
					UPDATE accounts
					SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP, version = version + 1
					WHERE id = ? AND status = 'ACTIVE'
					""", account.accountId());
				assertEquals(1, updated);
				archiveLocked.countDown();
				awaitLatch(releaseArchive, "归档事务释放栅栏超时");
			}));
			assertTrue(archiveLocked.await(10, TimeUnit.SECONDS), "归档事务未取得账户行锁");
			request = executor.submit(() -> mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
			assertTrue(awaitLockWait("accounts", archivePid.get()), "LiquidityHold 创建未等待账户行锁");
			releaseArchive.countDown();
			MvcResult result = request.get(10, TimeUnit.SECONDS);
			assertEquals(422, result.getResponse().getStatus());
			assertTrue(result.getResponse().getContentAsString().contains("\"code\":\"BUSINESS_RULE_VIOLATION\""));
			assertEquals("FAILED_FINAL", idempotencyStatus(owner.userId(), key));
			assertEquals(0, jdbc.queryForObject(
				"SELECT count(*) FROM liquidity_holds WHERE account_id = ?", Integer.class, account.accountId()));
			request = null;
			archiveSafeReplay(owner, account, token, key, body);
			archive.get(10, TimeUnit.SECONDS);
		} finally {
			releaseArchive.countDown();
			if (request != null && !request.isDone()) {
				request.cancel(true);
			}
			if (archive != null && !archive.isDone()) {
				archive.cancel(true);
			}
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "归档竞态测试线程未在超时内终止");
		}
	}

	private void archiveSafeReplay(UserFixture owner, AccountFixture account, String token, String key, String body) throws Exception {
		mvc.perform(post(path(account.accountId()))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
		assertEquals(1, idempotencyCount(owner.userId(), key));
	}

	@Test
	void auditFailureRollsBackRevisionFactsAndItsIdempotencyTerminalTogether() {
		UserFixture owner = insertUser("hold-audit-rollback-owner");
		AccountFixture account = seedAccount(owner.userId(), "审计失败回滚");
		UUID holdId = seedHold(account.accountId(), owner.userId());
		String key = "audit-failure-revise-01";
		LiquidityHoldService failingAuditService = new LiquidityHoldService(
			accounts, memberships, holds, cursors, entry -> { throw new IllegalStateException("模拟审计追加失败"); },
			transactions, clock, UUID::randomUUID);
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.RESERVED, new BigDecimal("11.00"), AccountCurrency.CNY, Instant.now(), null, "审计失败");

		assertThrows(IllegalStateException.class, () -> idempotency.executeAuthenticated(
			owner.userId(), 1, "reviseLiquidityHold", key, "b".repeat(64), () -> {
				LiquidityHold revised = failingAuditService.revise(owner.userId(), account.accountId(), holdId, 1, command, "audit-failure-request");
				return IdempotencyWorkResult.completed(revised, IdempotencyResponse.succeededResource(
					201, "LIQUIDITY_HOLD", revised.id(), new IdempotencyResponse.ResourceReference(
						path(account.accountId()) + "/" + holdId + "/revisions", revised.etag(), (long) revised.version())));
			}));

		assertEquals(1, jdbc.queryForObject(
			"SELECT version FROM liquidity_holds WHERE id = ?", Integer.class, holdId));
		assertEquals(0, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE id = ? AND ended_at IS NOT NULL", Integer.class, holdId));
		assertEquals(0, jdbc.queryForObject(
			"SELECT count(*) FROM liquidity_holds WHERE previous_revision_id = ?", Integer.class, holdId));
		assertEquals(0, jdbc.queryForObject(
			"SELECT count(*) FROM audit_logs WHERE resource_id = ?", Integer.class, holdId));
		assertEquals(0, idempotencyCount(owner.userId(), key));
	}

	private void assertAllowedAllRoutes(
		UserFixture user,
		UUID accountId,
		String role,
		UUID revisionHoldId,
		UUID releaseHoldId) throws Exception {
		String token = bearer(user);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray());
		mvc.perform(post(path(accountId))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-create-key-0001")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		mvc.perform(post(path(accountId) + "/{holdId}/revisions", revisionHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-revise-key-0001")
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
		mvc.perform(post(path(accountId) + "/{holdId}/release", releaseHoldId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("Idempotency-Key", role + "-release-key-001")
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
	}

	private void assertViewerCanReadButCannotWrite(UserFixture viewer, UUID accountId, UUID holdId) throws Exception {
		String token = bearer(viewer);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
		assertForbiddenWithoutIdempotency(post(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), viewer.userId(), "viewer-create-key-01");
		assertForbiddenWithoutIdempotency(post(path(accountId) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), viewer.userId(), "viewer-revise-key-01");
		assertForbiddenWithoutIdempotency(post(path(accountId) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\""), viewer.userId(), "viewer-release-key-1");
	}

	private void assertViewerCannotEnumerateMissingOrForeignHold(
		UserFixture viewer,
		UUID accountId,
		UUID foreignHoldId) throws Exception {
		String token = bearer(viewer);
		for (UUID holdId : List.of(UUID.randomUUID(), foreignHoldId)) {
			assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/revisions", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content(commandJson()), viewer.userId(), "viewer-hidden-revise-" + holdId);
			assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/release", holdId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\""),
				viewer.userId(), "viewer-hidden-release-" + holdId);
		}
	}

	private void assertInvisibleAllRoutes(UserFixture user, UUID accountId, UUID holdId, String label) throws Exception {
		String token = bearer(user);
		mvc.perform(get(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertNotFoundWithoutIdempotency(post(path(accountId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), user.userId(), label + "-create-key-001");
		assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/revisions", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\"")
			.contentType(MediaType.APPLICATION_JSON).content(commandJson()), user.userId(), label + "-revise-key-001");
		assertNotFoundWithoutIdempotency(post(path(accountId) + "/{holdId}/release", holdId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("If-Match", "\"1\""), user.userId(), label + "-release-key-001");
	}

	private void assertForbiddenWithoutIdempotency(
		MockHttpServletRequestBuilder request,
		UUID userId,
		String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertNotFoundWithoutIdempotency(
		MockHttpServletRequestBuilder request,
		UUID userId,
		String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertInvalidIfMatch(UUID userId, MockHttpServletRequestBuilder request, String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private void assertDuplicateIfMatch(UUID userId, MockHttpServletRequestBuilder request, String key) throws Exception {
		mvc.perform(request.header("Idempotency-Key", key).header("If-Match", "\"1\"").header("If-Match", "\"1\""))
			.andExpect(status().isBadRequest())
			.andExpect(header().doesNotExist(HttpHeaders.ETAG))
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, idempotencyCount(userId, key));
	}

	private MvcResult mutateAfter(
		CountDownLatch ready,
		CountDownLatch start,
		MockHttpServletRequestBuilder request) throws Exception {
		// 两个旧版本请求同步进入真实 HTTP 链路，让数据库条件更新决定唯一终止转换。
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("并发生命周期起始栅栏超时");
		}
		return mvc.perform(request).andReturn();
	}

	private boolean awaitLockWait(String relation, Integer blockingPid) throws InterruptedException {
		if (relation == null || blockingPid == null) {
			throw new IllegalArgumentException("锁等待探测必须绑定关系和阻塞事务。");
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Integer waiting = jdbc.queryForObject("""
				SELECT count(*)
				FROM pg_stat_activity waiting
				WHERE waiting.datname = current_database()
				  AND waiting.state = 'active'
				  AND waiting.wait_event_type = 'Lock'
				  AND waiting.query ILIKE ?
				  AND ? = ANY(pg_blocking_pids(waiting.pid))
				""", Integer.class, "%FROM " + relation + "%", blockingPid);
			if (waiting != null && waiting > 0) {
				return true;
			}
			// 让出当前线程，避免用固定 sleep 制造锁等待证据；超时仍由 deadline 兜底。
			Thread.yield();
		}
		return false;
	}

	private static void awaitLatch(CountDownLatch latch, String message) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError(message);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(message, exception);
		}
	}

	private int idempotencyCount(UUID userId, String key) {
		return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
			Integer.class, userId, key);
	}

	private String idempotencyStatus(UUID userId, String key) {
		return jdbc.queryForObject("SELECT status FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
			String.class, userId, key);
	}

	private String bearer(UserFixture user) {
		SessionTokenResult session = deviceSessionService.createForAuthenticatedUser(
			new CreateDeviceSessionCommand(user.userId(), "liquidity-http", "liquidity-http-device-" + user.userId()));
		return session.accessToken();
	}

	private UserFixture insertUser(String suffix) {
		UUID userId = UUID.randomUUID();
		String email = "liquidity-http-" + suffix + "-" + userId + "@example.test";
		Instant now = Instant.now();
		jdbc.update("""
			INSERT INTO users
				(id, email, email_normalized, email_verified_at, password_hash, password_hash_version,
				 nickname, timezone, base_currency, locale, amount_format, status, created_at, updated_at, version)
			VALUES (?, ?, ?, CAST(? AS timestamptz), 'test-only-hash', 1, '流动性 HTTP', 'Asia/Shanghai', 'CNY', 'zh-CN',
				'STANDARD', 'ACTIVE', CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", userId, email, email, now.toString(), now.toString(), now.toString());
		return new UserFixture(userId);
	}

	private AccountFixture seedAccount(UUID ownerId, String name) {
		UUID accountId = UUID.randomUUID();
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO accounts
					(id, account_class, account_type, name, institution, currency, note, status,
					 archived_at, created_by, created_at, updated_at, version)
				VALUES (?, 'ASSET', 'BANK', ?, '测试机构', 'CNY', NULL, 'ACTIVE', NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
				""", accountId, name, ownerId, now.toString(), now.toString());
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, membership_no, version)
				VALUES (?, ?, ?, 'OWNER', 'ACTIVE', CAST(? AS timestamptz), 1, 1)
				""", membershipId, accountId, ownerId, now.toString());
			insertCurrentInclusion(membershipId, ownerId, now);
			jdbc.update("""
				INSERT INTO ledger_accounts
					(id, visible_account_id, code, ledger_role, account_nature, currency, status, created_at)
				VALUES (?, ?, ?, 'PRIMARY', 'ASSET', 'CNY', 'ACTIVE', CAST(? AS timestamptz))
				""", UUID.randomUUID(), accountId, "ACCOUNT_" + accountId, now.toString());
		});
		return new AccountFixture(accountId);
	}

	private void addMembership(UUID accountId, UUID userId, String role, String status) {
		UUID membershipId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> {
			jdbc.update("""
				INSERT INTO account_members
					(id, account_id, user_id, role, status, joined_at, ended_at, membership_no, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
				""", membershipId, accountId, userId, role, status,
				java.sql.Timestamp.from(now), "ACTIVE".equals(status) ? null : java.sql.Timestamp.from(now));
			if ("ACTIVE".equals(status)) {
				insertCurrentInclusion(membershipId, userId, now);
			}
		});
	}

	private void addRetainedOwner(AccountFixture account, String emailLabel) {
		// V007 的延迟约束要求撤权事务提交时仍有 ACTIVE OWNER；保留成员不参与请求，仅使竞态夹具合法。
		UserFixture retainedOwner = insertUser(emailLabel);
		addMembership(account.accountId(), retainedOwner.userId(), "OWNER", "ACTIVE");
	}

	private void insertCurrentInclusion(UUID membershipId, UUID userId, Instant now) {
		jdbc.update("""
			INSERT INTO account_inclusion_settings
				(id, membership_id, included, ratio, valid_from, created_by, created_at)
			VALUES (?, ?, TRUE, 1.000000, CAST(? AS timestamptz), ?, CAST(? AS timestamptz))
			""", UUID.randomUUID(), membershipId, now.toString(), userId, now.toString());
	}

	private UUID seedHold(UUID accountId, UUID createdBy) {
		UUID holdId = UUID.randomUUID();
		Instant now = Instant.now();
		transactions.required(() -> jdbc.update("""
			INSERT INTO liquidity_holds
				(id, account_id, hold_type, amount, currency, effective_at, expires_at, released_at, source, note,
				 root_hold_id, previous_revision_id, revision_no, ended_at, end_reason, created_by, created_at, updated_at, version)
			VALUES (?, ?, 'FROZEN', 10.00, 'CNY', CAST(? AS timestamptz), NULL, NULL, 'MANUAL', '测试冻结',
				?, NULL, 1, NULL, NULL, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 1)
			""", holdId, accountId, now.minusSeconds(60).toString(), holdId, createdBy, now.toString(), now.toString()));
		return holdId;
	}

	private void archive(UUID accountId) {
		Instant now = Instant.now();
		jdbc.update("UPDATE accounts SET status = 'ARCHIVED', archived_at = CAST(? AS timestamptz) WHERE id = ?",
			now.toString(), accountId);
	}

	private static String path(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liquidity-holds";
	}

	private static String commandJson() {
		return commandJson("10.00", "CNY");
	}

	private static String commandJson(String amount, String currency) {
		Instant effectiveAt = Instant.now().minusSeconds(30);
		return "{\"type\":\"FROZEN\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\",\"effectiveAt\":\""
			+ effectiveAt + "\",\"expiresAt\":null,\"reason\":\"HTTP 验收\"}";
	}

	private record UserFixture(UUID userId) {}

	private record AccountFixture(UUID accountId) {}
}
