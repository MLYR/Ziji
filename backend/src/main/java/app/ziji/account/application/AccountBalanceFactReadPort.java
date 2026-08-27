package app.ziji.account.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 账户余额读取的 Ledger 事实端口；历史 asOf 语义与归档用的当前余额端口明确分开。 */
public interface AccountBalanceFactReadPort {

	/** 账户模块对 PRIMARY 借贷性质的最小语义；避免跨模块暴露账户 domain 类型。 */
	enum PrimaryNature {
		ASSET,
		LIABILITY
	}

	Optional<AccountBalanceReadPort.PostedPrimaryBalance> findPostedPrimaryBalanceAt(
		UUID accountId, PrimaryNature primaryNature, Instant asOf);
}
