package app.ziji.shared.application;

import java.util.function.Supplier;

/** 为应用层提供事务边界，避免领域代码依赖 Spring 事务 API。 */
public interface TransactionRunner {

	<T> T required(Supplier<T> action);

	void required(Runnable action);
}
