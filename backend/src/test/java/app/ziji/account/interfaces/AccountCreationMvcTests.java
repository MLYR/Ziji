package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountLedgerInitializationPort;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.application.AccountPage;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryUseCase;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountPatch;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.accountmember.application.AccountMemberInitPort;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.IdempotencyRecordStore;
import app.ziji.shared.application.IdempotencyRequest;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.CurrentUserTimezonePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** createAccount 的 HTTP 载荷、幂等重放和期初余额边界测试，不依赖 Spring 或数据库。 */
class AccountCreationMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000711");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000712");
	private static final UUID OPENING_ID = UUID.fromString("00000000-0000-0000-0000-000000000713");
	private static final Instant NOW = Instant.parse("2026-08-15T01:02:03Z");

	@Test
	void createReturns201EnvelopeAndReplaysOpeningTransactionId() throws Exception {
		Fixture fixture = fixture();
		String body = """
			{"accountClass":"ASSET","accountType":"BANK","name":"工资卡","currency":"CNY",
			 "openingBalance":{"amount":"100.00","businessAt":"2026-08-14T16:30:00Z","note":null}}
			""";

		fixture.mvc.perform(post("/api/v1/accounts").principal(() -> USER_ID.toString())
				.header("Idempotency-Key", "account-create-key-01").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.data.account.id").value(ACCOUNT_ID.toString()))
			.andExpect(jsonPath("$.data.openingTransactionId").value(OPENING_ID.toString()));
		fixture.mvc.perform(post("/api/v1/accounts").principal(() -> USER_ID.toString())
				.header("Idempotency-Key", "account-create-key-01").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.openingTransactionId").value(OPENING_ID.toString()));

		assertEquals(1, fixture.ledger.openingCalls);
		assertEquals(ZoneId.of("Asia/Shanghai"), fixture.ledger.timezone);
	}

	@Test
	void absentOrNullOpeningBalanceDoesNotPostAndInvalidInputDoesNotAcquireIdempotency() throws Exception {
		Fixture fixture = fixture();
		for (String body : new String[] {
			"{\"accountClass\":\"ASSET\",\"accountType\":\"CASH\",\"name\":\"现金\",\"currency\":\"JPY\"}",
			"{\"accountClass\":\"ASSET\",\"accountType\":\"CASH\",\"name\":\"现金\",\"currency\":\"JPY\",\"openingBalance\":null}"}) {
			fixture.mvc.perform(post("/api/v1/accounts").principal(() -> USER_ID.toString())
					.header("Idempotency-Key", "account-null-opening-" + body.length()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.openingTransactionId").value(org.hamcrest.Matchers.nullValue()));
		}
		int before = fixture.records.acquisitions;
		for (String invalid : new String[] {
			"{\"accountClass\":\"ASSET\",\"accountType\":\"FUND\",\"name\":\"错误\",\"currency\":\"CNY\"}",
			"{\"accountClass\":\"ASSET\",\"accountType\":\"CASH\",\"name\":\"错误\",\"currency\":\"CNY\",\"creditLimit\":\"10\"}",
			"{\"accountClass\":\"ASSET\",\"accountType\":\"CASH\",\"name\":\"错误\",\"currency\":\"CNY\",\"openingBalance\":{\"amount\":\"0\",\"businessAt\":\"2026-08-15T01:00:00Z\"}}",
			"{\"accountClass\":\"ASSET\",\"accountType\":\"CASH\",\"name\":\"错误\",\"currency\":\"JPY\",\"openingBalance\":{\"amount\":\"1.1\",\"businessAt\":\"2026-08-15T01:00:00Z\"}}"}) {
			fixture.mvc.perform(post("/api/v1/accounts").principal(() -> USER_ID.toString())
					.header("Idempotency-Key", "account-invalid-key-01").contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		assertEquals(before, fixture.records.acquisitions);
		assertEquals(0, fixture.ledger.openingCalls);
	}

	private static Fixture fixture() {
		FakeAccounts accounts = new FakeAccounts();
		FakeLedger ledger = new FakeLedger();
		AccountCreationService creation = new AccountCreationService(
			new DirectTransactions(), accounts, new Members(), ledger, Clock.fixed(NOW, ZoneOffset.UTC), () -> ACCOUNT_ID);
		Records records = new Records();
		CurrentUserIdResolver users = principal -> USER_ID;
		CurrentUserTimezonePort timezones = userId -> ZoneId.of("Asia/Shanghai");
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new AccountController(
			new Queries(accounts), creation, users, idempotency(records), timezones))
			.setControllerAdvice(new AccountApiExceptionHandler()).build();
		return new Fixture(mvc, ledger, records);
	}

	private static UnifiedIdempotencyService idempotency(Records records) {
		IdempotencyAnonymousSubjectHasher anonymous = email ->
			IdempotencySubject.anonymous(new IdempotencySubject.AnonymousDigest(1, new byte[32]), null);
		return new UnifiedIdempotencyService(new DirectTransactions(), records, anonymous, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private record Fixture(MockMvc mvc, FakeLedger ledger, Records records) {}

	private static final class DirectTransactions implements TransactionRunner {
		@Override public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }
		@Override public void required(Runnable action) { action.run(); }
	}

	private static final class FakeAccounts implements AccountStore {
		private Account account;
		@Override public void insert(Account value) { account = value; }
		@Override public Optional<Account> findById(UUID accountId) { return account != null && account.id().equals(accountId) ? Optional.of(account) : Optional.empty(); }
	}

	private static final class Members implements AccountMemberInitPort {
		@Override public UUID initializeOwnerMembership(UUID accountId, UUID userId, Instant now) { return UUID.randomUUID(); }
		@Override public void initializeInitialInclusion(UUID membershipId, UUID userId, Instant now) {}
	}

	private static final class FakeLedger implements AccountLedgerInitializationPort {
		private int openingCalls;
		private ZoneId timezone;
		@Override public void initializePrimary(UUID accountId, String accountClass, String currency, Instant now) {}
		@Override public void initializePositionCost(UUID accountId, String currency, Instant now) {}
		@Override public UUID postOpening(
			UUID accountId,
			String accountClass,
			String currency,
			UUID createdBy,
			AccountOpeningBalance opening,
			ZoneId value) {
			openingCalls++;
			timezone = value;
			if (opening.amount().compareTo(new BigDecimal("100.00")) == 0) return OPENING_ID;
			throw new IllegalArgumentException("JPY 精度应在 Ledger 前拒绝。");
		}
		@Override public Optional<UUID> findOpeningTransactionId(UUID accountId) {
			return openingCalls == 0 ? Optional.empty() : Optional.of(OPENING_ID);
		}
	}

	private static final class Queries implements AccountQueryUseCase {
		private final FakeAccounts accounts;
		private Queries(FakeAccounts accounts) { this.accounts = accounts; }
		@Override public AccountPage listVisibleAccounts(UUID userId, Integer limit, String cursor) { return new AccountPage(java.util.List.of(), null, false); }
		@Override public AccountQueryResult getVisibleAccount(UUID userId, UUID accountId) {
			Account account = accounts.findById(accountId).orElseThrow();
			return new AccountQueryResult(account.id(), account.accountClass(), account.accountType(), account.name(), account.institution(),
				account.currency(), AccountStatus.ACTIVE, account.createdAt(), account.version(), "OWNER", new BigDecimal("1.000000"));
		}
		@Override public AccountQueryResult updateAccount(UUID userId, UUID accountId, int version, AccountPatch patch) { throw new UnsupportedOperationException(); }
	}

	private static final class Records implements IdempotencyRecordStore {
		private final Map<String, Completed> completed = new HashMap<>();
		private final Map<UUID, IdempotencyRequest> acquired = new HashMap<>();
		private int acquisitions;
		@Override public Acquisition acquire(IdempotencyRequest request, Instant now) {
			acquisitions++;
			Completed prior = completed.get(request.idempotencyKey());
			if (prior != null) return prior.request.requestHash().equals(request.requestHash()) ? new Acquisition.Replay(prior.response) : new Acquisition.KeyReused();
			UUID id = UUID.randomUUID(); acquired.put(id, request); return new Acquisition.Acquired(id);
		}
		@Override public void complete(UUID id, IdempotencyResponse response, Instant at) {
			IdempotencyRequest request = acquired.remove(id); completed.put(request.idempotencyKey(), new Completed(request, response));
		}
		@Override public int deleteExpiredTerminalRecords(Instant now, int maximumRecords) { return 0; }
		private record Completed(IdempotencyRequest request, IdempotencyResponse response) {}
	}
}
