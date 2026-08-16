package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
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

/** 五类账务语义命令的应用编排；不包含 HTTP、Spring、jOOQ 或余额投影。 */
public final class LedgerCommandApplicationService {

	private final TransactionRunner transactions;
	private final AccountPostingReferencePort accounts;
	private final AccountPostingAccessPort accountAccess;
	private final CategoryStore categories;
	private final LedgerAccountStore ledgerAccounts;
	private final LedgerTransactionStore ledgerTransactions;
	private final LedgerTransactionFactory transactionFactory;
	private final Clock clock;

	public LedgerCommandApplicationService(
		TransactionRunner transactions,
		AccountPostingReferencePort accounts,
		AccountPostingAccessPort accountAccess,
		CategoryStore categories,
		LedgerAccountStore ledgerAccounts,
		LedgerTransactionStore ledgerTransactions,
		PostingService postingService,
		Clock clock) {
		if (transactions == null || accounts == null || accountAccess == null || categories == null
			|| ledgerAccounts == null || ledgerTransactions == null || postingService == null || clock == null) {
			throw new LedgerCommandValidationException("账务命令服务依赖不能为空。");
		}
		this.transactions = transactions;
		this.accounts = accounts;
		this.accountAccess = accountAccess;
		this.categories = categories;
		this.ledgerAccounts = ledgerAccounts;
		this.ledgerTransactions = ledgerTransactions;
		this.transactionFactory = new LedgerTransactionFactory(postingService);
		this.clock = clock;
	}

	public Transaction postIncome(IncomeCommand command) {
		require(command, "收入命令");
		return transactions.required(() -> {
			AccountPostingReference account = editableAccount(command.userId(), command.accountId(), command.businessAt());
			LedgerAccountReference accountLedger = primary(account);
			requireAssetAccount(account, "收入");
			validateAmount(command.amount(), accountLedger.currency());
			requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.INCOME);
			requireSystem(command.incomeLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.INCOME);

			Transaction transaction = transactionFactory.createPosted(
				UUID.randomUUID(),
				TransactionType.INCOME,
				TransactionSource.MANUAL,
				command.businessAt(),
				command.businessDate(),
				command.timezone(),
				clock.instant(),
				List.of(
					new LedgerEntrySpec(accountLedger.id(), LedgerDirection.DEBIT, command.amount()),
					new LedgerEntrySpec(command.incomeLedgerAccountId(), LedgerDirection.CREDIT, command.amount())));
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, command.userId(), command.counterparty(), null, command.note(),
				command.categoryId(), new NoTransactionDetails()));
			return transaction;
		});
	}

	public Transaction postExpense(ExpenseCommand command) {
		require(command, "支出命令");
		return transactions.required(() -> {
			AccountPostingReference account = editableAccount(command.userId(), command.accountId(), command.businessAt());
			LedgerAccountReference accountLedger = primary(account);
			validateAmount(command.amount(), accountLedger.currency());
			requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.EXPENSE);
			requireSystem(command.expenseLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EXPENSE);

			Transaction transaction = transactionFactory.createPosted(
				UUID.randomUUID(),
				TransactionType.EXPENSE,
				TransactionSource.MANUAL,
				command.businessAt(),
				command.businessDate(),
				command.timezone(),
				clock.instant(),
				List.of(
					new LedgerEntrySpec(command.expenseLedgerAccountId(), LedgerDirection.DEBIT, command.amount()),
					new LedgerEntrySpec(accountLedger.id(), LedgerDirection.CREDIT, command.amount())));
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, command.userId(), null, command.merchant(), command.note(),
				command.categoryId(), new NoTransactionDetails()));
			return transaction;
		});
	}

	public Transaction postRefund(RefundCommand command) {
		require(command, "退款命令");
		return transactions.required(() -> {
			AccountPostingReference refundAccount = editableAccount(command.userId(), command.accountId(), command.businessAt());
			LedgerAccountReference refundLedger = primary(refundAccount);
			requireAssetAccount(refundAccount, "退款");
			validateAmount(command.amount(), refundLedger.currency());
			LedgerTransactionStore.RefundCandidate original = ledgerTransactions
				.findRefundCandidate(command.originalTransactionId())
				.orElseThrow(() -> invalid("原支出交易不存在或不可退款。"));
			if (!accountAccess.mayPost(command.userId(), original.originalAccountId(), command.businessAt())) {
				throw invalid("无权操作原支出交易。");
			}
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
				UUID.randomUUID(),
				TransactionType.REFUND,
				TransactionSource.MANUAL,
				command.businessAt(),
				command.businessDate(),
				command.timezone(),
				clock.instant(),
				List.of(
					new LedgerEntrySpec(refundLedger.id(), LedgerDirection.DEBIT, command.amount()),
					new LedgerEntrySpec(original.expenseLedgerAccountId(), LedgerDirection.CREDIT, command.amount())));
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, command.userId(), null, null, command.note(), null,
				new RefundWriteDetails(command.originalTransactionId(), original.categoryId())));
			return transaction;
		});
	}

	public Transaction postTransfer(TransferCommand command) {
		require(command, "转账命令");
		return transactions.required(() -> {
			AccountPostingReference from = editableAccount(command.userId(), command.fromAccountId(), command.businessAt());
			AccountPostingReference to = editableAccount(command.userId(), command.toAccountId(), command.businessAt());
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
				LedgerAccountReference feeLedger = requireSystem(
					command.feeLedgerAccountId(), command.userId(), fromLedger.currency(), LedgerAccountNature.EXPENSE);
				if (command.feeCategoryId() != null) {
					requireCategory(command.feeCategoryId(), command.userId(), command.fromAccountId(), CategoryType.EXPENSE);
					categoryId = command.feeCategoryId();
				}
				entries.add(new LedgerEntrySpec(feeLedger.id(), LedgerDirection.DEBIT, fee));
				entries.add(new LedgerEntrySpec(fromLedger.id(), LedgerDirection.CREDIT, fee));
			} else if (command.feeCategoryId() != null || command.feeLedgerAccountId() != null) {
				throw invalid("无手续费时不能提供手续费科目或分类。");
			}
			Transaction transaction = transactionFactory.createPosted(
				UUID.randomUUID(),
				TransactionType.TRANSFER,
				TransactionSource.MANUAL,
				command.businessAt(),
				command.businessDate(),
				command.timezone(),
				clock.instant(),
				entries);
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, command.userId(), null, null, command.note(), categoryId,
				new TransferWriteDetails(
					command.fromAccountId(), command.toAccountId(), command.amount(), command.amount(), fee)));
			return transaction;
		});
	}

	public Transaction postBalanceAdjustment(BalanceAdjustmentCommand command) {
		require(command, "余额调整命令");
		return transactions.required(() -> {
			AccountPostingReference account = editableAccount(command.userId(), command.accountId(), command.businessAt());
			LedgerAccountReference accountLedger = primary(account);
			validateBalance(command.actualBalance(), accountLedger.currency());
			LedgerAccountReference equityLedger = requireSystem(
				command.equityLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EQUITY);
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
			ledgerTransactions.persistPosted(new PostedTransactionWrite(
				transaction, command.userId(), null, null, null, null,
				new BalanceAdjustmentWriteDetails(
					command.accountId(), before, command.actualBalance(), difference, command.reason())));
			return transaction;
		});
	}

	/** 按既有 B1 语义生成“冲正 + 新版本”事实链，不接受任意外部借贷分录。 */
	public TransactionRevisionResult revisePostedTransaction(RevisePostedTransactionCommand command) {
		require(command, "交易修订命令");
		return transactions.required(() -> {
			LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
			Transaction original = snapshot.transaction();
			requireExpectedVersion(command.expectedEntityVersion(), snapshot.entityVersion());
			requireIndependentOriginal(snapshot);
			validateOriginalAccess(command.userId(), original, command.businessAt());
			RevisionBuild build = buildRevision(command, snapshot);

			Transaction reversal = transactionFactory.createReversal(original, UUID.randomUUID(), clock.instant());
			Transaction replacement = transactionFactory.createPostedVersion(
				UUID.randomUUID(), original.type(), original.source(), command.businessAt(), command.businessDate(),
				command.timezone(), clock.instant(), original.rootTransactionId(), original.transactionId(),
				original.versionNo() + 1, build.entries());
			ledgerTransactions.persistRevision(new LedgerTransactionStore.TransactionRevisionWrite(
				original.transactionId(), snapshot.entityVersion(), command.reason(), reversal,
				new PostedTransactionWrite(replacement, command.userId(), command.counterparty(), command.merchant(),
					command.note(), build.categoryId(), build.details())));
			return new TransactionRevisionResult(original.transactionId(), reversal, replacement);
		});
	}

	/** 作废通过新增冲正事实完成，原 POSTED 交易只发生允许的状态迁移。 */
	public TransactionVoidResult voidPostedTransaction(VoidPostedTransactionCommand command) {
		require(command, "交易作废命令");
		return transactions.required(() -> {
			LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
			Transaction original = snapshot.transaction();
			requireExpectedVersion(command.expectedEntityVersion(), snapshot.entityVersion());
			requireIndependentOriginal(snapshot);
			validateOriginalAccess(command.userId(), original, original.businessAt());

			Transaction reversal = transactionFactory.createReversal(original, UUID.randomUUID(), clock.instant());
			ledgerTransactions.persistVoid(new LedgerTransactionStore.TransactionVoidWrite(
				original.transactionId(), snapshot.entityVersion(), command.userId(), command.reason(), reversal));
			return new TransactionVoidResult(original.transactionId(), reversal);
		});
	}

	private AccountPostingReference editableAccount(UUID userId, UUID accountId, java.time.Instant businessAt) {
		AccountPostingReference account = accounts.findById(accountId)
			.orElseThrow(() -> invalid("账户不存在。"));
		if (!account.active()) {
			throw invalid("归档账户不能新增账务交易。");
		}
		if (!accountAccess.mayPost(userId, accountId, businessAt)) {
			throw invalid("当前成员无权写入该账户。");
		}
		return account;
	}

	private LedgerTransactionStore.PostedTransactionSnapshot postedForMutation(UUID transactionId) {
		return ledgerTransactions.findPostedForMutation(transactionId)
			.orElseThrow(() -> invalid("交易不存在或不是可修改的已确认交易。"));
	}

	private static void requireExpectedVersion(int expected, int actual) {
		if (expected != actual) {
			throw invalid("交易版本已变化。");
		}
	}

	private static void requireIndependentOriginal(LedgerTransactionStore.PostedTransactionSnapshot snapshot) {
		if (snapshot.hasDependentFacts() || snapshot.transaction().type() == TransactionType.REVERSAL
			|| snapshot.transaction().status() != TransactionStatus.POSTED) {
			throw invalid("交易已有关联后续事实，不能修改或作废。");
		}
	}

	private void validateOriginalAccess(UUID userId, Transaction transaction, java.time.Instant effectiveAt) {
		for (LedgerEntry entry : transaction.entries()) {
			LedgerAccountReference ledgerAccount = ledgerAccounts.findById(entry.ledgerAccountId())
				.orElseThrow(() -> invalid("原交易账务科目不存在。"));
			if (!ledgerAccount.active()) {
				throw invalid("原交易账务科目不可用。");
			}
			if (ledgerAccount.visibleAccountId() != null) {
				accounts.findById(ledgerAccount.visibleAccountId())
					.orElseThrow(() -> invalid("原交易账户不存在。"));
				if (!accountAccess.mayPost(userId, ledgerAccount.visibleAccountId(), effectiveAt)) {
					throw invalid("当前成员无权修改或作废该交易。");
				}
			} else if (ledgerAccount.role() != LedgerAccountRole.SYSTEM
				|| !userId.equals(ledgerAccount.ownerUserId())) {
				throw invalid("原交易系统科目不属于当前用户。");
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
		};
	}

	private RevisionBuild buildIncomeRevision(
		RevisePostedTransactionCommand command,
		Transaction original,
		TransactionRevisionDetails.Income details) {
		if (original.type() != TransactionType.INCOME) {
			throw invalid("收入语义载荷与原交易类型不匹配。");
		}
		LedgerAccountReference accountLedger = originalVisibleLedger(original);
		AccountPostingReference account = visibleAccountForMutation(accountLedger, command.userId(), command.businessAt());
		requireAssetAccount(account, "收入");
		validateAmount(details.amount(), accountLedger.currency());
		requireCategory(details.categoryId(), command.userId(), account.id(), CategoryType.INCOME);
		LedgerAccountReference incomeLedger = requireSystem(
			details.incomeLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.INCOME);
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
		LedgerAccountReference accountLedger = originalVisibleLedger(original);
		AccountPostingReference account = visibleAccountForMutation(accountLedger, command.userId(), command.businessAt());
		validateAmount(details.amount(), accountLedger.currency());
		requireCategory(details.categoryId(), command.userId(), account.id(), CategoryType.EXPENSE);
		LedgerAccountReference expenseLedger = requireSystem(
			details.expenseLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EXPENSE);
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
		AccountPostingReference from = editableAccount(command.userId(), details.fromAccountId(), command.businessAt());
		AccountPostingReference to = editableAccount(command.userId(), details.toAccountId(), command.businessAt());
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
			LedgerAccountReference feeLedger = requireSystem(
				details.feeLedgerAccountId(), command.userId(), fromLedger.currency(), LedgerAccountNature.EXPENSE);
			if (details.feeCategoryId() != null) {
				requireCategory(details.feeCategoryId(), command.userId(), details.fromAccountId(), CategoryType.EXPENSE);
				categoryId = details.feeCategoryId();
			}
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
		AccountPostingReference refundAccount = editableAccount(command.userId(), details.accountId(), command.businessAt());
		LedgerAccountReference refundLedger = primary(refundAccount);
		requireAssetAccount(refundAccount, "退款");
		validateAmount(details.amount(), refundLedger.currency());
		LedgerTransactionStore.RefundCandidate candidate = ledgerTransactions
			.findRefundCandidate(details.originalTransactionId())
			.orElseThrow(() -> invalid("原支出交易不存在或不可退款。"));
		if (!accountAccess.mayPost(command.userId(), candidate.originalAccountId(), command.businessAt())) {
			throw invalid("无权操作原支出交易。");
		}
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
		AccountPostingReference account = editableAccount(command.userId(), details.accountId(), command.businessAt());
		LedgerAccountReference accountLedger = primary(account);
		validateBalance(details.actualBalance(), accountLedger.currency());
		LedgerAccountReference equityLedger = requireSystem(
			details.equityLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EQUITY);
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

	private LedgerAccountReference originalVisibleLedger(Transaction transaction) {
		return transaction.entries().stream()
			.map(entry -> ledgerAccounts.findById(entry.ledgerAccountId()).orElse(null))
			.filter(reference -> reference != null && reference.visibleAccountId() != null
				&& reference.role() == LedgerAccountRole.PRIMARY)
			.findFirst()
			.orElseThrow(() -> invalid("原交易缺少可见账户主科目。"));
	}

	private AccountPostingReference visibleAccountForMutation(
		LedgerAccountReference ledgerAccount, UUID userId, java.time.Instant effectiveAt) {
		AccountPostingReference account = accounts.findById(ledgerAccount.visibleAccountId())
			.orElseThrow(() -> invalid("原交易账户不存在。"));
		if (!accountAccess.mayPost(userId, account.id(), effectiveAt)) {
			throw invalid("当前成员无权修改该交易。");
		}
		return account;
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

	private static void require(Object command, String field) {
		if (command == null) {
			throw new LedgerCommandValidationException(field + "不能为空。");
		}
	}

	private static LedgerCommandValidationException invalid(String message) {
		return new LedgerCommandValidationException(message);
	}
}
