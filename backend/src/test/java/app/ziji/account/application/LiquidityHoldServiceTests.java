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

import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
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
		assertThrows(LiquidityHoldException.VersionConflict.class,
			() -> service.preflightMutation(USER_ID, ACCOUNT_ID, holdId, 2));
		assertThrows(AccountPermissionDeniedException.class,
			() -> service.preflightMutation(VIEWER_ID, ACCOUNT_ID, holdId, 1));
		for (UUID invisibleUserId : List.of(LEFT_ID, REMOVED_ID, ENDED_ID, STRANGER_ID, CREATED_BY_ONLY_ID)) {
			assertThrows(AccountNotVisibleException.class, () -> service.list(invisibleUserId, ACCOUNT_ID, null, null));
			assertThrows(AccountNotVisibleException.class,
				() -> service.preflightMutation(invisibleUserId, ACCOUNT_ID, holdId, 1));
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
		AccountStore accounts = new AccountStore() {
			@Override
			public void insert(Account account) {}

			@Override
			public Optional<Account> findById(UUID accountId) {
				return Optional.of(Account.create(ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK,
					"现金", null, AccountCurrency.CNY, null, accountCreatedBy, NOW));
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
			public Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId) { return Optional.empty(); }

			@Override
			public void insert(LiquidityHold hold) {
				inserted.add(hold);
				stored.put(hold.id(), hold);
			}

			@Override
			public Optional<LiquidityHold> supersedeIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
				return Optional.empty();
			}

			@Override
			public Optional<LiquidityHold> releaseIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now) {
				return Optional.empty();
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
