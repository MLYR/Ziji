package app.ziji.account.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.Account;

/** 账户查询只读端口；只读取 accounts 聚合，membership 视角由 accountmember 公开端口提供。 */
public interface AccountQueryReadPort {

	Optional<Account> findById(UUID accountId);

	List<Account> listByIds(
		Collection<UUID> accountIds,
		AccountKeysetPosition after,
		int maximumRecords);

	/**
	 * 跨模块汇总读取的账户分类摘要；只暴露分类与币种，不把领域模型泄漏到其他模块。
	 * asOf 非空时按历史时点过滤：asOf 前创建且尚未归档的账户才参与统计。
	 * 保留 default 抛错以兼容既有测试替身，真实 PostgreSQL 实现必须覆盖。
	 */
	default List<ClassSummary> listClassSummariesByIds(Collection<UUID> accountIds, java.time.Instant asOf) {
		throw new UnsupportedOperationException("账户分类摘要读取未实现。");
	}

	record ClassSummary(UUID accountId, String accountClass, String currency) {

		public ClassSummary {
			if (accountId == null || accountClass == null || currency == null) {
				throw new IllegalArgumentException("账户分类摘要不完整。");
			}
		}
	}
}
