package app.ziji.account.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceReadPort.PostedPrimaryBalance;
import app.ziji.account.application.AccountBalanceResult.LiquidityStatus;
import app.ziji.account.domain.Account;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;

/** 账户余额读取编排：权限和账户事实、Ledger、Hold 均在同一 PostgreSQL 快照内完成。 */
public class AccountBalanceService implements AccountBalanceUseCase {

	private static final Set<String> READABLE_ROLES = Set.of("OWNER", "EDITOR", "VIEWER");

	private final AccountQueryReadPort accounts;
	private final AccountMembershipReadPort memberships;
	private final AccountBalanceFactReadPort ledgerBalances;
	private final LiquidityHoldBalanceReadPort holdBalances;
	private final AccountBalanceSnapshotTransaction snapshots;
	private final Clock clock;

	public AccountBalanceService(
		AccountQueryReadPort accounts,
		AccountMembershipReadPort memberships,
		AccountBalanceFactReadPort ledgerBalances,
		LiquidityHoldBalanceReadPort holdBalances,
		AccountBalanceSnapshotTransaction snapshots,
		Clock clock) {
		if (accounts == null || memberships == null || ledgerBalances == null || holdBalances == null
			|| snapshots == null || clock == null) {
			throw new AccountQueryValidationException("账户余额读取依赖不能为空。");
		}
		this.accounts = accounts;
		this.memberships = memberships;
		this.ledgerBalances = ledgerBalances;
		this.holdBalances = holdBalances;
		this.snapshots = snapshots;
		this.clock = clock;
	}

	@Override
	public AccountBalanceResult getBalance(UUID userId, UUID accountId, Instant requestedAsOf) {
		if (userId == null || accountId == null) {
			throw new AccountQueryValidationException();
		}
		try {
			return snapshots.read(() -> {
				// 缺失 asOf 只在共同快照事务内捕获一次 Clock；显式时点绝不再次读取系统时钟。
				Instant asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
				ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
					.orElseThrow(AccountNotVisibleException::new);
				validateMembership(membership, accountId);
				Account account = accounts.findById(accountId).orElseThrow(AccountNotVisibleException::new);

				PostedPrimaryBalance ledger = ledgerBalances.findPostedPrimaryBalanceAt(
					accountId, expectedPrimaryNature(account.accountClass()), asOf)
					.orElseThrow(() -> AccountBalanceException.persistence(
						new IllegalStateException("账户 PRIMARY 科目不存在。")));
				AccountCurrency ledgerCurrency = parseCurrency(ledger.currencyCode());
				if (ledgerCurrency != account.currency()) {
					throw AccountBalanceException.persistence(new IllegalStateException("账户与 PRIMARY 余额币种不一致。"));
				}

				LiquidityHoldBalanceReadPort.EffectiveHoldAmounts holds = holdBalances.sumEffectiveAt(accountId, asOf);
				validateHoldCurrency(account.currency(), holds);
				// 单行金额精度必须在聚合前由 infrastructure 识别，不能被相加后的合法 scale 掩盖。
				if (holds.precisionErrorCount() != 0) {
					throw AccountBalanceException.persistence(new IllegalStateException("流动性占用金额精度无效。"));
				}
				BigDecimal ledgerAmount = exactAmount(ledger.amount(), account.currency());
				BigDecimal frozen = exactAmount(holds.frozen(), account.currency());
				BigDecimal inTransit = exactAmount(holds.inTransit(), account.currency());
				BigDecimal reserved = exactAmount(holds.reserved(), account.currency());
				AccountBalanceResult.UnavailableBreakdown breakdown =
					new AccountBalanceResult.UnavailableBreakdown(frozen, inTransit, reserved);
				BigDecimal unavailable = breakdown.total();
				BigDecimal available = ledgerAmount.subtract(unavailable);
				return new AccountBalanceResult(
					accountId,
					account.currency(),
					ledgerAmount,
					unavailable,
					breakdown,
					available,
					available.signum() < 0 ? LiquidityStatus.NEGATIVE_AVAILABLE : LiquidityStatus.NORMAL,
					asOf,
					0);
			});
		} catch (AccountNotVisibleException | AccountQueryValidationException | AccountBalanceException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw AccountBalanceException.persistence(exception);
		}
	}

	private static AccountBalanceFactReadPort.PrimaryNature expectedPrimaryNature(AccountClass accountClass) {
		return accountClass == AccountClass.LIABILITY
			? AccountBalanceFactReadPort.PrimaryNature.LIABILITY
			: AccountBalanceFactReadPort.PrimaryNature.ASSET;
	}

	private static void validateMembership(ActiveMembership membership, UUID accountId) {
		if (!accountId.equals(membership.accountId()) || !READABLE_ROLES.contains(membership.role())
			|| membership.inclusionRatio().signum() < 0
			|| membership.inclusionRatio().compareTo(BigDecimal.ONE) > 0) {
			throw AccountBalanceException.persistence(new IllegalStateException("当前账户成员事实无效。"));
		}
	}

	private static AccountCurrency parseCurrency(String currencyCode) {
		try {
			return AccountCurrency.fromCode(currencyCode);
		} catch (RuntimeException exception) {
			throw AccountBalanceException.persistence(exception);
		}
	}

	private static void validateHoldCurrency(
		AccountCurrency accountCurrency,
		LiquidityHoldBalanceReadPort.EffectiveHoldAmounts holds) {
		if (holds.currencyCount() == 0) {
			if (holds.currencyCode() != null) {
				throw AccountBalanceException.persistence(new IllegalStateException("流动性占用币种聚合无效。"));
			}
			return;
		}
		if (holds.currencyCount() != 1 || holds.currencyCode() == null
			|| parseCurrency(holds.currencyCode()) != accountCurrency) {
			throw AccountBalanceException.persistence(new IllegalStateException("账户与流动性占用币种不一致。"));
		}
	}

	private static BigDecimal exactAmount(BigDecimal amount, AccountCurrency currency) {
		if (amount == null) {
			throw AccountBalanceException.persistence(new IllegalStateException("余额金额事实无效。"));
		}
		try {
			int scale = currency == AccountCurrency.JPY ? 0 : 2;
			return amount.setScale(scale, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException exception) {
			throw AccountBalanceException.persistence(new IllegalStateException("余额金额精度无效。", exception));
		}
	}
}
