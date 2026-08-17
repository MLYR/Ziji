package app.ziji.liability.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.LiabilityAccountReference;
import app.ziji.account.application.LiabilityAccountReferencePort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiabilityDetailServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000501");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000502");
	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
	private static final String KEY = "liability-detail-key-0001";

	@Test
	void emptyDetailsAndActiveRoleMatrixUseMembershipInsteadOfCreatedBy() {
		Fixture fixture = fixture("CREDIT_CARD", "CNY", "OWNER");
		LiabilityDetail empty = fixture.service.get(USER_ID, ACCOUNT_ID);
		assertEquals(0, empty.version());
		assertNull(empty.currentAmountDue());

		fixture.memberships.role = "EDITOR";
		fixture.service.authorizeWrite(USER_ID, ACCOUNT_ID);
		fixture.memberships.role = "VIEWER";
		assertEquals(0, fixture.service.get(USER_ID, ACCOUNT_ID).version());
		assertThrows(LiabilityDetailApplicationException.PermissionDenied.class,
			() -> fixture.service.authorizeWrite(USER_ID, ACCOUNT_ID));
		fixture.memberships.visible = false;
		assertThrows(LiabilityDetailApplicationException.NotFound.class,
			() -> fixture.service.get(USER_ID, ACCOUNT_ID));

		fixture.memberships.visible = true;
		fixture.memberships.role = "OWNER";
		fixture.accounts.reference = new LiabilityAccountReference(ACCOUNT_ID, "ASSET", "BANK", "CNY");
		assertThrows(LiabilityDetailApplicationException.NotFound.class,
			() -> fixture.service.get(USER_ID, ACCOUNT_ID));
	}

	@Test
	void fourAccountTypesAcceptOnlyTheirFrozenFieldMatrix() {
		putForType("CREDIT_CARD", values("0.05", null, null, 8, 20, "100.00"));
		putForType("LOAN", values("0.05", "2026-01-01", "2027-01-01", null, 20, "100.00"));
		putForType("CONSUMER_LOAN", values("0.05", "2026-01-01", "2027-01-01", null, 20, "100.00"));
		putForType("OTHER", values("0.05", "2026-01-01", "2027-01-01", 8, 20, "100.00"));
	}

	@Test
	void putCreateReplaceAndPatchAdvanceOnlyDetailVersion() {
		Fixture fixture = fixture("OTHER", "CNY", "OWNER");
		int accountVersion = fixture.accounts.version;

		LiabilityDetailWriteResult created = fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values("0.05", "2026-01-01", "2027-01-01", 8, 20, "100.00"), KEY);
		assertEquals(201, created.status());
		assertEquals(1, created.detail().version());

		LiabilityDetailWriteResult replay = fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values("0.05", "2026-01-01", "2027-01-01", 8, 20, "100.00"), KEY);
		assertEquals(201, replay.status());
		assertEquals(1, replay.detail().version());
		assertEquals(1, fixture.details.inserts);

		LiabilityDetailWriteResult replaced = fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.replace(1),
			values("0.06", "2026-01-01", "2027-02-01", 9, 21, "80.50"), "liability-replace-key-01");
		assertEquals(200, replaced.status());
		assertEquals(2, replaced.detail().version());

		LiabilityDetailWriteResult patched = fixture.service.patch(
			USER_ID, ACCOUNT_ID, 2,
			new LiabilityDetailPatch(false, null, false, null, false, null,
				true, null, true, 22, true, BigDecimal.ZERO), "liability-patch-key-0001");
		assertEquals(200, patched.status());
		assertEquals(3, patched.detail().version());
		assertNull(patched.detail().billingDay());
		assertEquals(22, patched.detail().repaymentDay());
		assertEquals(accountVersion, fixture.accounts.version);
	}

	@Test
	void versionAndExistenceConflictsEndBeforeNewIdempotencyAcquisition() {
		Fixture fixture = fixture("OTHER", "JPY", "OWNER");
		fixture.details.current = LiabilityDetail.create(
			ACCOUNT_ID, values(null, null, null, null, null, "100"), NOW);

		assertThrows(LiabilityDetailApplicationException.VersionConflict.class, () -> fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values(null, null, null, null, null, "200"), KEY));
		assertThrows(LiabilityDetailApplicationException.VersionConflict.class, () -> fixture.service.patch(
			USER_ID, ACCOUNT_ID, 2,
			new LiabilityDetailPatch(false, null, false, null, false, null,
				false, null, false, null, true, new BigDecimal("200")), "liability-stale-key-001"));
		assertEquals(0, fixture.idempotency.acquisitions);

		fixture.details.current = null;
		assertThrows(LiabilityDetailApplicationException.NotFound.class, () -> fixture.service.patch(
			USER_ID, ACCOUNT_ID, 1,
			new LiabilityDetailPatch(false, null, false, null, false, null,
				false, null, false, null, true, new BigDecimal("200")), "liability-missing-key-01"));
		assertEquals(0, fixture.idempotency.acquisitions);
	}

	@Test
	void sameKeyDifferentHashIsRejectedWithoutSecondWrite() {
		Fixture fixture = fixture("CREDIT_CARD", "CNY", "OWNER");
		fixture.service.put(USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values(null, null, null, 8, 20, "100.00"), KEY);

		assertThrows(LiabilityDetailApplicationException.IdempotencyKeyReused.class, () -> fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values(null, null, null, 9, 20, "100.00"), KEY));
		assertEquals(1, fixture.details.inserts);
	}

	@Test
	void replayFailsClosedAfterAnotherWriteAdvancesTheDetailVersion() {
		Fixture fixture = fixture("CREDIT_CARD", "CNY", "OWNER");
		fixture.service.put(USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values(null, null, null, 8, 20, "100.00"), KEY);
		fixture.service.patch(USER_ID, ACCOUNT_ID, 1,
			new LiabilityDetailPatch(false, null, false, null, false, null,
				true, 9, false, null, false, null), "liability-drift-patch-01");

		assertThrows(LiabilityDetailApplicationException.SafeReplayUnavailable.class, () -> fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(),
			values(null, null, null, 8, 20, "100.00"), KEY));
		assertEquals(2, fixture.details.current.version());
	}

	private static void putForType(String accountType, LiabilityDetailValues values) {
		Fixture fixture = fixture(accountType, "CNY", "OWNER");
		LiabilityDetailWriteResult result = fixture.service.put(
			USER_ID, ACCOUNT_ID, LiabilityDetailPutCondition.initial(), values,
			"liability-type-" + accountType.toLowerCase() + "-key");
		assertEquals(1, result.detail().version());
	}

	private static Fixture fixture(String accountType, String currency, String role) {
		FakeAccountPort accounts = new FakeAccountPort(
			new LiabilityAccountReference(ACCOUNT_ID, "LIABILITY", accountType, currency));
		FakeMembershipPort memberships = new FakeMembershipPort(role);
		MemoryDetailStore details = new MemoryDetailStore();
		MemoryIdempotencyStore idempotency = new MemoryIdempotencyStore();
		UnifiedIdempotencyService unified = new UnifiedIdempotencyService(
			new ImmediateTransactions(), idempotency,
			email -> IdempotencySubject.anonymous(
				new IdempotencySubject.AnonymousDigest(1, new byte[32]), null),
			Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(new LiabilityDetailService(
			accounts, memberships, details, unified, Clock.fixed(NOW, ZoneOffset.UTC)),
			accounts, memberships, details, idempotency);
	}

	private static LiabilityDetailValues values(
		String interestRate, String loanDate, String dueDate,
		Integer billingDay, Integer repaymentDay, String currentAmountDue) {
		return new LiabilityDetailValues(
			interestRate == null ? null : new BigDecimal(interestRate),
			loanDate == null ? null : LocalDate.parse(loanDate),
			dueDate == null ? null : LocalDate.parse(dueDate),
			billingDay, repaymentDay,
			currentAmountDue == null ? null : new BigDecimal(currentAmountDue));
	}

	private record Fixture(
		LiabilityDetailService service,
		FakeAccountPort accounts,
		FakeMembershipPort memberships,
		MemoryDetailStore details,
		MemoryIdempotencyStore idempotency) {
	}

	private static final class FakeAccountPort implements LiabilityAccountReferencePort {
		private LiabilityAccountReference reference;
		private int version = 9;

		private FakeAccountPort(LiabilityAccountReference reference) {
			this.reference = reference;
		}

		@Override
		public Optional<LiabilityAccountReference> findById(UUID accountId) {
			return reference != null && reference.id().equals(accountId) ? Optional.of(reference) : Optional.empty();
		}
	}

	private static final class FakeMembershipPort implements AccountMembershipReadPort {
		private boolean visible = true;
		private String role;

		private FakeMembershipPort(String role) {
			this.role = role;
		}

		@Override
		public List<ActiveMembership> listActiveMemberships(UUID userId) {
			return findActiveMembership(userId, ACCOUNT_ID).stream().toList();
		}

		@Override
		public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
			return visible && USER_ID.equals(userId) && ACCOUNT_ID.equals(accountId)
				? Optional.of(new ActiveMembership(accountId, role, BigDecimal.ONE)) : Optional.empty();
		}
	}

	private static final class MemoryDetailStore implements LiabilityDetailStore {
		private LiabilityDetail current;
		private int inserts;

		@Override
		public Optional<LiabilityDetail> findByAccountId(UUID accountId) {
			return current != null && current.accountId().equals(accountId) ? Optional.of(current) : Optional.empty();
		}

		@Override
		public Optional<LiabilityDetail> lockByAccountId(UUID accountId) {
			return findByAccountId(accountId);
		}

		@Override
		public boolean insertIfAbsent(LiabilityDetail detail) {
			if (current != null) return false;
			current = detail;
			inserts++;
			return true;
		}

		@Override
		public Optional<LiabilityDetail> updateIfVersion(LiabilityDetail detail, int expectedVersion) {
			if (current == null || current.version() != expectedVersion) return Optional.empty();
			current = detail;
			return Optional.of(detail);
		}
	}

	private static final class MemoryIdempotencyStore implements IdempotencyRecordStore {
		private final Map<String, Completed> completed = new HashMap<>();
		private final Map<UUID, IdempotencyRequest> acquired = new HashMap<>();
		private int acquisitions;

		@Override
		public Optional<Acquisition> inspect(IdempotencyRequest request, Instant now) {
			Completed existing = completed.get(request.idempotencyKey());
			if (existing == null) return Optional.empty();
			return Optional.of(existing.request().requestHash().equals(request.requestHash())
				? new Acquisition.Replay(existing.response()) : new Acquisition.KeyReused());
		}

		@Override
		public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquisitions++;
			Optional<Acquisition> inspected = inspect(request, now);
			if (inspected.isPresent()) return inspected.get();
			UUID id = UUID.randomUUID();
			acquired.put(id, request);
			return new Acquisition.Acquired(id);
		}

		@Override
		public void complete(UUID recordId, IdempotencyResponse response, Instant completedAt) {
			IdempotencyRequest request = acquired.remove(recordId);
			completed.put(request.idempotencyKey(), new Completed(request, response));
		}

		@Override
		public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) {
			return 0;
		}

		private record Completed(IdempotencyRequest request, IdempotencyResponse response) {
		}
	}

	private static final class ImmediateTransactions implements TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}
}
