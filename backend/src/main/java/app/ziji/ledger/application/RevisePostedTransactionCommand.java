package app.ziji.ledger.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
	List<UUID> tagIds,
	TransactionRevisionDetails details) {

	public RevisePostedTransactionCommand {
		if (userId == null || transactionId == null || expectedEntityVersion <= 0 || businessAt == null
			|| businessDate == null || timezone == null || timezone.isBlank() || reason == null || reason.isBlank()
			|| details == null || transactionId.equals(replacementTransactionId)) {
			throw new LedgerCommandValidationException("交易修订命令不完整。");
		}
		if (tagIds == null) {
			throw new LedgerCommandValidationException("交易修订标签列表不能为空。");
		}
		tagIds = List.copyOf(tagIds);
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
			counterparty, merchant, note, reason, List.of(), details);
	}

	/** 现有内部/Sync 无标签调用继续可用；HTTP 修订可显式携带标签事实。 */
	public RevisePostedTransactionCommand(
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
		this(userId, transactionId, replacementTransactionId, expectedEntityVersion, businessAt, businessDate,
			timezone, counterparty, merchant, note, reason, List.of(), details);
	}
}
