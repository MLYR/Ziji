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
		return createPostedWithRelations(
			transactionId, type, source, businessAt, businessDate, timezone, postedAt,
			transactionId, null, null, 1, specifications);
	}

	/** 创建同一业务事实链中的后续已确认版本，不会修改前一版本。 */
	public Transaction createPostedVersion(
		UUID transactionId,
		TransactionType type,
		TransactionSource source,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		Instant postedAt,
		UUID rootTransactionId,
		UUID previousVersionId,
		int versionNo,
		List<LedgerEntrySpec> specifications) {
		return createPostedWithRelations(
			transactionId, type, source, businessAt, businessDate, timezone, postedAt,
			rootTransactionId, previousVersionId, null, versionNo, specifications);
	}

	/** 通过新交易中的反向分录抵消原已确认交易，原交易与原分录保持不变。 */
	public Transaction createReversal(Transaction original, UUID reversalTransactionId, Instant postedAt) {
		if (original == null || original.status() != TransactionStatus.POSTED
			|| original.type() == TransactionType.REVERSAL || reversalTransactionId == null || postedAt == null) {
			throw new LedgerDomainException("只能为非冲正的已确认交易创建冲正交易。");
		}
		List<LedgerEntrySpec> specifications = new ArrayList<>(original.entries().size());
		for (LedgerEntry entry : original.entries()) {
			specifications.add(new LedgerEntrySpec(
				entry.ledgerAccountId(),
				entry.direction() == LedgerDirection.DEBIT ? LedgerDirection.CREDIT : LedgerDirection.DEBIT,
				entry.amount()));
		}
		// 冲正本身是独立事实，使用自身作为 root，并通过 reversal_of_id 指向被抵消的交易。
		return createPostedWithRelations(
			reversalTransactionId, TransactionType.REVERSAL, original.source(), original.businessAt(),
			original.businessDate(), original.timezone().getId(), postedAt, reversalTransactionId,
			null, original.transactionId(), 1, specifications);
	}

	private Transaction createPostedWithRelations(
		UUID transactionId,
		TransactionType type,
		TransactionSource source,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		Instant postedAt,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
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
			rootTransactionId,
			previousVersionId,
			reversalOfId,
			versionNo,
			postedAt,
			entries);
		postingService.validate(transaction);
		return transaction;
	}
}
