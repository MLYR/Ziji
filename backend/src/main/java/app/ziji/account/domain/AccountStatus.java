package app.ziji.account.domain;

/** 账户生命周期状态；归档规则由 archivedAt 配对约束。 */
public enum AccountStatus {
	ACTIVE,
	ARCHIVED
}
