package app.ziji.ledger.application;

/** 当前用户能看到交易或账户，但 ACTIVE membership 角色不允许写入。 */
public final class LedgerPermissionDeniedException extends LedgerCommandValidationException {

	public LedgerPermissionDeniedException() {
		super("当前成员没有账务写入权限。");
	}
}
