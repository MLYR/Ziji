package app.ziji.ledger.domain;

import java.time.LocalDate;
import java.util.UUID;

/** 不可变账务分录；金额必须为绝对值，借贷方向由 LedgerDirection 表达。 */
public final class LedgerEntry {

	private final UUID entryId;
	private final UUID transactionId;
	private final UUID ledgerAccountId;
	private final int sequenceNo;
	private final LedgerDirection direction;
	private final Money amount;
	private final LocalDate businessDate;

	public LedgerEntry(
		UUID entryId,
		UUID transactionId,
		UUID ledgerAccountId,
		int sequenceNo,
		LedgerDirection direction,
		Money amount,
		LocalDate businessDate) {
		if (entryId == null || transactionId == null || ledgerAccountId == null) {
			throw new LedgerDomainException("分录标识不能为空。");
		}
		if (sequenceNo <= 0) {
			throw new LedgerDomainException("分录顺序必须大于零。");
		}
		if (direction == null) {
			throw new LedgerDomainException("分录方向不能为空。");
		}
		if (amount == null) {
			throw new LedgerDomainException("分录金额不能为空。");
		}
		if (amount.amount().signum() <= 0) {
			throw new LedgerDomainException("分录金额必须大于零。");
		}
		if (businessDate == null) {
			throw new LedgerDomainException("分录业务日期不能为空。");
		}
		this.entryId = entryId;
		this.transactionId = transactionId;
		this.ledgerAccountId = ledgerAccountId;
		this.sequenceNo = sequenceNo;
		this.direction = direction;
		this.amount = amount;
		this.businessDate = businessDate;
	}

	public UUID entryId() {
		return entryId;
	}

	public UUID transactionId() {
		return transactionId;
	}

	public UUID ledgerAccountId() {
		return ledgerAccountId;
	}

	public int sequenceNo() {
		return sequenceNo;
	}

	public LedgerDirection direction() {
		return direction;
	}

	public Money amount() {
		return amount;
	}

	public CurrencyCode currency() {
		return amount.currency();
	}

	public LocalDate businessDate() {
		return businessDate;
	}
}
