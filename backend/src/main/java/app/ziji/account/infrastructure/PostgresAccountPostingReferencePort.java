package app.ziji.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.account.application.AccountStore;
import app.ziji.account.domain.Account;
import org.springframework.stereotype.Repository;

/** 通过账户聚合端口提供账务所需的最小公开快照。 */
@Repository
public class PostgresAccountPostingReferencePort implements AccountPostingReferencePort {

	private final AccountStore accounts;

	public PostgresAccountPostingReferencePort(AccountStore accounts) {
		this.accounts = accounts;
	}

	@Override
	public Optional<AccountPostingReference> findById(UUID accountId) {
		return accounts.findById(accountId).map(PostgresAccountPostingReferencePort::toReference);
	}

	private static AccountPostingReference toReference(Account account) {
		return new AccountPostingReference(
			account.id(),
			account.accountClass().name(),
			account.accountType().name(),
			account.currency().name(),
			account.status() == app.ziji.account.domain.AccountStatus.ACTIVE);
	}
}
