package app.ziji.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 纯领域交易工厂：统一生成不可变 POSTED 聚合并复用 PostingService 校验。 */
public final class LedgerTransactionFactory {

	private final PostingService postingService;

	public LedgerTransactionFactory(PostingService postingService) {
		if (postingService == null) {
			throw new LedgerDomainException("入账校验服务不能为空。");
		}
		this.postingService = postingService;
	}

	public Transaction createPosted(
		UUID transactionId,
		TransactionType type,
		TransactionSource source,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		Instant postedAt,
		List<LedgerEntrySpec> specifications) {
		if (transactionId == null || type == null || source == null || businessAt == null
			|| businessDate == null || postedAt == null || specifications == null
			|| specifications.size() < 2) {
			throw new LedgerDomainException("入账交易参数不完整。");
		}
		List<LedgerEntry> entries = new ArrayList<>(specifications.size());
		int sequenceNo = 1;
		for (LedgerEntrySpec specification : specifications) {
			entries.add(new LedgerEntry(
				UUID.randomUUID(),
				transactionId,
				specification.ledgerAccountId(),
				sequenceNo++,
				specification.direction(),
				specification.amount(),
				businessDate));
		}
		Transaction transaction = new Transaction(
			transactionId,
			type,
			TransactionStatus.POSTED,
			businessAt,
			businessDate,
			timezone,
			source,
			transactionId,
			null,
			null,
			1,
			postedAt,
			entries);
		postingService.validate(transaction);
		return transaction;
	}
}
