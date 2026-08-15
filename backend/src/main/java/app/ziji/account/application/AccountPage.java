package app.ziji.account.application;

import java.util.List;

/** 账户列表的稳定 keyset 分页结果；nextCursor 为 null 表示没有下一页。 */
public record AccountPage(List<AccountQueryResult> accounts, String nextCursor, boolean hasMore) {

	public AccountPage {
		accounts = List.copyOf(accounts);
	}
}
