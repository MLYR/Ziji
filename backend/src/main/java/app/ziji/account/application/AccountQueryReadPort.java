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

	/** 跨模块汇总读取的账户分类摘要；只暴露分类与币种，不把领域模型泄漏到其他模块。 */
	List<ClassSummary> listClassSummariesByIds(Collection<UUID> accountIds);

	record ClassSummary(UUID accountId, String accountClass, String currency) {

		public ClassSummary {
			if (accountId == null || accountClass == null || currency == null) {
				throw new IllegalArgumentException("账户分类摘要不完整。");
			}
		}
	}
}
