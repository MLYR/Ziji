package app.ziji.statistics.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountBalanceFactReadPort;
import app.ziji.account.application.AccountBalanceFactReadPort.PrimaryNature;
import app.ziji.account.application.AccountBalanceSnapshotTransaction;
import app.ziji.account.application.LiquidityHoldBalanceReadPort;
import app.ziji.account.application.LiquidityHoldBalanceReadPort.EffectiveHoldAmounts;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;

/**
 * Dashboard 核心指标：资产、可用资金、投资资产、负债和净资产。
 * 当前指标直接从已入账事实按共同数据库快照实时重建，与当日快照等价；
 * 历史日期投影和 valuationRevision 修订链由后续统计任务承担。
 * 缺失汇率的非基准币种账户绝不按 0 或 1 静默折算，必须以质量告警显式排除。
 */
public class DashboardApplicationService implements DashboardQueryUseCase {

	private static final String WARNING_MISSING_EXCHANGE_RATES = "MISSING_EXCHANGE_RATES";

	private final AccountMembershipReadPort memberships;
	private final AccountQueryPort accounts;
	private final AccountBalanceFactReadPort ledgerBalances;
	private final LiquidityHoldBalanceReadPort holdBalances;
	private final CurrentUserBaseCurrencyPort baseCurrencies;
	private final ChangeSequenceReadPort changeSequences;
	private final AccountBalanceSnapshotTransaction snapshots;
	private final Clock clock;

	/** 账户摘要直接复用 account 公开端口的 ClassSummary，不引入 account 领域类型。 */
	public interface AccountQueryPort {

		List<AccountQueryReadPort.ClassSummary> listActiveByIds(List<UUID> accountIds);
	}

	public DashboardApplicationService(
		AccountMembershipReadPort memberships,
		AccountQueryPort accounts,
		AccountBalanceFactReadPort ledgerBalances,
		LiquidityHoldBalanceReadPort holdBalances,
		CurrentUserBaseCurrencyPort baseCurrencies,
		ChangeSequenceReadPort changeSequences,
		AccountBalanceSnapshotTransaction snapshots,
		Clock clock) {
		if (memberships == null || accounts == null || ledgerBalances == null || holdBalances == null
			|| baseCurrencies == null || changeSequences == null || snapshots == null || clock == null) {
			throw new IllegalArgumentException("Dashboard 读取依赖不能为空。");
		}
		this.memberships = memberships;
		this.accounts = accounts;
		this.ledgerBalances = ledgerBalances;
		this.holdBalances = holdBalances;
		this.baseCurrencies = baseCurrencies;
		this.changeSequences = changeSequences;
		this.snapshots = snapshots;
		this.clock = clock;
	}

	@Override
	public DashboardResult getDashboard(UUID userId) {
		if (userId == null) {
			throw new DashboardValidationException("Dashboard 需要当前用户。");
		}
		// 全部事实读取共享一个数据库快照，避免余额与占用聚合读到不同时刻的事实。
		return snapshots.read(() -> {
			Instant asOf = clock.instant();
			String baseCurrency = baseCurrencies.currentBaseCurrency(userId);
			CurrencyCode base = CurrencyCode.fromCode(baseCurrency);

			List<UUID> includedAccountIds = new ArrayList<>();
			for (ActiveMembership membership : memberships.listActiveMemberships(userId)) {
				// 计入比例为 0 表示该账户明确不计入当前主体统计。
				if (membership.inclusionRatio().signum() > 0) {
					includedAccountIds.add(membership.accountId());
				}
			}
			List<AccountQueryReadPort.ClassSummary> accounts = includedAccountIds.isEmpty()
				? List.of() : this.accounts.listActiveByIds(includedAccountIds);

			BigDecimal totalAssets = BigDecimal.ZERO;
			BigDecimal availableFunds = BigDecimal.ZERO;
			BigDecimal investmentAssets = BigDecimal.ZERO;
			BigDecimal totalLiabilities = BigDecimal.ZERO;
			BigDecimal brokerCash = BigDecimal.ZERO;
			int missingRateCount = 0;

			for (AccountQueryReadPort.ClassSummary account : accounts) {
				// 非基准币种在 B1 无汇率事实：显式排除并告警，不按 0 或 1 静默折算。
				if (!base.name().equals(account.currency())) {
					missingRateCount++;
					continue;
				}
				// 与账户余额 API 同语义：PRIMARY 已入账余额按 asOf 的业务日期截止；
				// 资产/投资 nature 为 ASSET（借-贷为正），负债 nature 为 LIABILITY（贷-借为正债务）。
				PrimaryNature nature = "LIABILITY".equals(account.accountClass())
					? PrimaryNature.LIABILITY : PrimaryNature.ASSET;
				BigDecimal balance = ledgerBalances.findPostedPrimaryBalanceAt(account.accountId(), nature, asOf)
					.map(app.ziji.account.application.AccountBalanceReadPort.PostedPrimaryBalance::amount)
					.orElseThrow(() -> new IllegalStateException("账户 PRIMARY 余额事实缺失。"));
				switch (account.accountClass()) {
					case "INVESTMENT" -> {
						totalAssets = totalAssets.add(balance);
						investmentAssets = investmentAssets.add(balance);
						brokerCash = brokerCash.add(balance);
					}
					case "LIABILITY" -> totalLiabilities = totalLiabilities.add(balance);
					default -> {
						EffectiveHoldAmounts holds = holdBalances.sumEffectiveAt(account.accountId(), asOf);
						// 单行金额精度与占用币种一致性 fail closed，不能带着脏聚合继续汇总。
						if (holds.precisionErrorCount() != 0 || holds.currencyCount() > 1) {
							throw new IllegalStateException("流动性占用聚合事实无效。");
						}
						// 可用余额 = 账面余额 − 不可用金额；负可用余额是合法事实，原样计入。
						BigDecimal unavailable = nz(holds.frozen()).add(nz(holds.inTransit())).add(nz(holds.reserved()));
						totalAssets = totalAssets.add(balance);
						availableFunds = availableFunds.add(balance.subtract(unavailable));
					}
				}
			}

			BigDecimal netAssets = totalAssets.subtract(totalLiabilities);
			int scale = base.minorUnits();
			DashboardResult.Summary summary = new DashboardResult.Summary(
				scale(totalAssets, scale), scale(availableFunds, scale), scale(investmentAssets, scale),
				scale(totalLiabilities, scale), scale(netAssets, scale));
			DashboardResult.Attribution attribution = new DashboardResult.Attribution(
				BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale),
				BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale));
			DashboardResult.InvestmentOverview investmentOverview = new DashboardResult.InvestmentOverview(
				baseCurrency, scale(brokerCash, scale), BigDecimal.ZERO.setScale(scale),
				scale(investmentAssets, scale), 0);
			List<DashboardResult.QualityWarning> warnings = new ArrayList<>();
			if (missingRateCount > 0) {
				warnings.add(new DashboardResult.QualityWarning(WARNING_MISSING_EXCHANGE_RATES, missingRateCount));
			}
			return new DashboardResult(
				baseCurrency, asOf, changeSequences.latestSequence(userId), 1, asOf, "CURRENT",
				summary, attribution, List.of(), investmentOverview, warnings);
		});
	}

	private static BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static BigDecimal scale(BigDecimal value, int scale) {
		return value.setScale(scale, java.math.RoundingMode.HALF_UP);
	}
}
