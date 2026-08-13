package app.ziji.shared.infrastructure;

import java.util.function.Supplier;

import app.ziji.shared.application.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringTransactionRunner implements TransactionRunner {

	private final TransactionTemplate transactions;

	SpringTransactionRunner(TransactionTemplate transactions) {
		this.transactions = transactions;
	}

	@Override
	public <T> T required(Supplier<T> action) {
		// TransactionTemplate 保证事实写入和 outbox 后续可以共用同一事务边界。
		return transactions.execute(status -> action.get());
	}

	@Override
	public void required(Runnable action) {
		// 无返回值用例仍复用相同的 REQUIRED 传播语义。
		transactions.executeWithoutResult(status -> action.run());
	}
}
