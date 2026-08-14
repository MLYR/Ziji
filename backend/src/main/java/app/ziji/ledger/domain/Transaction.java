package app.ziji.ledger.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Transaction 聚合根，冻结交易元数据并持有不可变分录集合。 */
public final class Transaction {

	private final UUID transactionId;
	private final TransactionType type;
	private final TransactionStatus status;
	private final Instant businessAt;
	private final LocalDate businessDate;
	private final ZoneId timezone;
	private final TransactionSource source;
	private final UUID rootTransactionId;
	private final UUID previousVersionId;
	private final UUID reversalOfId;
	private final int versionNo;
	private final Instant postedAt;
	private final List<LedgerEntry> entries;

	public Transaction(
		UUID transactionId,
		TransactionType type,
		TransactionStatus status,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		TransactionSource source,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
		Instant postedAt,
		List<LedgerEntry> entries) {
		if (transactionId == null || rootTransactionId == null) {
			throw new LedgerDomainException("交易标识不能为空。");
		}
		if (type == null || status == null || source == null) {
			throw new LedgerDomainException("交易类型、状态和来源不能为空。");
		}
		if (businessAt == null || businessDate == null) {
			throw new LedgerDomainException("交易业务时间和日期不能为空。");
		}
		if (timezone == null || timezone.isBlank()) {
			throw new LedgerDomainException("交易时区不能为空。");
		}
		// 统一保存经过 JDK 严格解析的时区，避免把非法标识带入领域模型。
		ZoneId parsedTimezone;
		try {
			parsedTimezone = ZoneId.of(timezone);
		} catch (DateTimeException exception) {
			throw new LedgerDomainException("交易时区格式无效。");
		}
		if (versionNo <= 0) {
			throw new LedgerDomainException("交易版本号必须大于零。");
		}
		validatePostedAt(status, postedAt);
		// 与 V003 一致：只有 REVERSAL 交易才能携带 reversalOfId。
		if (reversalOfId != null && type != TransactionType.REVERSAL) {
			throw new LedgerDomainException("非冲正交易不能关联 reversalOfId。");
		}
		// 与 V003 一致：第一版本不能携带 previousVersionId。
		if (versionNo <= 1 && previousVersionId != null) {
			throw new LedgerDomainException("第一版本交易不能关联 previousVersionId。");
		}
		if (entries == null) {
			throw new LedgerDomainException("交易分录集合不能为空。");
		}
		if (entries.isEmpty()) {
			throw new LedgerDomainException("交易至少需要一条分录。");
		}
		Set<Integer> sequenceNumbers = new HashSet<>();
		for (LedgerEntry entry : entries) {
			if (entry == null) {
				throw new LedgerDomainException("交易不能包含空分录。");
			}
			if (!transactionId.equals(entry.transactionId())) {
				throw new LedgerDomainException("分录必须属于当前交易。");
			}
			if (!businessDate.equals(entry.businessDate())) {
				throw new LedgerDomainException("分录业务日期必须与交易一致。");
			}
			if (!sequenceNumbers.add(entry.sequenceNo())) {
				throw new LedgerDomainException("同一交易的分录顺序不能重复。");
			}
		}
		this.transactionId = transactionId;
		this.type = type;
		this.status = status;
		this.businessAt = businessAt;
		this.businessDate = businessDate;
		this.timezone = parsedTimezone;
		this.source = source;
		this.rootTransactionId = rootTransactionId;
		this.previousVersionId = previousVersionId;
		this.reversalOfId = reversalOfId;
		this.versionNo = versionNo;
		this.postedAt = postedAt;
		this.entries = List.copyOf(entries);
	}

	private static void validatePostedAt(TransactionStatus status, Instant postedAt) {
		boolean requiresPostedAt = status == TransactionStatus.POSTED
			|| status == TransactionStatus.REVERSED
			|| status == TransactionStatus.SUPERSEDED;
		if (requiresPostedAt && postedAt == null) {
			throw new LedgerDomainException("已确认交易必须具有 postedAt。");
		}
		if (!requiresPostedAt && postedAt != null) {
			throw new LedgerDomainException("草稿或丢弃交易不能具有 postedAt。");
		}
	}

	public UUID transactionId() {
		return transactionId;
	}

	public TransactionType type() {
		return type;
	}

	public TransactionStatus status() {
		return status;
	}

	public Instant businessAt() {
		return businessAt;
	}

	public LocalDate businessDate() {
		return businessDate;
	}

	public ZoneId timezone() {
		return timezone;
	}

	public TransactionSource source() {
		return source;
	}

	public UUID rootTransactionId() {
		return rootTransactionId;
	}

	public UUID previousVersionId() {
		return previousVersionId;
	}

	public UUID reversalOfId() {
		return reversalOfId;
	}

	public int versionNo() {
		return versionNo;
	}

	public Instant postedAt() {
		return postedAt;
	}

	public List<LedgerEntry> entries() {
		return entries;
	}
}
