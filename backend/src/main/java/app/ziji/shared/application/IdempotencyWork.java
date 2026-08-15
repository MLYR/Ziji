package app.ziji.shared.application;

/** 幂等记录已在当前事务锁定后执行的业务工作；抛出的异常会连同 PROCESSING 一起回滚。 */
@FunctionalInterface
public interface IdempotencyWork<T> {

	IdempotencyWorkResult<T> execute();
}
