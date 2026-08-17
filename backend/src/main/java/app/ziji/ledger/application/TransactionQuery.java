package app.ziji.ledger.application;

import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.domain.TransactionType;

/** 交易列表的类型化筛选；HTTP 层不把内部科目 ID 暴露为查询入口。 */
public record TransactionQuery(
	UUID accountId,
	TransactionType type,
	LocalDate dateFrom,
	LocalDate dateTo,
	UUID categoryId) {

	public TransactionQuery {
		if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
			throw new TransactionQueryValidationException();
		}
	}
}
