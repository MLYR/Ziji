package app.ziji.account.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import app.ziji.shared.application.TransactionRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BUG-API-006：客户端币种必须在 application 层与账户事实比较，不能直写持久化。 */
class LiquidityHoldServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
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

	private static LiquidityHoldService service(
		List<LiquidityHold> inserted,
		List<AuditLogWritePort.AuditLogEntry> audits) {
		AccountStore accounts = new AccountStore() {
			@Override
			public void insert(Account account) {}

			@Override
			public Optional<Account> findById(UUID accountId) {
				return Optional.of(Account.create(ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK,
					"现金", null, AccountCurrency.CNY, null, USER_ID, NOW));
			}
		};
		AccountMembershipReadPort memberships = new AccountMembershipReadPort() {
			@Override
			public List<ActiveMembership> listActiveMemberships(UUID userId) {
				return List.of(new ActiveMembership(ACCOUNT_ID, "OWNER", BigDecimal.ONE));
			}

			@Override
			public Optional<ActiveMembership> findActiveMembership(UUID userId, UUID accountId) {
				return Optional.of(new ActiveMembership(ACCOUNT_ID, "OWNER", BigDecimal.ONE));
			}
		};
		LiquidityHoldStore holds = new LiquidityHoldStore() {
			@Override
			public List<LiquidityHold> listByAccount(UUID accountId, LiquidityHoldKeysetPosition after, int maximumRecords) {
				return List.of();
			}

			@Override
			public Optional<LiquidityHold> findByAccountAndId(UUID accountId, UUID holdId) { return Optional.empty(); }

			@Override
			public Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId) { return Optional.empty(); }

			@Override
			public void insert(LiquidityHold hold) { inserted.add(hold); }

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
			Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);
	}
}
