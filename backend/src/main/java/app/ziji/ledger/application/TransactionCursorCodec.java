package app.ziji.ledger.application;

import java.util.UUID;

/** 交易游标编码端口；游标载荷必须绑定用户、筛选条件、排序和 API operation。 */
public interface TransactionCursorCodec {

	String encode(UUID userId, TransactionQuery query, TransactionKeysetPosition position);

	TransactionKeysetPosition decode(UUID userId, TransactionQuery query, String cursor);
}
