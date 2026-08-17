package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 已确认交易的替代事实载荷；原交易只会通过冲正和版本关系关闭。 */
public record RevisePostedTransactionCommand(
	UUID userId,
	UUID transactionId,
	UUID replacementTransactionId,
	int expectedEntityVersion,
	Instant businessAt,
	LocalDate businessDate,
	String timezone,
	String counterparty,
	String merchant,
	String note,
	String reason,
	TransactionRevisionDetails details) {

	public RevisePostedTransactionCommand {
		if (userId == null || transactionId == null || expectedEntityVersion <= 0 || businessAt == null
			|| businessDate == null || timezone == null || timezone.isBlank() || reason == null || reason.isBlank()
			|| details == null || transactionId.equals(replacementTransactionId)) {
			throw new LedgerCommandValidationException("交易修订命令不完整。");
		}
	}

	/** 既有内部/Sync 调用继续由 Ledger 生成替代 Transaction ID。 */
	public RevisePostedTransactionCommand(
		UUID userId,
		UUID transactionId,
		int expectedEntityVersion,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String counterparty,
		String merchant,
		String note,
		String reason,
		TransactionRevisionDetails details) {
		this(userId, transactionId, null, expectedEntityVersion, businessAt, businessDate, timezone,
			counterparty, merchant, note, reason, details);
	}
}
