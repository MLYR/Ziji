package app.ziji.statistics.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 统计事实聚合端口；由 Ledger 基础设施在已入账事实之上聚合。
 * 桶边界由 application 按用户选择的粒度计算后传入；facts 只固化 business_date
 * （入账时按用户时区派生并冻结），用户修改时区不改变历史归属。
 */
public interface StatisticsFactReadPort {

	/**
	 * 每桶真实收支：income 为 INCOME nature 贷方合计，expense 为 EXPENSE nature 借方合计；
	 * 仅统计桶内 business_date 的已入账交易。granularity 取 DAY/WEEK/MONTH/YEAR。
	 */
	List<NatureBucket> natureBuckets(UUID userId, LocalDate dateFrom, LocalDate dateTo, String granularity);

	/** 每桶期末 PRIMARY 余额（累计至桶末日）；资产/投资借-贷为正，负债贷-借为正债务。 */
	List<AccountEndBalance> accountEndBalances(List<UUID> accountIds, List<LocalDate> bucketEnds);

	/** 每桶真实收支；bucketStart 为桶起始日。 */
	record NatureBucket(LocalDate bucketStart, BigDecimal income, BigDecimal expense) {
	}

	/** 每桶每账户期末余额。 */
	record AccountEndBalance(LocalDate bucketEnd, UUID accountId, BigDecimal balance) {
	}
}
