package app.ziji.ledger.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceException;
import app.ziji.account.application.AccountBalanceFactReadPort.PrimaryNature;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** PRIMARY 事实映射的 fail-closed 测试；正常写入路径由数据库约束同时保护。 */
class PostgresAccountBalanceReadPortTests {

	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000001201");
	private static final UUID LEDGER_ID = UUID.fromString("00000000-0000-0000-0000-000000001202");
	private static final Instant AS_OF = Instant.parse("2026-08-16T00:00:00Z");

	@Test
	void accountNatureMismatchFailsClosedBeforeReadingBalance() {
		LedgerAccountReference reference = new LedgerAccountReference(
			LEDGER_ID, ACCOUNT_ID, null, "ACCOUNT_PRIMARY", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.LIABILITY, CurrencyCode.CNY, true);

		PostgresAccountBalanceReadPort port = new PostgresAccountBalanceReadPort(new FakeLedgerAccountStore(reference));

		assertThrows(AccountBalanceException.class,
			() -> port.findPostedPrimaryBalanceAt(ACCOUNT_ID, PrimaryNature.ASSET, AS_OF));
	}

	@Test
	void archivedPrimaryFailsClosedBeforeReadingBalance() {
		LedgerAccountReference reference = new LedgerAccountReference(
			LEDGER_ID, ACCOUNT_ID, null, "ACCOUNT_PRIMARY", LedgerAccountRole.PRIMARY,
			LedgerAccountNature.ASSET, CurrencyCode.CNY, false);

		PostgresAccountBalanceReadPort port = new PostgresAccountBalanceReadPort(new FakeLedgerAccountStore(reference));

		assertThrows(AccountBalanceException.class,
			() -> port.findPostedPrimaryBalanceAt(ACCOUNT_ID, PrimaryNature.ASSET, AS_OF));
	}

	private static final class FakeLedgerAccountStore implements LedgerAccountStore {

		private final LedgerAccountReference reference;

		private FakeLedgerAccountStore(LedgerAccountReference reference) {
			this.reference = reference;
		}

		@Override
		public Optional<LedgerAccountReference> findById(UUID ledgerAccountId) {
			return Optional.empty();
		}

		@Override
		public Optional<LedgerAccountReference> findPrimaryForVisibleAccount(UUID accountId) {
			return Optional.of(reference);
		}

		@Override
		public LedgerAccountReference ensureCategorySystemAccount(
			UUID ownerUserId, UUID categoryId, LedgerAccountNature nature, CurrencyCode currency) {
			throw new AssertionError("余额读取不应创建系统科目。");
		}

		@Override
		public Money currentBalance(UUID ledgerAccountId) {
			throw new AssertionError("映射无效时不应读取当前余额。");
		}
	}
}
