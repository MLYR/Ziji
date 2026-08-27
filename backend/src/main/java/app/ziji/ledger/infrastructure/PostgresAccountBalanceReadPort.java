package app.ziji.ledger.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceReadPort;
import app.ziji.account.application.AccountBalanceFactReadPort;
import app.ziji.account.application.AccountBalanceException;
import app.ziji.account.application.AccountBalanceFactReadPort.PrimaryNature;
import app.ziji.ledger.application.LedgerAccountStore;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.Money;
import org.springframework.stereotype.Repository;

/** 将已入账 PRIMARY Ledger 余额转换为账户模块的最小读取快照，不暴露账务表或投影。 */
@Repository
// 该适配器参与账户归档事务，保留可被 Spring 代理子类化的边界。
public class PostgresAccountBalanceReadPort implements AccountBalanceReadPort, AccountBalanceFactReadPort {

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
			balance.amount(), balance.currency().name()));
	}

	@Override
	public Optional<PostedPrimaryBalance> findPostedPrimaryBalanceAt(
		UUID accountId, PrimaryNature primaryNature, Instant asOf) {
		if (accountId == null || primaryNature == null || asOf == null) {
			return Optional.empty();
		}
		try {
			Optional<LedgerAccountReference> primary = ledgerAccounts.findPrimaryForVisibleAccount(accountId);
			if (primary.isEmpty()) {
				return Optional.empty();
			}
			LedgerAccountReference reference = primary.get();
			// PRIMARY 的会计性质决定借贷符号；错配或归档科目必须阻止余额读取而不能返回伪余额。
			LedgerAccountNature expectedNature = primaryNature == PrimaryNature.LIABILITY
				? LedgerAccountNature.LIABILITY : LedgerAccountNature.ASSET;
			if (reference.role() != LedgerAccountRole.PRIMARY || !reference.active()
				|| reference.nature() != expectedNature
				|| !accountId.equals(reference.visibleAccountId())) {
				throw AccountBalanceException.persistence(new IllegalStateException("PRIMARY 科目映射事实无效。"));
			}
			Money balance = ledgerAccounts.balanceAt(reference.id(), asOf);
			if (balance == null || balance.currency() != reference.currency()) {
				throw AccountBalanceException.persistence(new IllegalStateException("PRIMARY 余额事实不一致。"));
			}
			return Optional.of(new PostedPrimaryBalance(balance.amount(), balance.currency().name()));
		} catch (AccountBalanceException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw AccountBalanceException.persistence(exception);
		}
	}
}
