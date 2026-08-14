package app.ziji.ledger.domain;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 纯领域入账校验服务，只验证结构和逐币种借贷平衡，不访问外部资源。 */
public final class PostingService {

	/** 校验准备入账的交易，不创建持久化记录或数据库事务。 */
	public void validate(Transaction transaction) {
		if (transaction == null) {
			throw new LedgerDomainException("待入账交易不能为空。");
		}
		if (transaction.entries().size() < 2) {
			throw new LedgerDomainException("交易至少需要两条分录。");
		}

		Map<CurrencyCode, BigDecimal> debitTotals = new EnumMap<>(CurrencyCode.class);
		Map<CurrencyCode, BigDecimal> creditTotals = new EnumMap<>(CurrencyCode.class);
		Set<Integer> sequenceNumbers = new HashSet<>();
		Set<CurrencyCode> currencies = EnumSet.noneOf(CurrencyCode.class);
		for (LedgerEntry entry : transaction.entries()) {
			validateEntry(transaction, entry, sequenceNumbers);
			CurrencyCode currency = entry.currency();
			currencies.add(currency);
			Map<CurrencyCode, BigDecimal> totals = entry.direction() == LedgerDirection.DEBIT
				? debitTotals : creditTotals;
			totals.merge(currency, entry.amount().amount(), BigDecimal::add);
		}

		// 每个币种独立比较，禁止用汇率或跨币种总额掩盖原币不平衡。
		for (CurrencyCode currency : currencies) {
			BigDecimal debit = debitTotals.getOrDefault(currency, BigDecimal.ZERO);
			BigDecimal credit = creditTotals.getOrDefault(currency, BigDecimal.ZERO);
			if (debit.compareTo(credit) != 0) {
				throw new LedgerDomainException("交易在至少一个币种内借贷不平衡。");
			}
		}
	}

	private static void validateEntry(
		Transaction transaction,
		LedgerEntry entry,
		Set<Integer> sequenceNumbers) {
		if (entry == null) {
			throw new LedgerDomainException("交易不能包含空分录。");
		}
		if (!transaction.transactionId().equals(entry.transactionId())) {
			throw new LedgerDomainException("分录必须属于当前交易。");
		}
		if (!transaction.businessDate().equals(entry.businessDate())) {
			throw new LedgerDomainException("分录业务日期必须与交易一致。");
		}
		if (!sequenceNumbers.add(entry.sequenceNo())) {
			throw new LedgerDomainException("同一交易的分录顺序不能重复。");
		}
		if (entry.amount().amount().signum() <= 0) {
			throw new LedgerDomainException("分录金额必须大于零。");
		}
	}
}
