package app.ziji.account.infrastructure;

import java.util.function.Supplier;

import app.ziji.account.application.AccountBalanceSnapshotTransaction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 余额读取固定使用独立 PostgreSQL REPEATABLE READ 只读事务，避免公共写事务语义被改变。 */
@Component
public class SpringAccountBalanceSnapshotTransaction implements AccountBalanceSnapshotTransaction {

	private final TransactionTemplate snapshotTransaction;

	public SpringAccountBalanceSnapshotTransaction(PlatformTransactionManager transactionManager) {
		if (transactionManager == null) {
			throw new IllegalArgumentException("余额一致性事务管理器不能为空。");
		}
		this.snapshotTransaction = new TransactionTemplate(transactionManager);
		this.snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.snapshotTransaction.setReadOnly(true);
	}

	@Override
	public <T> T read(Supplier<T> action) {
		if (action == null) {
			throw new IllegalArgumentException("余额一致性读取动作不能为空。");
		}
		return snapshotTransaction.execute(status -> action.get());
	}
}
