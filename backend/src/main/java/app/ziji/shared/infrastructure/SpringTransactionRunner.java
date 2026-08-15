package app.ziji.shared.infrastructure;

import java.util.function.Supplier;

import app.ziji.shared.application.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringTransactionRunner implements TransactionRunner {

	private final TransactionTemplate transactions;
	private final TransactionTemplate nestedTransactions;

	SpringTransactionRunner(TransactionTemplate transactions, PlatformTransactionManager transactionManager) {
		this.transactions = transactions;
		this.nestedTransactions = new TransactionTemplate(transactionManager);
		this.nestedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
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

	@Override
	public <T> T nested(Supplier<T> action) {
		// 注册/重置的稳定失败只回滚业务子事务，外层幂等 FAILED_FINAL 仍可原子提交。
		return nestedTransactions.execute(status -> action.get());
	}

	@Override
	public void nested(Runnable action) {
		nestedTransactions.executeWithoutResult(status -> action.run());
	}
}
