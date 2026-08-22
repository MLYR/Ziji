package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldType;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-API-006：客户端币种必须在 application 层与账户事实比较，不能直写持久化。 */
class LiquidityHoldServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
	private static final UUID EDITOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
	private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000904");
	private static final UUID LEFT_ID = UUID.fromString("00000000-0000-0000-0000-000000000905");
	private static final UUID REMOVED_ID = UUID.fromString("00000000-0000-0000-0000-000000000906");
	private static final UUID ENDED_ID = UUID.fromString("00000000-0000-0000-0000-000000000907");
	private static final UUID STRANGER_ID = UUID.fromString("00000000-0000-0000-0000-000000000908");
	private static final UUID CREATED_BY_ONLY_ID = UUID.fromString("00000000-0000-0000-0000-000000000909");
	private static final Instant NOW = Instant.parse("2026-08-15T01:02:03Z");

	@Test
	void createRejectsCurrencyThatDiffersFromAccountBeforeFactsOrAudit() {
		List<LiquidityHold> inserted = new ArrayList<>();
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldService service = service(inserted, audits);
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.FROZEN, new BigDecimal("10.00"), AccountCurrency.USD,
			NOW, null, "币种不匹配");

		assertThrows(LiquidityHoldException.BusinessRule.class,
			() -> service.create(USER_ID, ACCOUNT_ID, command, "request-901"));

		assertTrue(inserted.isEmpty());
		assertTrue(audits.isEmpty());
	}

	@Test
	void replayVersionDriftFailsClosedInsteadOfReturningWriteConflict() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold current = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID, NOW, NOW, 2);
		LiquidityHoldService service = service(new ArrayList<>(), new ArrayList<>(), Optional.of(current));

		assertThrows(LiquidityHoldException.SafeReplayUnavailable.class,
			() -> service.replay(USER_ID, ACCOUNT_ID, current.id(), 1));
	}

	@Test
	void replayLogicalExpiryDriftFailsClosedWithoutVersionChange() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold current = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(20), NOW.minusSeconds(1), null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID,
			NOW.minusSeconds(10), NOW.minusSeconds(10), 1);
		LiquidityHoldService service = service(new ArrayList<>(), new ArrayList<>(), Optional.of(current));

		assertThrows(LiquidityHoldException.SafeReplayUnavailable.class,
			() -> service.replay(USER_ID, ACCOUNT_ID, current.id(), 1));
	}

	@Test
	void futureEffectiveAtReplayFailsClosedAfterLogicalStatusChangesWithoutSecondFactOrAudit() {
		MutableClock clock = new MutableClock(NOW);
		List<LiquidityHold> inserted = new ArrayList<>();
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldService service = service(inserted, audits, Optional.empty(), clock);
		MemoryIdempotencyStore records = new MemoryIdempotencyStore();
		UnifiedIdempotencyService idempotency = idempotency(records, clock);
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.FROZEN, new BigDecimal("10.00"), AccountCurrency.CNY,
			NOW.plusSeconds(60), null, "未来生效");
		String key = "future-effective-replay-001";
		int[] workCalls = {0};

		IdempotencyExecution<LiquidityHold> first = idempotency.executeAuthenticated(
			USER_ID, 1, "createLiquidityHold", key, "a".repeat(64), () -> {
				workCalls[0]++;
				LiquidityHold created = service.create(USER_ID, ACCOUNT_ID, command, "request-902");
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "LIQUIDITY_HOLD", created.id(),
					new IdempotencyResponse.ResourceReference("/api/v1/accounts/" + ACCOUNT_ID + "/liquidity-holds",
						created.etag(), (long) created.version())));
			});

		assertEquals(IdempotencyExecution.Status.EXECUTED, first.status());
		assertEquals(app.ziji.account.domain.LiquidityHoldStatus.PENDING, first.value().statusAt(clock.instant()));
		clock.set(NOW.plusSeconds(61));
		IdempotencyExecution<LiquidityHold> replay = idempotency.executeAuthenticated(
			USER_ID, 1, "createLiquidityHold", key, "a".repeat(64), () -> {
				workCalls[0]++;
				throw new AssertionError("安全重放不得重新执行业务写入。");
			});

		assertEquals(IdempotencyExecution.Status.REPLAYED, replay.status());
		assertThrows(LiquidityHoldException.SafeReplayUnavailable.class,
			() -> service.replay(USER_ID, ACCOUNT_ID, first.value().id(), first.value().version()));
		assertEquals(1, workCalls[0]);
		assertEquals(1, inserted.size());
		assertEquals(1, audits.size());
		assertEquals(1, records.completed.size());
	}

	@Test
	void activeMembershipRoleMatrixDoesNotUseAccountCreatorAsAnAuthorizationShortcut() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold hold = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID, NOW, NOW, 1);
		Map<UUID, String> activeRoles = Map.of(USER_ID, "OWNER", EDITOR_ID, "EDITOR", VIEWER_ID, "VIEWER");
		LiquidityHoldService service = service(new ArrayList<>(), new ArrayList<>(), Optional.of(hold),
			Clock.fixed(NOW, ZoneOffset.UTC), activeRoles, CREATED_BY_ONLY_ID);

		assertDoesNotThrow(() -> service.list(USER_ID, ACCOUNT_ID, null, null));
		assertDoesNotThrow(() -> service.list(EDITOR_ID, ACCOUNT_ID, null, null));
		assertDoesNotThrow(() -> service.list(VIEWER_ID, ACCOUNT_ID, null, null));
		assertDoesNotThrow(() -> service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 1));
		assertDoesNotThrow(() -> service.preflightMutation(EDITOR_ID, ACCOUNT_ID, holdId, 1));
		assertDoesNotThrow(() -> service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 2));
		assertThrows(AccountPermissionDeniedException.class,
			() -> service.preflightMutation(VIEWER_ID, ACCOUNT_ID, holdId, 1));
		for (UUID visibleUserId : List.of(USER_ID, EDITOR_ID, VIEWER_ID)) {
			assertThrows(AccountNotVisibleException.class,
				() -> service.preflightMutation(visibleUserId, ACCOUNT_ID, UUID.randomUUID(), 1));
		}
		for (UUID invisibleUserId : List.of(LEFT_ID, REMOVED_ID, ENDED_ID, STRANGER_ID, CREATED_BY_ONLY_ID)) {
			assertThrows(AccountNotVisibleException.class, () -> service.list(invisibleUserId, ACCOUNT_ID, null, null));
			assertThrows(AccountNotVisibleException.class,
				() -> service.preflightMutation(invisibleUserId, ACCOUNT_ID, holdId, 1));
		}
	}

	@Test
	void mutationChecksHoldVisibilityBeforeViewerPermissionInPreflightAndTransaction() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold hold = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID, NOW, NOW, 1);
		LiquidityHoldService service = service(new ArrayList<>(), new ArrayList<>(), Optional.of(hold),
			Clock.fixed(NOW, ZoneOffset.UTC), Map.of(USER_ID, "OWNER", VIEWER_ID, "VIEWER"), USER_ID);
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.RESERVED, new BigDecimal("11.00"), AccountCurrency.CNY, NOW, null, "修订");

		assertThrows(AccountNotVisibleException.class,
			() -> service.preflightMutation(VIEWER_ID, ACCOUNT_ID, UUID.randomUUID(), 1));
		assertThrows(AccountPermissionDeniedException.class,
			() -> service.preflightMutation(VIEWER_ID, ACCOUNT_ID, holdId, 1));
		assertThrows(AccountNotVisibleException.class,
			() -> service.revise(VIEWER_ID, ACCOUNT_ID, UUID.randomUUID(), 1, command, "request-missing"));
		assertThrows(AccountPermissionDeniedException.class,
			() -> service.revise(VIEWER_ID, ACCOUNT_ID, holdId, 1, command, "request-viewer"));
		assertThrows(AccountNotVisibleException.class,
			() -> service.release(VIEWER_ID, ACCOUNT_ID, UUID.randomUUID(), 1, "release-missing"));
		assertThrows(AccountPermissionDeniedException.class,
			() -> service.release(VIEWER_ID, ACCOUNT_ID, holdId, 1, "release-viewer"));
	}

	@Test
	void archivedAccountRejectsNewFactsButAllowsReleaseOfAnOperableVisibleHold() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold current = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID, NOW, NOW, 1);
		Account archived = Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "归档账户", null, AccountCurrency.CNY, null,
			AccountStatus.ARCHIVED, NOW, USER_ID, NOW, NOW, 2);
		List<LiquidityHold> inserted = new ArrayList<>();
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldService service = service(inserted, audits, Optional.of(current), Clock.fixed(NOW, ZoneOffset.UTC),
			Map.of(USER_ID, "OWNER"), archived);
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.RESERVED, new BigDecimal("11.00"), AccountCurrency.CNY, NOW, null, "归档后修订");

		assertDoesNotThrow(() -> service.preflightCreate(USER_ID, ACCOUNT_ID));
		assertDoesNotThrow(() -> service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 1, false));
		assertDoesNotThrow(() -> service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 1, true));
		assertThrows(LiquidityHoldException.BusinessRule.class,
			() -> service.create(USER_ID, ACCOUNT_ID, command, "archived-create"));
		assertThrows(LiquidityHoldException.BusinessRule.class,
			() -> service.revise(USER_ID, ACCOUNT_ID, holdId, 1, command, "archived-revise"));
		assertEquals(0, inserted.size());
		LiquidityHold released = service.release(USER_ID, ACCOUNT_ID, holdId, 1, "archived-release");
		assertEquals("RELEASED", released.endReason().name());
		assertEquals(NOW, released.releasedAt());
		assertEquals(1, audits.size());
	}

	@Test
	void transactionRechecksArchivedStatusAfterPreflightBeforeCreatingOrRevisingFacts() {
		UUID holdId = UUID.randomUUID();
		LiquidityHold current = LiquidityHold.restore(
			holdId, ACCOUNT_ID, holdId, null, 1, LiquidityHoldType.FROZEN,
			new BigDecimal("10.00"), AccountCurrency.CNY, NOW.minusSeconds(1), null, null,
			app.ziji.account.domain.LiquidityHoldSource.MANUAL, "原因", null, null, USER_ID, NOW, NOW, 1);
		Account active = Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "现金", null, AccountCurrency.CNY, null, USER_ID, NOW);
		Account archived = Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "归档账户", null, AccountCurrency.CNY, null,
			AccountStatus.ARCHIVED, NOW, USER_ID, NOW, NOW, 2);
		AtomicReference<Account> account = new AtomicReference<>(active);
		List<LiquidityHold> inserted = new ArrayList<>();
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldService service = service(inserted, audits, Optional.of(current), Clock.fixed(NOW, ZoneOffset.UTC),
			Map.of(USER_ID, "OWNER"), account::get);
		MemoryIdempotencyStore records = new MemoryIdempotencyStore();
		UnifiedIdempotencyService idempotency = idempotency(records, Clock.fixed(NOW, ZoneOffset.UTC));
		LiquidityHoldCommand command = new LiquidityHoldCommand(
			LiquidityHoldType.RESERVED, new BigDecimal("11.00"), AccountCurrency.CNY, NOW, null, "归档竞争");

		service.preflightCreate(USER_ID, ACCOUNT_ID);
		account.set(archived);
		assertThrows(LiquidityHoldException.BusinessRule.class,
			() -> idempotency.executeAuthenticated(USER_ID, 1, "createLiquidityHold", "archive-race-create-001", "c".repeat(64), () -> {
				LiquidityHold created = service.create(USER_ID, ACCOUNT_ID, command, "archived-after-create-preflight");
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededEmpty(201));
			}));
		account.set(active);
		service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 1, false);
		account.set(archived);
		assertThrows(LiquidityHoldException.BusinessRule.class,
			() -> idempotency.executeAuthenticated(USER_ID, 1, "reviseLiquidityHold", "archive-race-revise-01", "d".repeat(64), () -> {
				LiquidityHold revised = service.revise(USER_ID, ACCOUNT_ID, holdId, 1, command, "archived-after-revise-preflight");
				return IdempotencyWorkResult.completed(revised, IdempotencyResponse.succeededEmpty(201));
			}));

		assertTrue(inserted.isEmpty());
		assertTrue(audits.isEmpty());
		assertTrue(records.completed.isEmpty());
	}

	@Test
	void lifecycleAuditContainsOnlyTheRequiredVersionAndTypeMetadata() {
		List<LiquidityHold> inserted = new ArrayList<>();
		List<AuditLogWritePort.AuditLogEntry> audits = new ArrayList<>();
		LiquidityHoldService service = service(inserted, audits);
		LiquidityHold created = service.create(USER_ID, ACCOUNT_ID, new LiquidityHoldCommand(
			LiquidityHoldType.FROZEN, new BigDecimal("10.00"), AccountCurrency.CNY, NOW, null, "创建理由"), "request-create");
		LiquidityHold revised = service.revise(USER_ID, ACCOUNT_ID, created.id(), 1, new LiquidityHoldCommand(
			LiquidityHoldType.IN_TRANSIT, new BigDecimal("12.00"), AccountCurrency.CNY, NOW, null, "修订理由"), "request-revise");
		LiquidityHold released = service.release(USER_ID, ACCOUNT_ID, revised.id(), 1, "request-release");

		assertEquals(3, audits.size());
		AuditLogWritePort.AuditLogEntry create = audits.get(0);
		assertEquals("LIQUIDITY_HOLD_CREATED", create.action());
		assertEquals("LIQUIDITY_HOLD", create.resourceType());
		assertEquals(created.id(), create.resourceId());
		assertEquals(ACCOUNT_ID, create.accountId());
		assertEquals(USER_ID, create.actorUserId());
		assertEquals(AuditLogWritePort.ActorType.USER, create.actorType());
		assertEquals("request-create", create.requestId());
		assertEquals(AuditLogWritePort.Result.SUCCESS, create.result());
		assertEquals(null, create.reasonCode());
		assertEquals(created.id().toString(), create.metadata().get("holdId"));
		assertEquals("1", create.metadata().get("version"));
		assertEquals("FROZEN", create.metadata().get("type"));

		AuditLogWritePort.AuditLogEntry revise = audits.get(1);
		assertEquals("LIQUIDITY_HOLD_REVISED", revise.action());
		assertEquals("LIQUIDITY_HOLD", revise.resourceType());
		assertEquals("SUPERSEDED", revise.reasonCode());
		assertEquals(revised.id(), revise.resourceId());
		assertEquals(ACCOUNT_ID, revise.accountId());
		assertEquals(USER_ID, revise.actorUserId());
		assertEquals(AuditLogWritePort.ActorType.USER, revise.actorType());
		assertEquals("request-revise", revise.requestId());
		assertEquals(AuditLogWritePort.Result.SUCCESS, revise.result());
		assertEquals(created.id().toString(), revise.metadata().get("previousHoldId"));
		assertEquals("1", revise.metadata().get("previousVersion"));
		assertEquals("1", revise.metadata().get("expectedVersion"));
		assertEquals("1", revise.metadata().get("version"));
		assertEquals("FROZEN", revise.metadata().get("fromType"));
		assertEquals("IN_TRANSIT", revise.metadata().get("toType"));

		AuditLogWritePort.AuditLogEntry release = audits.get(2);
		assertEquals("LIQUIDITY_HOLD_RELEASED", release.action());
		assertEquals("LIQUIDITY_HOLD", release.resourceType());
		assertEquals("RELEASED", release.reasonCode());
		assertEquals(released.id(), release.resourceId());
		assertEquals(ACCOUNT_ID, release.accountId());
		assertEquals(USER_ID, release.actorUserId());
		assertEquals(AuditLogWritePort.ActorType.USER, release.actorType());
		assertEquals("request-release", release.requestId());
		assertEquals(AuditLogWritePort.Result.SUCCESS, release.result());
		assertEquals(revised.id().toString(), release.metadata().get("previousHoldId"));
		assertEquals("1", release.metadata().get("previousVersion"));
		assertEquals("1", release.metadata().get("expectedVersion"));
		assertEquals("2", release.metadata().get("version"));
		assertEquals("IN_TRANSIT", release.metadata().get("fromType"));
		assertEquals("IN_TRANSIT", release.metadata().get("toType"));
		for (AuditLogWritePort.AuditLogEntry audit : audits) {
			assertTrue(audit.metadata().keySet().stream().noneMatch(key ->
				key.toLowerCase().contains("reason") || key.toLowerCase().contains("amount")
					|| key.toLowerCase().contains("currency") || key.toLowerCase().contains("token")
					|| key.toLowerCase().contains("cookie") || key.toLowerCase().contains("key")));
		}
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits) {
		return service(inserted, audits, Optional.empty());
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits,
		Optional<LiquidityHold> replayHold) {
		return service(inserted, audits, replayHold, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits,
		Optional<LiquidityHold> replayHold,
		Clock clock) {
		return service(inserted, audits, replayHold, clock, Map.of(USER_ID, "OWNER"), USER_ID);
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits,
		Optional<LiquidityHold> replayHold,
		Clock clock,
		Map<UUID, String> activeRoles,
		UUID accountCreatedBy) {
		return service(inserted, audits, replayHold, clock, activeRoles, Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "现金", null, AccountCurrency.CNY, null,
			accountCreatedBy, NOW));
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits,
		Optional<LiquidityHold> replayHold,
		Clock clock,
		Map<UUID, String> activeRoles,
		Account account) {
		return service(inserted, audits, replayHold, clock, activeRoles, () -> account);
	}

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits,
		Optional<LiquidityHold> replayHold,
		Clock clock,
		Map<UUID, String> activeRoles,
		Supplier<Account> account) {
		AccountStore accounts = new AccountStore() {
			@Override
			public void insert(Account account) {}

			@Override
			public Optional<Account> findById(UUID accountId) {
				return ACCOUNT_ID.equals(accountId) ? Optional.of(account.get()) : Optional.empty();
			}

			@Override
			public Optional<Account> findByIdForUpdate(UUID accountId) {
				return findById(accountId);
			}
		};
		AccountMembershipReadPort memberships = new AccountMembershipReadPort() {
			@Override
			public List<ActiveMembership> listActiveMemberships(UUID userId) {
				return activeRoles.containsKey(userId)
					? List.of(new ActiveMembership(ACCOUNT_ID, activeRoles.get(userId), BigDecimal.ONE)) : List.of();
			}

			@Override
			public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
				return ACCOUNT_ID.equals(accountId) && activeRoles.containsKey(userId)
					? Optional.of(new ActiveMembership(ACCOUNT_ID, activeRoles.get(userId), BigDecimal.ONE)) : Optional.empty();
			}

			@Override
			public Optional<ActiveMembership> findActiveMembershipForUpdate(UUID userId, UUID accountId) {
				return findActiveMembership(userId, accountId);
			}
		};
		Map<UUID, LiquidityHold> stored = new HashMap<>();
		replayHold.ifPresent(hold -> stored.put(hold.id(), hold));
		LiquidityHoldStore holds = new LiquidityHoldStore() {
			@Override
			public List<LiquidityHold> listByAccount(UUID accountId, LiquidityHoldKeysetPosition after, int maximumRecords) {
				return List.of();
			}

			@Override
			public Optional<LiquidityHold> findByAccountAndId(UUID accountId, UUID holdId) {
				return Optional.ofNullable(stored.get(holdId)).filter(hold -> accountId.equals(hold.accountId()));
			}

			@Override
			public void insert(LiquidityHold hold) {
				inserted.add(hold);
				stored.put(hold.id(), hold);
			}

			@Override
			public Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId) {
				return findByAccountAndId(accountId, holdId);
			}

			@Override
			public Optional<LiquidityHold> supersedeIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
				LiquidityHold current = stored.get(holdId);
				if (current == null || !accountId.equals(current.accountId()) || current.version() != expectedVersion
					|| current.endedAt() != null) {
					return Optional.empty();
				}
				LiquidityHold closed = LiquidityHold.restore(
					current.id(), current.accountId(), current.rootHoldId(), current.previousRevisionId(), current.revisionNo(),
					current.type(), current.amount(), current.currency(), current.effectiveAt(), current.expiresAt(), null,
					current.source(), current.note(), now, app.ziji.account.domain.LiquidityHoldEndReason.SUPERSEDED,
					current.createdBy(), current.createdAt(), now, current.version() + 1);
				stored.put(holdId, closed);
				return Optional.of(closed);
			}

			@Override
			public Optional<LiquidityHold> releaseIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
				LiquidityHold current = stored.get(holdId);
				if (current == null || !accountId.equals(current.accountId()) || current.version() != expectedVersion
					|| current.endedAt() != null) {
					return Optional.empty();
				}
				LiquidityHold released = LiquidityHold.restore(
					current.id(), current.accountId(), current.rootHoldId(), current.previousRevisionId(), current.revisionNo(),
					current.type(), current.amount(), current.currency(), current.effectiveAt(), current.expiresAt(), now,
					current.source(), current.note(), now, app.ziji.account.domain.LiquidityHoldEndReason.RELEASED,
					current.createdBy(), current.createdAt(), now, current.version() + 1);
				stored.put(holdId, released);
				return Optional.of(released);
			}
		};
		LiquidityHoldCursorCodec cursors = new LiquidityHoldCursorCodec() {
			@Override
			public String encode(UUID accountId, LiquidityHoldKeysetPosition position) { return "unused"; }

			@Override
			public LiquidityHoldKeysetPosition decode(UUID accountId, String cursor) { return null; }
		};
		TransactionRunner transactions = new TransactionRunner() {
			@Override
			public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }

			@Override
			public void required(Runnable action) { action.run(); }
		};
		return new LiquidityHoldService(
			accounts, memberships, holds, cursors, audits::add, transactions,
			clock, UUID::randomUUID);
	}

	private static UnifiedIdempotencyService idempotency(MemoryIdempotencyStore records, Clock clock) {
		IdempotencyAnonymousSubjectHasher anonymous = email ->
			IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		return new UnifiedIdempotencyService(new TransactionRunner() {
			@Override
			public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }

			@Override
			public void required(Runnable action) { action.run(); }
		}, records, anonymous, clock);
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() { return ZoneOffset.UTC; }

		@Override
		public Clock withZone(java.time.ZoneId zone) { return this; }

		@Override
		public Instant instant() { return instant; }
	}

	private static final class MemoryIdempotencyStore implements IdempotencyRecordStore {
		private final Map<UUID, app.ziji.shared.application.IdempotencyRequest> acquired = new HashMap<>();
		private final Map<String, IdempotencyResponse> completed = new HashMap<>();

		@Override
		public Acquisition acquire(app.ziji.shared.application.IdempotencyRequest request, Instant now) {
			IdempotencyResponse prior = completed.get(request.idempotencyKey());
			if (prior != null) {
				return new Acquisition.Replay(prior);
			}
			UUID recordId = UUID.randomUUID();
			acquired.put(recordId, request);
			return new Acquisition.Acquired(recordId);
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			app.ziji.shared.application.IdempotencyRequest request = acquired.remove(recordId);
			if (request == null) {
				throw new IllegalStateException("测试幂等记录不存在");
			}
			completed.put(request.idempotencyKey(), response);
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) { return 0; }
	}
}
