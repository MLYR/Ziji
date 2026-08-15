package app.ziji.shared.application;

import java.util.function.Supplier;

/** 为应用层提供事务边界，避免领域代码依赖 Spring 事务 API。 */
public interface TransactionRunner {

	<T> T required(Supplier<T> action);

	void required(Runnable action);

	/**
	 * 仅在调用方已持有事务时建立数据库 savepoint；默认实现让纯 application 单元测试保持 REQUIRED 语义。
	 */
	default <T> T nested(Supplier<T> action) {
		return required(action);
	}

	default void nested(Runnable action) {
		required(action);
	}
}
