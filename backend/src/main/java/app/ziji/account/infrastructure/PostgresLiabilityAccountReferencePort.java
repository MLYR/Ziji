package app.ziji.account.infrastructure;

import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountStore;
import app.ziji.account.application.LiabilityAccountReference;
import app.ziji.account.application.LiabilityAccountReferencePort;
import app.ziji.account.domain.Account;
import org.springframework.stereotype.Repository;

/** 通过账户聚合端口提供负债详情所需的最小公开快照。 */
@Repository
public class PostgresLiabilityAccountReferencePort implements LiabilityAccountReferencePort {

	private final AccountStore accounts;

	public PostgresLiabilityAccountReferencePort(AccountStore accounts) {
		this.accounts = accounts;
	}

	@Override
	public Optional<LiabilityAccountReference> findById(UUID accountId) {
		return accounts.findById(accountId).map(PostgresLiabilityAccountReferencePort::toReference);
	}

	private static LiabilityAccountReference toReference(Account account) {
		return new LiabilityAccountReference(
			account.id(), account.accountClass().name(), account.accountType().name(), account.currency().name());
	}
}
