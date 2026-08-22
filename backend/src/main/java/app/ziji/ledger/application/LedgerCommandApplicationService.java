package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.audit.application.AuditLogWritePort;
import app.ziji.category.application.CategoryReference;
import app.ziji.category.application.CategoryStore;
import app.ziji.category.application.CategoryType;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerAccountNature;
import app.ziji.ledger.domain.LedgerAccountReference;
import app.ziji.ledger.domain.LedgerAccountRole;
import app.ziji.ledger.domain.LedgerDirection;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.LedgerEntrySpec;
import app.ziji.ledger.domain.LedgerTransactionFactory;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.PostingService;
import app.ziji.ledger.domain.Transaction;
import app.ziji.ledger.domain.TransactionSource;
import app.ziji.ledger.domain.TransactionStatus;
import app.ziji.ledger.domain.TransactionType;
import app.ziji.shared.application.TransactionRunner;

/** 账务语义命令的应用编排；不包含 HTTP、Spring、jOOQ 或余额投影。 */
public final class LedgerCommandApplicationService implements LedgerSyncCommandPort {

	private final TransactionRunner transactions;
	private final AccountPostingReferencePort accounts;
	private final AccountPostingAccessPort accountAccess;
	private final CategoryStore categories;
	private final LedgerAccountStore ledgerAccounts;
	private final LedgerTransactionStore ledgerTransactions;
	private final AuditLogWritePort auditLogs;
	private final LedgerOutbox ledgerOutbox;
	private final LedgerRequestIdProvider requestIds;
	private final LedgerTransactionFactory transactionFactory;
	private final Clock clock;

	public LedgerCommandApplicationService(
		TransactionRunner transactions,
		AccountPostingReferencePort accounts,
		AccountPostingAccessPort accountAccess,
		CategoryStore categories,
		LedgerAccountStore ledgerAccounts,
		LedgerTransactionStore ledgerTransactions,
		AuditLogWritePort auditLogs,
		LedgerOutbox ledgerOutbox,
		LedgerRequestIdProvider requestIds,
		PostingService postingService,
		Clock clock) {
		if (transactions == null || accounts == null || accountAccess == null || categories == null
			|| ledgerAccounts == null || ledgerTransactions == null || auditLogs == null || ledgerOutbox == null
			|| requestIds == null || postingService == null || clock == null) {
			throw new LedgerCommandValidationException("账务命令服务依赖不能为空。");
		}
		this.transactions = transactions;
		this.accounts = accounts;
		this.accountAccess = accountAccess;
		this.categories = categories;
		this.ledgerAccounts = ledgerAccounts;
		this.ledgerTransactions = ledgerTransactions;
		this.auditLogs = auditLogs;
		this.ledgerOutbox = ledgerOutbox;
		this.requestIds = requestIds;
		this.transactionFactory = new LedgerTransactionFactory(postingService);
		this.clock = clock;
	}

	public Transaction postIncome(IncomeCommand command) {
		return postIncome(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	/** 公共初次创建可携带客户端 Transaction UUID；缺失时仍由 Ledger 生成。 */
	public Transaction postIncome(IncomeCommand command, UUID transactionId) {
		return postIncome(command, initialTransactionId(transactionId), TransactionSource.MANUAL);
	}

	/**
	 * 仅由账户创建端口调用的内部 OPENING 入账；分录和权益科目始终由 Ledger 决定，不能暴露给公共交易接口。
	 */
	public UUID postOpening(
		UUID accountId,
		String accountClass,
		String currencyCode,
		UUID createdBy,
		AccountOpeningBalance openingBalance,
		ZoneId timezone) {
		if (accountId == null || accountClass == null || currencyCode == null || createdBy == null
			|| openingBalance == null || timezone == null) {
			throw invalid("期初余额入账参数无效。");
		}
		return transactions.required(() -> {
			CurrencyCode currency = CurrencyCode.fromCode(currencyCode);
			Money amount = new Money(openingBalance.amount(), currency);
			validateAmount(amount, currency);
			LedgerAccountReference primary = openingPrimary(accountId, accountClass, currency);
			LedgerAccountReference equity = ledgerAccounts.ensureOpeningEquityAccount(createdBy, currency);
			if (equity.role() != LedgerAccountRole.SYSTEM || equity.nature() != LedgerAccountNature.EQUITY
				|| !"EQUITY_OPENING_BALANCE".equals(equity.code()) || !equity.active()) {
				throw invalid("期初权益科目状态无效。");
			}
			LocalDate businessDate = openingBalance.businessAt().atZone(timezone).toLocalDate();
			boolean liability = "LIABILITY".equals(accountClass);
			// 正债务以贷 PRIMARY 入账；资产和投资期初现金使用相反方向，投资绝不触碰 POSITION_COST。
			LedgerDirection primaryDirection = liability ? LedgerDirection.CREDIT : LedgerDirection.DEBIT;
			LedgerDirection equityDirection = liability ? LedgerDirection.DEBIT : LedgerDirection.CREDIT;
			Transaction transaction = transactionFactory.createPosted(
				UUID.randomUUID(), TransactionType.OPENING, TransactionSource.MANUAL,
				openingBalance.businessAt(), businessDate, timezone.getId(), clock.instant(),
				List.of(
					new LedgerEntrySpec(primary.id(), primaryDirection, amount),
					new LedgerEntrySpec(equity.id(), equityDirection, amount)));
			completeInitialPosting(new PostedTransactionWrite(
				transaction, createdBy, null, null, openingBalance.note(), null, new NoTransactionDetails()));
			return transaction.transactionId();
		});
	}

	private Transaction postIncome(IncomeCommand command, UUID transactionId, TransactionSource source) {
		require(command, "收入命令");
		// 公共写接口外层还要原子保存幂等终态；预期业务拒绝只回滚当前账务 savepoint。
		return transactions.nested(() -> postIncomeInTransaction(command, transactionId, source));
	}

	private Transaction postIncomeInTransaction(IncomeCommand command, UUID transactionId, TransactionSource source) {
		AccountPostingReference account = editableAccount(command.userId(), command.accountId());
		LedgerAccountReference accountLedger = primary(account);
		requireAssetAccount(account, "收入");
		validateAmount(command.amount(), accountLedger.currency());
		requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.INCOME);
		LedgerAccountReference incomeLedger = command.incomeLedgerAccountId() == null
			? ensureCategoryCounter(command.userId(), command.categoryId(), LedgerAccountNature.INCOME, accountLedger.currency())
			: requireSystem(command.incomeLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.INCOME);

		Transaction transaction = transactionFactory.createPosted(
			transactionId,
			TransactionType.INCOME,
			source,
			command.businessAt(),
			command.businessDate(),
			command.timezone(),
			clock.instant(),
			List.of(
				new LedgerEntrySpec(accountLedger.id(), LedgerDirection.DEBIT, command.amount()),
				new LedgerEntrySpec(incomeLedger.id(), LedgerDirection.CREDIT, command.amount())));
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), command.counterparty(), null, command.note(),
			command.categoryId(), new NoTransactionDetails()));
		return transaction;
	}

	public Transaction postExpense(ExpenseCommand command) {
		return postExpense(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	public Transaction postExpense(ExpenseCommand command, UUID transactionId) {
		return postExpense(command, initialTransactionId(transactionId), TransactionSource.MANUAL);
	}

	private Transaction postExpense(ExpenseCommand command, UUID transactionId, TransactionSource source) {
		require(command, "支出命令");
		return transactions.nested(() -> postExpenseInTransaction(command, transactionId, source));
	}

	private Transaction postExpenseInTransaction(ExpenseCommand command, UUID transactionId, TransactionSource source) {
		AccountPostingReference account = editableAccount(command.userId(), command.accountId());
		LedgerAccountReference accountLedger = primary(account);
		if ("LIABILITY".equals(account.accountClass())) {
			if (!"CREDIT_CARD".equals(account.accountType())) {
				throw invalid("只有信用卡负债账户可以使用支出语义。");
			}
		} else {
			requireAssetAccount(account, "支出");
		}
		validateAmount(command.amount(), accountLedger.currency());
		requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.EXPENSE);
		LedgerAccountReference expenseLedger = command.expenseLedgerAccountId() == null
			? ensureCategoryCounter(command.userId(), command.categoryId(), LedgerAccountNature.EXPENSE, accountLedger.currency())
			: requireSystem(command.expenseLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EXPENSE);

		Transaction transaction = transactionFactory.createPosted(
			transactionId,
			TransactionType.EXPENSE,
			source,
			command.businessAt(),
			command.businessDate(),
			command.timezone(),
			clock.instant(),
			List.of(
				new LedgerEntrySpec(expenseLedger.id(), LedgerDirection.DEBIT, command.amount()),
				new LedgerEntrySpec(accountLedger.id(), LedgerDirection.CREDIT, command.amount())));
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), null, command.merchant(), command.note(),
			command.categoryId(), new NoTransactionDetails()));
		return transaction;
	}

	/** 借款到账固定为资产借、负债贷；不走普通收入或可见转账的账户类别捷径。 */
	public Transaction postLiabilityBorrowing(LiabilityBorrowingCommand command) {
		return postLiabilityBorrowing(command, UUID.randomUUID());
	}

	public Transaction postLiabilityBorrowing(LiabilityBorrowingCommand command, UUID transactionId) {
		require(command, "借款到账命令");
		return transactions.nested(() -> {
			lockAccounts(command.assetAccountId(), command.liabilityAccountId());
			AccountPostingReference asset = editableAccount(command.userId(), command.assetAccountId());
			AccountPostingReference liability = editableAccount(command.userId(), command.liabilityAccountId());
			requireExactAccountClass(asset, "ASSET", "借款到账");
			requireExactAccountClass(liability, "LIABILITY", "借款到账");
			if (!Set.of("LOAN", "CONSUMER_LOAN", "OTHER").contains(liability.accountType())) {
				throw invalid("借款到账不允许使用信用卡账户。");
			}
			LedgerAccountReference assetLedger = primary(asset);
			LedgerAccountReference liabilityLedger = primary(liability);
			if (assetLedger.currency() != liabilityLedger.currency()) {
				throw invalid("借款到账两账户必须使用同一币种。");
			}
			validateAmount(command.amount(), assetLedger.currency());
			Transaction transaction = transactionFactory.createPosted(
				initialTransactionId(transactionId), TransactionType.TRANSFER, TransactionSource.MANUAL,
				command.businessAt(), command.businessDate(), command.timezone(), clock.instant(),
				List.of(
					new LedgerEntrySpec(assetLedger.id(), LedgerDirection.DEBIT, command.amount()),
					new LedgerEntrySpec(liabilityLedger.id(), LedgerDirection.CREDIT, command.amount())));
			// 复用既有 transfer_details 作为本金来源/去向事实，不制造第二张借款表。
			completeInitialPosting(new PostedTransactionWrite(
				transaction, command.userId(), null, null, command.note(), null,
				new LiabilityBorrowingWriteDetails(
					command.assetAccountId(), command.liabilityAccountId(), command.amount())));
			return transaction;
		});
	}

	/** 还款本金不计支出，利息和手续费各自借费用系统科目并计入支出。 */
	public Transaction postLiabilityRepayment(LiabilityRepaymentCommand command) {
		return postLiabilityRepayment(command, UUID.randomUUID());
	}

	public Transaction postLiabilityRepayment(LiabilityRepaymentCommand command, UUID transactionId) {
		require(command, "负债还款命令");
		return transactions.nested(() -> {
			lockAccounts(command.cashAccountId(), command.liabilityAccountId());
			AccountPostingReference cash = editableAccount(command.userId(), command.cashAccountId());
			AccountPostingReference liability = editableAccount(command.userId(), command.liabilityAccountId());
			requireExactAccountClass(cash, "ASSET", "负债还款");
			requireExactAccountClass(liability, "LIABILITY", "负债还款");
			LedgerAccountReference cashLedger = primary(cash);
			LedgerAccountReference liabilityLedger = primary(liability);
			if (cashLedger.currency() != liabilityLedger.currency()) {
				throw invalid("负债还款两账户必须使用同一币种。");
			}
			CurrencyCode currency = cashLedger.currency();
			validateAmount(command.principalAmount(), currency);
			validateNonNegativeAmount(command.interestAmount(), currency, "利息");
			validateNonNegativeAmount(command.feeAmount(), currency, "手续费");
			if (command.interestAmount().amount().signum() > 0) {
				requireCategoryForAccounts(command.interestCategoryId(), command.userId(), CategoryType.EXPENSE,
					command.cashAccountId(), command.liabilityAccountId());
			} else if (command.interestCategoryId() != null) {
				throw invalid("利息为零时不能提供利息分类。");
			}
			if (command.feeAmount().amount().signum() > 0) {
				requireCategoryForAccounts(command.feeCategoryId(), command.userId(), CategoryType.EXPENSE,
					command.cashAccountId(), command.liabilityAccountId());
			} else if (command.feeCategoryId() != null) {
				throw invalid("手续费为零时不能提供手续费分类。");
			}

			List<LedgerEntrySpec> entries = new ArrayList<>();
			entries.add(new LedgerEntrySpec(liabilityLedger.id(), LedgerDirection.DEBIT, command.principalAmount()));
			entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, command.principalAmount()));
			if (command.interestAmount().amount().signum() > 0) {
				LedgerAccountReference interestLedger = ensureCategoryCounter(
					command.userId(), command.interestCategoryId(), LedgerAccountNature.EXPENSE, currency);
				entries.add(new LedgerEntrySpec(interestLedger.id(), LedgerDirection.DEBIT, command.interestAmount()));
				entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, command.interestAmount()));
			}
			if (command.feeAmount().amount().signum() > 0) {
				LedgerAccountReference feeLedger = ensureCategoryCounter(
					command.userId(), command.feeCategoryId(), LedgerAccountNature.EXPENSE, currency);
				entries.add(new LedgerEntrySpec(feeLedger.id(), LedgerDirection.DEBIT, command.feeAmount()));
				entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, command.feeAmount()));
			}
			Transaction transaction = transactionFactory.createPosted(
				initialTransactionId(transactionId), TransactionType.REPAYMENT, TransactionSource.MANUAL,
				command.businessAt(), command.businessDate(), command.timezone(), clock.instant(), entries);
			completeInitialPosting(new PostedTransactionWrite(
				transaction, command.userId(), null, null, command.note(), null,
				new RepaymentWriteDetails(command.liabilityAccountId(), command.cashAccountId(),
					command.principalAmount(), command.interestAmount(), command.feeAmount(),
					command.interestCategoryId(), command.feeCategoryId())));
			return transaction;
		});
	}

	public Transaction postRefund(RefundCommand command) {
		return postRefund(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	public Transaction postRefund(RefundCommand command, UUID transactionId) {
		return postRefund(command, initialTransactionId(transactionId), TransactionSource.MANUAL);
	}

	private Transaction postRefund(RefundCommand command, UUID transactionId, TransactionSource source) {
		require(command, "退款命令");
		return transactions.nested(() -> postRefundInTransaction(command, transactionId, source));
	}

	private Transaction postRefundInTransaction(RefundCommand command, UUID transactionId, TransactionSource source) {
		LedgerTransactionStore.RefundCandidate original = ledgerTransactions
			.findRefundCandidate(command.originalTransactionId())
			.orElseThrow(() -> invalid("原支出交易不存在或不可退款。"));
		lockAccounts(command.accountId(), original.originalAccountId());
		AccountPostingReference refundAccount = editableAccount(command.userId(), command.accountId());
		LedgerAccountReference refundLedger = primary(refundAccount);
		requireAssetAccount(refundAccount, "退款");
		validateAmount(command.amount(), refundLedger.currency());
		requireWritableAccount(command.userId(), original.originalAccountId());
		if (!original.originalAmount().currency().equals(refundLedger.currency())
			|| !original.refundedAmount().currency().equals(refundLedger.currency())) {
			throw invalid("退款币种必须与原支出一致。");
		}
		Money remaining = new Money(
			original.originalAmount().amount().subtract(original.refundedAmount().amount()),
			refundLedger.currency());
		if (command.amount().compareTo(remaining) > 0) {
			throw invalid("退款金额超过原支出可退款余额。");
		}
		if (original.categoryId() == null) {
			throw invalid("原支出缺少可继承的支出分类。");
		}
		requireCategory(original.categoryId(), command.userId(), original.originalAccountId(), CategoryType.EXPENSE);
		Transaction transaction = transactionFactory.createPosted(
			transactionId,
			TransactionType.REFUND,
			source,
			command.businessAt(),
			command.businessDate(),
			command.timezone(),
			clock.instant(),
			List.of(
				new LedgerEntrySpec(refundLedger.id(), LedgerDirection.DEBIT, command.amount()),
				new LedgerEntrySpec(original.expenseLedgerAccountId(), LedgerDirection.CREDIT, command.amount())));
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), null, null, command.note(), null,
			new RefundWriteDetails(command.originalTransactionId(), original.categoryId())));
		return transaction;
	}

	public Transaction postTransfer(TransferCommand command) {
		return postTransfer(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	public Transaction postTransfer(TransferCommand command, UUID transactionId) {
		return postTransfer(command, initialTransactionId(transactionId), TransactionSource.MANUAL);
	}

	private Transaction postTransfer(TransferCommand command, UUID transactionId, TransactionSource source) {
		require(command, "转账命令");
		return transactions.nested(() -> postTransferInTransaction(command, transactionId, source));
	}

	private Transaction postTransferInTransaction(TransferCommand command, UUID transactionId, TransactionSource source) {
		lockAccounts(command.fromAccountId(), command.toAccountId());
		AccountPostingReference from = editableAccount(command.userId(), command.fromAccountId());
		AccountPostingReference to = editableAccount(command.userId(), command.toAccountId());
		requireAssetAccount(from, "转账");
		requireAssetAccount(to, "转账");
		LedgerAccountReference fromLedger = primary(from);
		LedgerAccountReference toLedger = primary(to);
		if (fromLedger.currency() != toLedger.currency()) {
			throw invalid("本任务只允许同币种转账。");
		}
		validateAmount(command.amount(), fromLedger.currency());
		Money fee = command.feeAmount() == null
			? new Money(BigDecimal.ZERO, fromLedger.currency()) : command.feeAmount();
		validateFee(fee, fromLedger.currency());
		List<LedgerEntrySpec> entries = new ArrayList<>(List.of(
			new LedgerEntrySpec(toLedger.id(), LedgerDirection.DEBIT, command.amount()),
			new LedgerEntrySpec(fromLedger.id(), LedgerDirection.CREDIT, command.amount())));
		UUID categoryId = null;
		if (fee.amount().signum() > 0) {
			requireCategory(command.feeCategoryId(), command.userId(), command.fromAccountId(), CategoryType.EXPENSE);
			LedgerAccountReference feeLedger = command.feeLedgerAccountId() == null
				? ensureCategoryCounter(command.userId(), command.feeCategoryId(), LedgerAccountNature.EXPENSE, fromLedger.currency())
				: requireSystem(command.feeLedgerAccountId(), command.userId(), fromLedger.currency(), LedgerAccountNature.EXPENSE);
			categoryId = command.feeCategoryId();
			entries.add(new LedgerEntrySpec(feeLedger.id(), LedgerDirection.DEBIT, fee));
			entries.add(new LedgerEntrySpec(fromLedger.id(), LedgerDirection.CREDIT, fee));
		} else if (command.feeCategoryId() != null || command.feeLedgerAccountId() != null) {
			throw invalid("无手续费时不能提供手续费科目或分类。");
		}
		Transaction transaction = transactionFactory.createPosted(
			transactionId,
			TransactionType.TRANSFER,
			source,
			command.businessAt(),
			command.businessDate(),
			command.timezone(),
			clock.instant(),
			entries);
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), null, null, command.note(), categoryId,
			new TransferWriteDetails(
				command.fromAccountId(), command.toAccountId(), command.amount(), command.amount(), fee)));
		return transaction;
	}

	/**
	 * Sync 只能提交业务语义；系统对方科目在 Ledger 事务内按分类惰性确保，不能泄漏到接口层。
	 */
	public SyncLedgerResult applySync(SyncLedgerCommand command) {
		require(command, "同步账务命令");
		// 预期拒绝只回滚本操作 savepoint，外层统一幂等终态仍须与该拒绝原子提交。
		return transactions.nested(() -> switch (command) {
			case SyncLedgerCommand.Income income -> {
				LedgerAccountReference counter = ensureCategoryCounter(
					income.userId(), income.categoryId(), LedgerAccountNature.INCOME, income.amount().currency());
				Transaction posted = postIncomeInTransaction(new IncomeCommand(
					income.userId(), income.accountId(), counter.id(), income.categoryId(), income.amount(),
					income.businessAt(), income.businessDate(), income.timezone(), income.counterparty(), income.note()),
					income.transactionId(), TransactionSource.SYNC);
				yield new SyncLedgerResult(posted.transactionId(), 1);
			}
			case SyncLedgerCommand.Expense expense -> {
				LedgerAccountReference counter = ensureCategoryCounter(
					expense.userId(), expense.categoryId(), LedgerAccountNature.EXPENSE, expense.amount().currency());
				Transaction posted = postExpenseInTransaction(new ExpenseCommand(
					expense.userId(), expense.accountId(), counter.id(), expense.categoryId(), expense.amount(),
					expense.businessAt(), expense.businessDate(), expense.timezone(), expense.merchant(), expense.note()),
					expense.transactionId(), TransactionSource.SYNC);
				yield new SyncLedgerResult(posted.transactionId(), 1);
			}
			case SyncLedgerCommand.Refund refund -> {
				Transaction posted = postRefundInTransaction(new RefundCommand(
					refund.userId(), refund.accountId(), refund.originalTransactionId(), refund.amount(), refund.businessAt(),
					refund.businessDate(), refund.timezone(), refund.note()), refund.transactionId(), TransactionSource.SYNC);
				yield new SyncLedgerResult(posted.transactionId(), 1);
			}
			case SyncLedgerCommand.Transfer transfer -> {
				UUID feeLedgerId = transfer.feeAmount().amount().signum() == 0 ? null : ensureCategoryCounter(
					transfer.userId(), transfer.feeCategoryId(), LedgerAccountNature.EXPENSE, transfer.feeAmount().currency()).id();
				Transaction posted = postTransferInTransaction(new TransferCommand(
					transfer.userId(), transfer.fromAccountId(), transfer.toAccountId(), feeLedgerId, transfer.feeCategoryId(),
					transfer.amount(), transfer.feeAmount(), transfer.businessAt(), transfer.businessDate(), transfer.timezone(), transfer.note()),
					transfer.transactionId(), TransactionSource.SYNC);
				yield new SyncLedgerResult(posted.transactionId(), 1);
			}
			case SyncLedgerCommand.Revision revision -> {
				TransactionRevisionResult revised = revisePostedTransactionInTransaction(toSyncRevision(revision));
				yield new SyncLedgerResult(revised.replacement().transactionId(), 1);
			}
			case SyncLedgerCommand.Reverse reverse -> {
				TransactionVoidResult reversed = voidPostedTransactionInTransaction(new VoidPostedTransactionCommand(
					reverse.userId(), reverse.transactionId(), reverse.expectedEntityVersion(), reverse.reason()));
				yield new SyncLedgerResult(reversed.reversal().transactionId(), 1);
			}
		});
	}

	private RevisePostedTransactionCommand toSyncRevision(SyncLedgerCommand.Revision revision) {
		SyncLedgerCommand.Replacement replacement = revision.replacement();
		return switch (replacement) {
			case SyncLedgerCommand.Replacement.Income income -> new RevisePostedTransactionCommand(
				revision.userId(), revision.transactionId(), revision.expectedEntityVersion(), income.businessAt(), income.businessDate(),
				income.timezone(), income.counterparty(), null, income.note(), revision.reason(),
				new TransactionRevisionDetails.Income(income.accountId(), income.amount(), ensureCategoryCounter(
					revision.userId(), income.categoryId(), LedgerAccountNature.INCOME, income.amount().currency()).id(), income.categoryId()));
			case SyncLedgerCommand.Replacement.Expense expense -> new RevisePostedTransactionCommand(
				revision.userId(), revision.transactionId(), revision.expectedEntityVersion(), expense.businessAt(), expense.businessDate(),
				expense.timezone(), null, expense.merchant(), expense.note(), revision.reason(),
				new TransactionRevisionDetails.Expense(expense.accountId(), expense.amount(), ensureCategoryCounter(
					revision.userId(), expense.categoryId(), LedgerAccountNature.EXPENSE, expense.amount().currency()).id(), expense.categoryId()));
			case SyncLedgerCommand.Replacement.Refund refund -> new RevisePostedTransactionCommand(
				revision.userId(), revision.transactionId(), revision.expectedEntityVersion(), refund.businessAt(), refund.businessDate(),
				refund.timezone(), null, null, refund.note(), revision.reason(),
				new TransactionRevisionDetails.Refund(refund.accountId(), refund.originalTransactionId(), refund.amount()));
			case SyncLedgerCommand.Replacement.Transfer transfer -> new RevisePostedTransactionCommand(
				revision.userId(), revision.transactionId(), revision.expectedEntityVersion(), transfer.businessAt(), transfer.businessDate(),
				transfer.timezone(), null, null, transfer.note(), revision.reason(),
				new TransactionRevisionDetails.Transfer(transfer.fromAccountId(), transfer.toAccountId(),
					transfer.feeAmount().amount().signum() == 0 ? null : ensureCategoryCounter(
						revision.userId(), transfer.feeCategoryId(), LedgerAccountNature.EXPENSE, transfer.feeAmount().currency()).id(),
					transfer.feeCategoryId(), transfer.amount(), transfer.feeAmount()));
		};
	}

	private LedgerAccountReference ensureCategoryCounter(
		UUID userId, UUID categoryId, LedgerAccountNature nature, CurrencyCode currency) {
		if (userId == null || categoryId == null || currency == null) {
			throw invalid("分类系统科目参数无效。");
		}
		return ledgerAccounts.ensureCategorySystemAccount(userId, categoryId, nature, currency);
	}

	public Transaction postBalanceAdjustment(BalanceAdjustmentCommand command) {
		require(command, "余额调整命令");
		return transactions.nested(() -> {
			AccountPostingReference account = editableAccount(command.userId(), command.accountId());
			LedgerAccountReference accountLedger = primary(account);
			validateBalance(command.actualBalance(), accountLedger.currency());
			LedgerAccountReference equityLedger = command.equityLedgerAccountId() == null
				? ledgerAccounts.ensureBalanceAdjustmentEquityAccount(command.userId(), accountLedger.currency())
				: requireSystem(command.equityLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EQUITY);
			if (!"EQUITY_BALANCE_ADJUSTMENT".equals(equityLedger.code())) {
				throw invalid("余额调整必须使用余额调整权益科目。");
			}
			Money before = ledgerAccounts.currentBalance(accountLedger.id());
			if (before.currency() != command.actualBalance().currency()) {
				throw invalid("余额调整币种不一致。");
			}
			Money difference = new Money(
				command.actualBalance().amount().subtract(before.amount()), before.currency());
			if (difference.amount().signum() == 0) {
				throw invalid("实际余额与系统余额一致，无需调整。");
			}
			boolean liability = "LIABILITY".equals(account.accountClass());
			LedgerDirection accountDirection = difference.amount().signum() > 0
				? (liability ? LedgerDirection.CREDIT : LedgerDirection.DEBIT)
				: (liability ? LedgerDirection.DEBIT : LedgerDirection.CREDIT);
			LedgerDirection equityDirection =
				accountDirection == LedgerDirection.DEBIT ? LedgerDirection.CREDIT : LedgerDirection.DEBIT;
			Money absoluteDifference = new Money(difference.amount().abs(), difference.currency());
			Transaction transaction = transactionFactory.createPosted(
				UUID.randomUUID(),
				TransactionType.ADJUSTMENT,
				TransactionSource.ADJUSTMENT,
				command.businessAt(),
				command.businessDate(),
				command.timezone(),
				clock.instant(),
				List.of(
					new LedgerEntrySpec(accountLedger.id(), accountDirection, absoluteDifference),
					new LedgerEntrySpec(equityLedger.id(), equityDirection, absoluteDifference)));
			completeInitialPosting(new PostedTransactionWrite(
				transaction, command.userId(), null, null, null, null,
				new BalanceAdjustmentWriteDetails(
					command.accountId(), before, command.actualBalance(), difference, command.reason())));
			return transaction;
		});
	}

	/** 按既有 B1 语义生成“冲正 + 新版本”事实链，不接受任意外部借贷分录。 */
	public TransactionRevisionResult revisePostedTransaction(RevisePostedTransactionCommand command) {
		require(command, "交易修订命令");
		return transactions.nested(() -> revisePostedTransactionInTransaction(command));
	}

	private TransactionRevisionResult revisePostedTransactionInTransaction(RevisePostedTransactionCommand command) {
		LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
		Transaction original = snapshot.transaction();
		lockRevisionAccounts(original, command.details());
		validateOriginalAccess(command.userId(), original);
		requireExpectedVersion(command.transactionId(), command.expectedEntityVersion(), snapshot.entityVersion());
		// 先比较锁定快照的实体版本，再判断历史状态，陈旧的 SUPERSEDED/REVERSED 请求仍须返回 VERSION_CONFLICT。
		requireIndependentOriginal(snapshot);
		RevisionBuild build = buildRevision(command, snapshot);

		Transaction reversal = transactionFactory.createReversal(original, UUID.randomUUID(), clock.instant());
		Transaction replacement = transactionFactory.createPostedVersion(
			initialTransactionId(command.replacementTransactionId()), original.type(), original.source(), command.businessAt(), command.businessDate(),
			command.timezone(), clock.instant(), original.rootTransactionId(), original.transactionId(),
			original.versionNo() + 1, build.entries());
		LedgerTransactionStore.TransactionRevisionWrite write = new LedgerTransactionStore.TransactionRevisionWrite(
			original.transactionId(), snapshot.entityVersion(), command.reason(), reversal,
			new PostedTransactionWrite(replacement, command.userId(), command.counterparty(), command.merchant(),
				command.note(), build.categoryId(), build.details()));
		ledgerTransactions.persistRevision(write);
		completeRevisionAuditAndOutbox(original, snapshot.entityVersion(), reversal, replacement, command.userId());
		return new TransactionRevisionResult(original.transactionId(), reversal, replacement);
	}

	/** 作废通过新增冲正事实完成，原 POSTED 交易只发生允许的状态迁移。 */
	public TransactionVoidResult voidPostedTransaction(VoidPostedTransactionCommand command) {
		require(command, "交易作废命令");
		return transactions.nested(() -> voidPostedTransactionInTransaction(command));
	}

	private TransactionVoidResult voidPostedTransactionInTransaction(VoidPostedTransactionCommand command) {
		LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
		Transaction original = snapshot.transaction();
		lockOriginalAccounts(original);
		validateOriginalAccess(command.userId(), original);
		requireExpectedVersion(command.transactionId(), command.expectedEntityVersion(), snapshot.entityVersion());
		// 与修订一致：版本冲突优先于“已有后续事实”的业务失败，避免旧版本请求被改写为 422。
		requireIndependentOriginal(snapshot);

		Transaction reversal = transactionFactory.createReversal(original, UUID.randomUUID(), clock.instant());
		LedgerTransactionStore.TransactionVoidWrite write = new LedgerTransactionStore.TransactionVoidWrite(
			original.transactionId(), snapshot.entityVersion(), command.userId(), command.reason(), reversal);
		ledgerTransactions.persistVoid(write);
		completeVoidAuditAndOutbox(original, snapshot.entityVersion(), reversal, command.userId());
		return new TransactionVoidResult(original.transactionId(), reversal);
	}

	/** 账务、审计和最小投影定位事件均处于当前 REQUIRED 事务，任何端口异常都会向外回滚。 */
	private void completeInitialPosting(PostedTransactionWrite write) {
		ledgerTransactions.persistPosted(write);
		Transaction transaction = write.transaction();
		auditLogs.append(audit(
			write.createdBy(), "TRANSACTION_POSTED", null,
			Map.of(
				"transactionId", transaction.transactionId().toString(),
				"rootTransactionId", transaction.rootTransactionId().toString(),
				"versionNo", Integer.toString(transaction.versionNo()),
				"entityVersion", "1",
				"source", transaction.source().name()), transaction));
		ledgerOutbox.append(postedEvent(transaction, "INITIAL", null));
	}

	private void completeRevisionAuditAndOutbox(
		Transaction original,
		int originalBefore,
		Transaction reversal,
		Transaction replacement,
		UUID actorUserId) {
		int originalAfter = originalBefore + 1;
		auditLogs.append(audit(
			actorUserId, "TRANSACTION_REVISED", "SUPERSEDED", Map.ofEntries(
				Map.entry("rootTransactionId", original.rootTransactionId().toString()),
				Map.entry("originalTransactionId", original.transactionId().toString()),
				Map.entry("reversalTransactionId", reversal.transactionId().toString()),
				Map.entry("replacementTransactionId", replacement.transactionId().toString()),
				Map.entry("originalVersionNo", Integer.toString(original.versionNo())),
				Map.entry("replacementVersionNo", Integer.toString(replacement.versionNo())),
				Map.entry("originalEntityVersionBefore", Integer.toString(originalBefore)),
				Map.entry("originalEntityVersionAfter", Integer.toString(originalAfter)),
				Map.entry("replacementEntityVersion", "1"),
				Map.entry("reversalEntityVersion", "1"),
				Map.entry("source", replacement.source().name())), original, reversal, replacement));
		ledgerOutbox.append(reversedEvent(reversal, original, originalBefore, originalAfter, "REVISION", replacement));
		ledgerOutbox.append(postedEvent(replacement, "REVISION", replacement));
	}

	private void completeVoidAuditAndOutbox(
		Transaction original,
		int originalBefore,
		Transaction reversal,
		UUID actorUserId) {
		int originalAfter = originalBefore + 1;
		auditLogs.append(audit(
			actorUserId, "TRANSACTION_VOIDED", "REVERSED",
			Map.of(
				"rootTransactionId", original.rootTransactionId().toString(),
				"originalTransactionId", original.transactionId().toString(),
				"reversalTransactionId", reversal.transactionId().toString(),
				"originalVersionNo", Integer.toString(original.versionNo()),
				"originalEntityVersionBefore", Integer.toString(originalBefore),
				"originalEntityVersionAfter", Integer.toString(originalAfter),
				"reversalEntityVersion", "1",
				"source", original.source().name()), original, reversal));
		ledgerOutbox.append(reversedEvent(reversal, original, originalBefore, originalAfter, "VOID", null));
	}

	private AuditLogWritePort.AuditLogEntry audit(
		UUID actorUserId,
		String action,
		String reasonCode,
		Map<String, String> metadata,
		Transaction... transactions) {
		Transaction resource = transactions[0];
		return new AuditLogWritePort.AuditLogEntry(
			clock.instant(), actorUserId, AuditLogWritePort.ActorType.USER, action, "TRANSACTION",
			resource.rootTransactionId(), accountIdFor(transactions), requestIds.currentRequestId(),
			AuditLogWritePort.Result.SUCCESS, reasonCode, metadata);
	}

	private LedgerOutboxEvent postedEvent(
		Transaction transaction, String operationKind, Transaction replacement) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("transactionId", transaction.transactionId().toString());
		payload.put("rootTransactionId", transaction.rootTransactionId().toString());
		payload.put("versionNo", transaction.versionNo());
		payload.put("entityVersion", 1);
		payload.put("operationKind", operationKind);
		if (replacement != null) {
			payload.put("replacementTransactionId", replacement.transactionId().toString());
			payload.put("replacementVersionNo", replacement.versionNo());
			payload.put("replacementEntityVersion", 1);
		}
		return new LedgerOutboxEvent(UUID.randomUUID(), transaction.transactionId(),
			LedgerOutboxEvent.EventType.TransactionPosted, clock.instant(), payload);
	}

	private LedgerOutboxEvent reversedEvent(
		Transaction reversal,
		Transaction original,
		int originalBefore,
		int originalAfter,
		String operationKind,
		Transaction replacement) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("transactionId", reversal.transactionId().toString());
		payload.put("rootTransactionId", original.rootTransactionId().toString());
		payload.put("entityVersion", 1);
		payload.put("reversalOfTransactionId", original.transactionId().toString());
		payload.put("reversalOfVersionNo", original.versionNo());
		payload.put("reversalOfEntityVersionBefore", originalBefore);
		payload.put("reversalOfEntityVersionAfter", originalAfter);
		payload.put("operationKind", operationKind);
		if (replacement != null) {
			payload.put("replacementTransactionId", replacement.transactionId().toString());
			payload.put("replacementVersionNo", replacement.versionNo());
			payload.put("replacementEntityVersion", 1);
		}
		return new LedgerOutboxEvent(UUID.randomUUID(), reversal.transactionId(),
			LedgerOutboxEvent.EventType.TransactionReversed, clock.instant(), payload);
	}

	private UUID accountIdFor(Transaction... transactions) {
		Set<UUID> accountIds = new java.util.HashSet<>();
		for (Transaction transaction : transactions) {
			for (LedgerEntry entry : transaction.entries()) {
				ledgerAccounts.findById(entry.ledgerAccountId())
					.map(LedgerAccountReference::visibleAccountId)
					.ifPresent(accountIds::add);
			}
		}
		return accountIds.size() == 1 ? accountIds.iterator().next() : null;
	}

	private AccountPostingReference editableAccount(UUID userId, UUID accountId) {
		AccountPostingReference account = requireWritableAccount(userId, accountId);
		if (!account.active()) {
			throw invalid("归档账户不能新增账务交易。");
		}
		return account;
	}

	private void lockAccounts(UUID... accountIds) {
		if (accountIds == null) {
			throw invalid("账户锁定参数无效。");
		}
		java.util.TreeSet<UUID> ordered = new java.util.TreeSet<>();
		for (UUID accountId : accountIds) {
			if (accountId == null) {
				throw invalid("账户 ID 不能为空。");
			}
			ordered.add(accountId);
		}
		// 多账户账务命令固定按 UUID 锁序，避免归档与转账/还款交叉提交时形成死锁。
		for (UUID accountId : ordered) {
			accounts.findByIdForUpdate(accountId)
				.orElseThrow(TransactionNotVisibleException::new);
		}
	}

	private AccountPostingReference requireWritableAccount(UUID userId, UUID accountId) {
		AccountPostingReference account = accounts.findByIdForUpdate(accountId)
			.orElseThrow(TransactionNotVisibleException::new);
		switch (accountAccess.postingDecision(userId, accountId)) {
			case ALLOWED -> {
				// 先完成当前 membership 可见性/角色判断，再拒绝归档账户，避免状态检查泄漏不可见资源。
				if (!account.active()) {
					throw invalid("归档账户不能新增或修改账务交易。");
				}
				return account;
			}
			case NOT_VISIBLE -> throw new TransactionNotVisibleException();
			case READ_ONLY -> throw new LedgerPermissionDeniedException();
		}
		throw invalid("当前成员无权写入该账户。");
	}

	private LedgerTransactionStore.PostedTransactionSnapshot postedForMutation(UUID transactionId) {
		return ledgerTransactions.findPostedForMutation(transactionId)
			.orElseThrow(() -> invalid("交易不存在或不是可修改的已确认交易。"));
	}

	private static void requireExpectedVersion(UUID transactionId, int expected, int actual) {
		if (expected != actual) {
			throw new LedgerVersionConflictException(transactionId, actual);
		}
	}

	private static void requireIndependentOriginal(LedgerTransactionStore.PostedTransactionSnapshot snapshot) {
		if (snapshot.hasDependentFacts() || snapshot.transaction().type() == TransactionType.REVERSAL
			|| snapshot.transaction().status() != TransactionStatus.POSTED) {
			throw invalid("交易已有关联后续事实，不能修改或作废。");
		}
	}

	private void lockOriginalAccounts(Transaction transaction) {
		lockAccounts(visibleAccountIds(transaction).toArray(UUID[]::new));
	}

	private void lockRevisionAccounts(Transaction original, TransactionRevisionDetails details) {
		if (details == null) {
			throw invalid("交易修订语义载荷不能为空。");
		}
		java.util.TreeSet<UUID> accountIds = visibleAccountIds(original);
		switch (details) {
			case TransactionRevisionDetails.Income income -> addAccountId(accountIds, income.accountId());
			case TransactionRevisionDetails.Expense expense -> addAccountId(accountIds, expense.accountId());
			case TransactionRevisionDetails.Transfer transfer -> {
				addAccountId(accountIds, transfer.fromAccountId());
				addAccountId(accountIds, transfer.toAccountId());
			}
			case TransactionRevisionDetails.Refund refund -> {
				addAccountId(accountIds, refund.accountId());
				ledgerTransactions.findRefundCandidate(refund.originalTransactionId())
					.map(LedgerTransactionStore.RefundCandidate::originalAccountId)
					.ifPresent(accountId -> addAccountId(accountIds, accountId));
			}
			case TransactionRevisionDetails.BalanceAdjustment adjustment ->
				addAccountId(accountIds, adjustment.accountId());
			case TransactionRevisionDetails.LiabilityBorrowing borrowing -> {
				addAccountId(accountIds, borrowing.assetAccountId());
				addAccountId(accountIds, borrowing.liabilityAccountId());
			}
			case TransactionRevisionDetails.LiabilityRepayment repayment -> {
				addAccountId(accountIds, repayment.cashAccountId());
				addAccountId(accountIds, repayment.liabilityAccountId());
			}
		}
		// 修订的原账户、目标账户和退款关联账户一次性按 UUID 排序，避免两阶段加锁形成死锁环。
		lockAccounts(accountIds.toArray(UUID[]::new));
	}

	private java.util.TreeSet<UUID> visibleAccountIds(Transaction transaction) {
		java.util.TreeSet<UUID> accountIds = new java.util.TreeSet<>();
		for (LedgerEntry entry : transaction.entries()) {
			LedgerAccountReference ledgerAccount = ledgerAccounts.findById(entry.ledgerAccountId())
				.orElseThrow(() -> invalid("原交易账务科目不存在。"));
			addAccountId(accountIds, ledgerAccount.visibleAccountId());
		}
		return accountIds;
	}

	private static void addAccountId(Set<UUID> accountIds, UUID accountId) {
		if (accountId != null) {
			accountIds.add(accountId);
		}
	}

	private void validateOriginalAccess(UUID userId, Transaction transaction) {
		List<LedgerAccountReference> references = new ArrayList<>();
		for (LedgerEntry entry : transaction.entries()) {
			LedgerAccountReference ledgerAccount = ledgerAccounts.findById(entry.ledgerAccountId())
				.orElseThrow(() -> invalid("原交易账务科目不存在。"));
			references.add(ledgerAccount);
		}
		for (LedgerAccountReference ledgerAccount : references) {
			if (ledgerAccount.visibleAccountId() != null) {
				requireWritableAccount(userId, ledgerAccount.visibleAccountId());
				if (!ledgerAccount.active()) {
					throw invalid("原交易账务科目不可用。");
				}
			} else if (ledgerAccount.role() != LedgerAccountRole.SYSTEM) {
				throw invalid("原交易内部科目角色无效。");
			}
		}
	}

	private RevisionBuild buildRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot) {
		Transaction original = snapshot.transaction();
		return switch (command.details()) {
			case TransactionRevisionDetails.Income details -> buildIncomeRevision(command, original, details);
			case TransactionRevisionDetails.Expense details -> buildExpenseRevision(command, original, details);
			case TransactionRevisionDetails.Transfer details -> buildTransferRevision(command, snapshot, details);
			case TransactionRevisionDetails.Refund details -> buildRefundRevision(command, snapshot, details);
			case TransactionRevisionDetails.BalanceAdjustment details ->
				buildBalanceAdjustmentRevision(command, snapshot, details);
			case TransactionRevisionDetails.LiabilityBorrowing details ->
				buildLiabilityBorrowingRevision(command, snapshot, details);
			case TransactionRevisionDetails.LiabilityRepayment details ->
				buildLiabilityRepaymentRevision(command, snapshot, details);
		};
	}

	private RevisionBuild buildIncomeRevision(
		RevisePostedTransactionCommand command,
		Transaction original,
		TransactionRevisionDetails.Income details) {
		if (original.type() != TransactionType.INCOME) {
			throw invalid("收入语义载荷与原交易类型不匹配。");
		}
		LedgerAccountReference originalAccountLedger = originalVisibleLedger(original);
		// 修订未指定账户时沿用原账户；入口已按原账户与目标账户的全局 UUID 顺序统一加锁。
		AccountPostingReference account = details.accountId() == null
			? visibleAccountForMutation(originalAccountLedger, command.userId())
			: editableAccount(command.userId(), details.accountId());
		LedgerAccountReference accountLedger = primary(account);
		requireAssetAccount(account, "收入");
		validateAmount(details.amount(), accountLedger.currency());
		requireCategory(details.categoryId(), command.userId(), account.id(), CategoryType.INCOME);
		LedgerAccountReference incomeLedger = details.incomeLedgerAccountId() == null
			? ensureCategoryCounter(command.userId(), details.categoryId(), LedgerAccountNature.INCOME, accountLedger.currency())
			: requireSystem(details.incomeLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.INCOME);
		return new RevisionBuild(
			List.of(
				new LedgerEntrySpec(accountLedger.id(), LedgerDirection.DEBIT, details.amount()),
				new LedgerEntrySpec(incomeLedger.id(), LedgerDirection.CREDIT, details.amount())),
			details.categoryId(), new NoTransactionDetails());
	}

	private RevisionBuild buildExpenseRevision(
		RevisePostedTransactionCommand command,
		Transaction original,
		TransactionRevisionDetails.Expense details) {
		if (original.type() != TransactionType.EXPENSE) {
			throw invalid("支出语义载荷与原交易类型不匹配。");
		}
		LedgerAccountReference originalAccountLedger = originalVisibleLedger(original);
		// 修订未指定账户时沿用原账户；入口已按原账户与目标账户的全局 UUID 顺序统一加锁。
		AccountPostingReference account = details.accountId() == null
			? visibleAccountForMutation(originalAccountLedger, command.userId())
			: editableAccount(command.userId(), details.accountId());
		LedgerAccountReference accountLedger = primary(account);
		if ("LIABILITY".equals(account.accountClass())) {
			if (!"CREDIT_CARD".equals(account.accountType())) {
				throw invalid("只有信用卡负债账户可以使用支出语义。");
			}
		} else {
			requireAssetAccount(account, "支出");
		}
		validateAmount(details.amount(), accountLedger.currency());
		requireCategory(details.categoryId(), command.userId(), account.id(), CategoryType.EXPENSE);
		LedgerAccountReference expenseLedger = details.expenseLedgerAccountId() == null
			? ensureCategoryCounter(command.userId(), details.categoryId(), LedgerAccountNature.EXPENSE, accountLedger.currency())
			: requireSystem(details.expenseLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EXPENSE);
		return new RevisionBuild(
			List.of(
				new LedgerEntrySpec(expenseLedger.id(), LedgerDirection.DEBIT, details.amount()),
				new LedgerEntrySpec(accountLedger.id(), LedgerDirection.CREDIT, details.amount())),
			details.categoryId(), new NoTransactionDetails());
	}

	private RevisionBuild buildTransferRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot,
		TransactionRevisionDetails.Transfer details) {
		if (snapshot.transaction().type() != TransactionType.TRANSFER
			|| !(snapshot.details() instanceof TransferWriteDetails)) {
			throw invalid("转账语义载荷与原交易类型不匹配。");
		}
		AccountPostingReference from = editableAccount(command.userId(), details.fromAccountId());
		AccountPostingReference to = editableAccount(command.userId(), details.toAccountId());
		requireAssetAccount(from, "转账");
		requireAssetAccount(to, "转账");
		LedgerAccountReference fromLedger = primary(from);
		LedgerAccountReference toLedger = primary(to);
		if (fromLedger.currency() != toLedger.currency()) {
			throw invalid("本任务只允许同币种转账。");
		}
		validateAmount(details.amount(), fromLedger.currency());
		Money fee = details.feeAmount() == null
			? new Money(BigDecimal.ZERO, fromLedger.currency()) : details.feeAmount();
		validateFee(fee, fromLedger.currency());
		List<LedgerEntrySpec> entries = new ArrayList<>(List.of(
			new LedgerEntrySpec(toLedger.id(), LedgerDirection.DEBIT, details.amount()),
			new LedgerEntrySpec(fromLedger.id(), LedgerDirection.CREDIT, details.amount())));
		UUID categoryId = null;
		if (fee.amount().signum() > 0) {
			requireCategory(details.feeCategoryId(), command.userId(), details.fromAccountId(), CategoryType.EXPENSE);
			LedgerAccountReference feeLedger = details.feeLedgerAccountId() == null
				? ensureCategoryCounter(command.userId(), details.feeCategoryId(), LedgerAccountNature.EXPENSE, fromLedger.currency())
				: requireSystem(details.feeLedgerAccountId(), command.userId(), fromLedger.currency(), LedgerAccountNature.EXPENSE);
			categoryId = details.feeCategoryId();
			entries.add(new LedgerEntrySpec(feeLedger.id(), LedgerDirection.DEBIT, fee));
			entries.add(new LedgerEntrySpec(fromLedger.id(), LedgerDirection.CREDIT, fee));
		} else if (details.feeCategoryId() != null || details.feeLedgerAccountId() != null) {
			throw invalid("无手续费时不能提供手续费科目或分类。");
		}
		return new RevisionBuild(
			entries, categoryId,
			new TransferWriteDetails(details.fromAccountId(), details.toAccountId(), details.amount(), details.amount(), fee));
	}

	private RevisionBuild buildRefundRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot,
		TransactionRevisionDetails.Refund details) {
		if (snapshot.transaction().type() != TransactionType.REFUND
			|| !(snapshot.details() instanceof RefundWriteDetails)) {
			throw invalid("退款语义载荷与原交易类型不匹配。");
		}
		RefundWriteDetails originalDetails = (RefundWriteDetails) snapshot.details();
		if (details.originalTransactionId() == null || details.originalTransactionId().equals(snapshot.transaction().transactionId())) {
			throw invalid("退款原支出交易关联无效。");
		}
		LedgerTransactionStore.RefundCandidate candidate = ledgerTransactions
			.findRefundCandidate(details.originalTransactionId())
			.orElseThrow(() -> invalid("原支出交易不存在或不可退款。"));
		AccountPostingReference refundAccount = editableAccount(command.userId(), details.accountId());
		LedgerAccountReference refundLedger = primary(refundAccount);
		requireAssetAccount(refundAccount, "退款");
		validateAmount(details.amount(), refundLedger.currency());
		requireWritableAccount(command.userId(), candidate.originalAccountId());
		if (!candidate.originalAmount().currency().equals(refundLedger.currency())) {
			throw invalid("退款币种必须与原支出一致。");
		}
		BigDecimal priorAmount = originalDetails.originalTransactionId().equals(details.originalTransactionId())
			? snapshot.transaction().entries().stream()
				.filter(entry -> entry.direction() == LedgerDirection.DEBIT)
				.map(entry -> entry.amount().amount())
				.findFirst().orElse(BigDecimal.ZERO)
			: BigDecimal.ZERO;
		Money remaining = new Money(
			candidate.originalAmount().amount().subtract(candidate.refundedAmount().amount()).add(priorAmount),
			refundLedger.currency());
		if (details.amount().compareTo(remaining) > 0) {
			throw invalid("退款金额超过原支出可退款余额。");
		}
		requireCategory(candidate.categoryId(), command.userId(), candidate.originalAccountId(), CategoryType.EXPENSE);
		return new RevisionBuild(
			List.of(
				new LedgerEntrySpec(refundLedger.id(), LedgerDirection.DEBIT, details.amount()),
				new LedgerEntrySpec(candidate.expenseLedgerAccountId(), LedgerDirection.CREDIT, details.amount())),
			null, new RefundWriteDetails(details.originalTransactionId(), candidate.categoryId()));
	}

	private RevisionBuild buildBalanceAdjustmentRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot,
		TransactionRevisionDetails.BalanceAdjustment details) {
		if (snapshot.transaction().type() != TransactionType.ADJUSTMENT
			|| !(snapshot.details() instanceof BalanceAdjustmentWriteDetails)) {
			throw invalid("余额调整语义载荷与原交易类型或账户不匹配。");
		}
		BalanceAdjustmentWriteDetails originalDetails = (BalanceAdjustmentWriteDetails) snapshot.details();
		if (!originalDetails.accountId().equals(details.accountId())) {
			throw invalid("余额调整语义载荷与原交易类型或账户不匹配。");
		}
		AccountPostingReference account = editableAccount(command.userId(), details.accountId());
		LedgerAccountReference accountLedger = primary(account);
		validateBalance(details.actualBalance(), accountLedger.currency());
		LedgerAccountReference equityLedger = details.equityLedgerAccountId() == null
			? ledgerAccounts.ensureBalanceAdjustmentEquityAccount(command.userId(), accountLedger.currency())
			: requireSystem(details.equityLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EQUITY);
		if (!"EQUITY_BALANCE_ADJUSTMENT".equals(equityLedger.code())) {
			throw invalid("余额调整必须使用余额调整权益科目。");
		}
		Money before = originalDetails.beforeBalance();
		if (!before.currency().equals(details.actualBalance().currency())) {
			throw invalid("余额调整币种不一致。");
		}
		Money difference = new Money(details.actualBalance().amount().subtract(before.amount()), before.currency());
		if (difference.amount().signum() == 0) {
			throw invalid("实际余额与系统余额一致，无需调整。");
		}
		boolean liability = "LIABILITY".equals(account.accountClass());
		LedgerDirection accountDirection = difference.amount().signum() > 0
			? (liability ? LedgerDirection.CREDIT : LedgerDirection.DEBIT)
			: (liability ? LedgerDirection.DEBIT : LedgerDirection.CREDIT);
		LedgerDirection equityDirection = accountDirection == LedgerDirection.DEBIT
			? LedgerDirection.CREDIT : LedgerDirection.DEBIT;
		Money absoluteDifference = new Money(difference.amount().abs(), difference.currency());
		return new RevisionBuild(
			List.of(
				new LedgerEntrySpec(accountLedger.id(), accountDirection, absoluteDifference),
				new LedgerEntrySpec(equityLedger.id(), equityDirection, absoluteDifference)),
			null, new BalanceAdjustmentWriteDetails(
				details.accountId(), before, details.actualBalance(), difference, details.reason()));
	}

	private RevisionBuild buildLiabilityBorrowingRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot,
		TransactionRevisionDetails.LiabilityBorrowing details) {
		if (snapshot.transaction().type() != TransactionType.TRANSFER
			|| !(snapshot.details() instanceof LiabilityBorrowingWriteDetails)) {
			throw invalid("借款到账语义载荷与原交易类型不匹配。");
		}
		AccountPostingReference asset = editableAccount(command.userId(), details.assetAccountId());
		AccountPostingReference liability = editableAccount(command.userId(), details.liabilityAccountId());
		requireExactAccountClass(asset, "ASSET", "借款到账");
		requireExactAccountClass(liability, "LIABILITY", "借款到账");
		if (!Set.of("LOAN", "CONSUMER_LOAN", "OTHER").contains(liability.accountType())) {
			throw invalid("借款到账不允许使用信用卡账户。");
		}
		LedgerAccountReference assetLedger = primary(asset);
		LedgerAccountReference liabilityLedger = primary(liability);
		if (assetLedger.currency() != liabilityLedger.currency()) {
			throw invalid("借款到账两账户必须使用同一币种。");
		}
		validateAmount(details.amount(), assetLedger.currency());
		return new RevisionBuild(
			List.of(
				new LedgerEntrySpec(assetLedger.id(), LedgerDirection.DEBIT, details.amount()),
				new LedgerEntrySpec(liabilityLedger.id(), LedgerDirection.CREDIT, details.amount())),
			null, new LiabilityBorrowingWriteDetails(
				details.assetAccountId(), details.liabilityAccountId(), details.amount()));
	}

	private RevisionBuild buildLiabilityRepaymentRevision(
		RevisePostedTransactionCommand command,
		LedgerTransactionStore.PostedTransactionSnapshot snapshot,
		TransactionRevisionDetails.LiabilityRepayment details) {
		if (snapshot.transaction().type() != TransactionType.REPAYMENT
			|| !(snapshot.details() instanceof RepaymentWriteDetails)) {
			throw invalid("负债还款语义载荷与原交易类型不匹配。");
		}
		AccountPostingReference cash = editableAccount(command.userId(), details.cashAccountId());
		AccountPostingReference liability = editableAccount(command.userId(), details.liabilityAccountId());
		requireExactAccountClass(cash, "ASSET", "负债还款");
		requireExactAccountClass(liability, "LIABILITY", "负债还款");
		LedgerAccountReference cashLedger = primary(cash);
		LedgerAccountReference liabilityLedger = primary(liability);
		if (cashLedger.currency() != liabilityLedger.currency()) {
			throw invalid("负债还款两账户必须使用同一币种。");
		}
		CurrencyCode currency = cashLedger.currency();
		validateAmount(details.principalAmount(), currency);
		validateNonNegativeAmount(details.interestAmount(), currency, "利息");
		validateNonNegativeAmount(details.feeAmount(), currency, "手续费");
		List<LedgerEntrySpec> entries = new ArrayList<>();
		entries.add(new LedgerEntrySpec(liabilityLedger.id(), LedgerDirection.DEBIT, details.principalAmount()));
		entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, details.principalAmount()));
		if (details.interestAmount().amount().signum() > 0) {
			requireCategoryForAccounts(details.interestCategoryId(), command.userId(), CategoryType.EXPENSE,
				details.cashAccountId(), details.liabilityAccountId());
			LedgerAccountReference interestLedger = ensureCategoryCounter(
				command.userId(), details.interestCategoryId(), LedgerAccountNature.EXPENSE, currency);
			entries.add(new LedgerEntrySpec(interestLedger.id(), LedgerDirection.DEBIT, details.interestAmount()));
			entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, details.interestAmount()));
		} else if (details.interestCategoryId() != null) {
			throw invalid("利息为零时不能提供利息分类。");
		}
		if (details.feeAmount().amount().signum() > 0) {
			requireCategoryForAccounts(details.feeCategoryId(), command.userId(), CategoryType.EXPENSE,
				details.cashAccountId(), details.liabilityAccountId());
			LedgerAccountReference feeLedger = ensureCategoryCounter(
				command.userId(), details.feeCategoryId(), LedgerAccountNature.EXPENSE, currency);
			entries.add(new LedgerEntrySpec(feeLedger.id(), LedgerDirection.DEBIT, details.feeAmount()));
			entries.add(new LedgerEntrySpec(cashLedger.id(), LedgerDirection.CREDIT, details.feeAmount()));
		} else if (details.feeCategoryId() != null) {
			throw invalid("手续费为零时不能提供手续费分类。");
		}
		return new RevisionBuild(entries, null, new RepaymentWriteDetails(
			details.liabilityAccountId(), details.cashAccountId(), details.principalAmount(),
			details.interestAmount(), details.feeAmount(), details.interestCategoryId(), details.feeCategoryId()));
	}

	private LedgerAccountReference originalVisibleLedger(Transaction transaction) {
		return transaction.entries().stream()
			.map(entry -> ledgerAccounts.findById(entry.ledgerAccountId()).orElse(null))
			.filter(reference -> reference != null && reference.visibleAccountId() != null
				&& reference.role() == LedgerAccountRole.PRIMARY)
			.findFirst()
			.orElseThrow(() -> invalid("原交易缺少可见账户主科目。"));
	}

	private AccountPostingReference visibleAccountForMutation(LedgerAccountReference ledgerAccount, UUID userId) {
		return requireWritableAccount(userId, ledgerAccount.visibleAccountId());
	}

	private record RevisionBuild(
		List<LedgerEntrySpec> entries,
		UUID categoryId,
		TransactionWriteDetails details) {
	}

	private LedgerAccountReference primary(AccountPostingReference account) {
		LedgerAccountReference reference = ledgerAccounts.findPrimaryForVisibleAccount(account.id())
			.orElseThrow(() -> invalid("账户缺少主账务科目。"));
		LedgerAccountNature expectedNature = switch (account.accountClass()) {
			case "ASSET", "INVESTMENT" -> LedgerAccountNature.ASSET;
			case "LIABILITY" -> LedgerAccountNature.LIABILITY;
			default -> null;
		};
		if (reference.role() != LedgerAccountRole.PRIMARY
			|| reference.visibleAccountId() == null
			|| !reference.active()
			|| expectedNature == null
			|| reference.nature() != expectedNature) {
			throw invalid("账户主账务科目状态无效。");
		}
		if (reference.currency() != CurrencyCode.fromCode(account.currency())) {
			throw invalid("账户与账务科目币种不一致。");
		}
		return reference;
	}

	private LedgerAccountReference openingPrimary(UUID accountId, String accountClass, CurrencyCode currency) {
		LedgerAccountReference reference = ledgerAccounts.findPrimaryForVisibleAccount(accountId)
			.orElseThrow(() -> invalid("账户缺少主账务科目。"));
		LedgerAccountNature expectedNature = "LIABILITY".equals(accountClass)
			? LedgerAccountNature.LIABILITY : LedgerAccountNature.ASSET;
		if (reference.role() != LedgerAccountRole.PRIMARY || !reference.active()
			|| !accountId.equals(reference.visibleAccountId()) || reference.nature() != expectedNature
			|| reference.currency() != currency) {
			throw invalid("账户主账务科目状态无效。");
		}
		return reference;
	}

	private LedgerAccountReference requireSystem(
		UUID ledgerAccountId,
		UUID userId,
		CurrencyCode currency,
		LedgerAccountNature nature) {
		if (ledgerAccountId == null) {
			throw invalid("系统科目不能为空。");
		}
		LedgerAccountReference reference = ledgerAccounts.findById(ledgerAccountId)
			.orElseThrow(() -> invalid("系统科目不存在。"));
		if (reference.role() != LedgerAccountRole.SYSTEM || reference.ownerUserId() == null
			|| !reference.ownerUserId().equals(userId) || reference.nature() != nature
			|| reference.currency() != currency || !reference.active()) {
			throw invalid("系统科目不匹配。");
		}
		return reference;
	}

	private void requireCategory(UUID categoryId, UUID userId, UUID accountId, CategoryType type) {
		CategoryReference category = categories.findById(categoryId)
			.orElseThrow(() -> invalid("分类不存在。"));
		if (!category.active() || category.type() != type
			|| (category.ownerUserId() != null && !category.ownerUserId().equals(userId))
			|| (category.accountId() != null && !category.accountId().equals(accountId))) {
			throw invalid("分类与交易语义或归属不匹配。");
		}
	}

	private void requireCategoryForAccounts(
		UUID categoryId, UUID userId, CategoryType type, UUID firstAccountId, UUID secondAccountId) {
		CategoryReference category = categories.findById(categoryId)
			.orElseThrow(() -> invalid("分类不存在。"));
		// 双账户命令允许使用任一参与账户的专属分类，但不能借此引用第三个账户的分类。
		if (!category.active() || category.type() != type
			|| (category.ownerUserId() != null && !category.ownerUserId().equals(userId))
			|| (category.accountId() != null
				&& !category.accountId().equals(firstAccountId)
				&& !category.accountId().equals(secondAccountId))) {
			throw invalid("分类与交易语义或归属不匹配。");
		}
	}

	private static void validateAmount(Money amount, CurrencyCode expectedCurrency) {
		if (amount == null || amount.amount().signum() <= 0) {
			throw invalid("金额必须大于零。");
		}
		if (amount.currency() != expectedCurrency) {
			throw invalid("金额币种与账户不一致。");
		}
		if (!amount.hasPostingPrecision()) {
			throw invalid("金额超出入账精度，必须先明确舍入。");
		}
	}

	private static void validateNonNegativeAmount(Money amount, CurrencyCode expectedCurrency, String field) {
		if (amount == null || amount.amount().signum() < 0) {
			throw invalid(field + "不能为负数。");
		}
		if (amount.currency() != expectedCurrency) {
			throw invalid(field + "币种与账户不一致。");
		}
		if (!amount.hasPostingPrecision()) {
			throw invalid(field + "超出入账精度，必须先明确舍入。");
		}
	}

	private static void validateFee(Money fee, CurrencyCode expectedCurrency) {
		if (fee == null || fee.amount().signum() < 0) {
			throw invalid("手续费不能为负数。");
		}
		if (fee.currency() != expectedCurrency) {
			throw invalid("手续费币种与转出账户不一致。");
		}
		if (!fee.hasPostingPrecision()) {
			throw invalid("手续费超出入账精度，必须先明确舍入。");
		}
	}

	private static void validateBalance(Money balance, CurrencyCode expectedCurrency) {
		if (balance == null) {
			throw invalid("实际余额不能为空。");
		}
		if (balance.currency() != expectedCurrency) {
			throw invalid("实际余额币种与账户不一致。");
		}
		if (!balance.hasPostingPrecision()) {
			throw invalid("实际余额超出入账精度，必须先明确舍入。");
		}
	}

	private static void requireAssetAccount(AccountPostingReference account, String operation) {
		if (!"ASSET".equals(account.accountClass()) && !"INVESTMENT".equals(account.accountClass())) {
			throw invalid(operation + "只允许资产或投资账户。");
		}
	}

	private static void requireExactAccountClass(
		AccountPostingReference account, String expectedClass, String operation) {
		if (!expectedClass.equals(account.accountClass())) {
			throw invalid(operation + "账户类型不符合要求。");
		}
	}

	private static void require(Object command, String field) {
		if (command == null) {
			throw new LedgerCommandValidationException(field + "不能为空。");
		}
	}

	private static LedgerCommandValidationException invalid(String message) {
		return new LedgerCommandValidationException(message);
	}

	private static UUID initialTransactionId(UUID requestedId) {
		return requestedId == null ? UUID.randomUUID() : requestedId;
	}
}
