package app.ziji.ledger.infrastructure;

import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceReadPort;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.Money;
import org.springframework.stereotype.Repository;

/** 将已入账 PRIMARY Ledger 余额转换为账户模块的最小读取快照，不暴露账务表或投影。 */
@Repository
public final class PostgresAccountBalanceReadPort implements AccountBalanceReadPort {

	private final LedgerAccountStore ledgerAccounts;

	public PostgresAccountBalanceReadPort(LedgerAccountStore ledgerAccounts) {
		if (ledgerAccounts == null) {
			throw new IllegalArgumentException("账务余额读取入口不能为空。");
		}
		this.ledgerAccounts = ledgerAccounts;
	}

	@Override
	public Optional<PostedPrimaryBalance> findPostedPrimaryBalance(UUID accountId) {
		if (accountId == null) {
			return Optional.empty();
		}
		Optional<LedgerAccountReference> primary = ledgerAccounts.findPrimaryForVisibleAccount(accountId);
		if (primary.isEmpty()) {
			return Optional.empty();
		}
		Money balance = ledgerAccounts.currentBalance(primary.get().id());
		return Optional.of(new PostedPrimaryBalance(
			balance.amount(), AccountCurrency.fromCode(balance.currency().name())));
	}
}
