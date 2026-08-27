package app.ziji.account.application;

import java.util.function.Supplier;

/** 账户余额读取的独立一致性事务边界；实现负责保证所有事实读取共享一个数据库快照。 */
public interface AccountBalanceSnapshotTransaction {

	<T> T read(Supplier<T> action);
}
