package app.ziji.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

/** 五类账务语义命令的应用编排；不包含 HTTP、Spring、jOOQ 或余额投影。 */
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

	private Transaction postIncome(IncomeCommand command, UUID transactionId, TransactionSource source) {
		require(command, "收入命令");
		return transactions.required(() -> postIncomeInTransaction(command, transactionId, source));
	}

	private Transaction postIncomeInTransaction(IncomeCommand command, UUID transactionId, TransactionSource source) {
		AccountPostingReference account = editableAccount(command.userId(), command.accountId(), command.businessAt());
		LedgerAccountReference accountLedger = primary(account);
		requireAssetAccount(account, "收入");
		validateAmount(command.amount(), accountLedger.currency());
		requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.INCOME);
		requireSystem(command.incomeLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.INCOME);

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
				new LedgerEntrySpec(command.incomeLedgerAccountId(), LedgerDirection.CREDIT, command.amount())));
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), command.counterparty(), null, command.note(),
			command.categoryId(), new NoTransactionDetails()));
		return transaction;
	}

	public Transaction postExpense(ExpenseCommand command) {
		return postExpense(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	private Transaction postExpense(ExpenseCommand command, UUID transactionId, TransactionSource source) {
		require(command, "支出命令");
		return transactions.required(() -> postExpenseInTransaction(command, transactionId, source));
	}

	private Transaction postExpenseInTransaction(ExpenseCommand command, UUID transactionId, TransactionSource source) {
		AccountPostingReference account = editableAccount(command.userId(), command.accountId(), command.businessAt());
		LedgerAccountReference accountLedger = primary(account);
		validateAmount(command.amount(), accountLedger.currency());
		requireCategory(command.categoryId(), command.userId(), command.accountId(), CategoryType.EXPENSE);
		requireSystem(command.expenseLedgerAccountId(), command.userId(), accountLedger.currency(), LedgerAccountNature.EXPENSE);

		Transaction transaction = transactionFactory.createPosted(
			transactionId,
			TransactionType.EXPENSE,
			source,
			command.businessAt(),
			command.businessDate(),
			command.timezone(),
			clock.instant(),
			List.of(
				new LedgerEntrySpec(command.expenseLedgerAccountId(), LedgerDirection.DEBIT, command.amount()),
				new LedgerEntrySpec(accountLedger.id(), LedgerDirection.CREDIT, command.amount())));
		completeInitialPosting(new PostedTransactionWrite(
			transaction, command.userId(), null, command.merchant(), command.note(),
			command.categoryId(), new NoTransactionDetails()));
		return transaction;
	}

	public Transaction postRefund(RefundCommand command) {
		return postRefund(command, UUID.randomUUID(), TransactionSource.MANUAL);
	}

	private Transaction postRefund(RefundCommand command, UUID transactionId, TransactionSource source) {
		require(command, "退款命令");
		return transactions.required(() -> postRefundInTransaction(command, transactionId, source));
	}

	private Transaction postRefundInTransaction(RefundCommand command, UUID transactionId, TransactionSource source) {
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

	private Transaction postTransfer(TransferCommand command, UUID transactionId, TransactionSource source) {
		require(command, "转账命令");
		return transactions.required(() -> postTransferInTransaction(command, transactionId, source));
	}

	private Transaction postTransferInTransaction(TransferCommand command, UUID transactionId, TransactionSource source) {
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
				new TransactionRevisionDetails.Income(income.amount(), ensureCategoryCounter(
					revision.userId(), income.categoryId(), LedgerAccountNature.INCOME, income.amount().currency()).id(), income.categoryId()));
			case SyncLedgerCommand.Replacement.Expense expense -> new RevisePostedTransactionCommand(
				revision.userId(), revision.transactionId(), revision.expectedEntityVersion(), expense.businessAt(), expense.businessDate(),
				expense.timezone(), null, expense.merchant(), expense.note(), revision.reason(),
				new TransactionRevisionDetails.Expense(expense.amount(), ensureCategoryCounter(
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
		return transactions.required(() -> revisePostedTransactionInTransaction(command));
	}

	private TransactionRevisionResult revisePostedTransactionInTransaction(RevisePostedTransactionCommand command) {
		LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
		Transaction original = snapshot.transaction();
		requireIndependentOriginal(snapshot);
		validateOriginalAccess(command.userId(), original, command.businessAt());
		requireExpectedVersion(command.transactionId(), command.expectedEntityVersion(), snapshot.entityVersion());
		RevisionBuild build = buildRevision(command, snapshot);

		Transaction reversal = transactionFactory.createReversal(original, UUID.randomUUID(), clock.instant());
		Transaction replacement = transactionFactory.createPostedVersion(
			UUID.randomUUID(), original.type(), original.source(), command.businessAt(), command.businessDate(),
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
		return transactions.required(() -> voidPostedTransactionInTransaction(command));
	}

	private TransactionVoidResult voidPostedTransactionInTransaction(VoidPostedTransactionCommand command) {
		LedgerTransactionStore.PostedTransactionSnapshot snapshot = postedForMutation(command.transactionId());
		Transaction original = snapshot.transaction();
		requireIndependentOriginal(snapshot);
		validateOriginalAccess(command.userId(), original, original.businessAt());
		requireExpectedVersion(command.transactionId(), command.expectedEntityVersion(), snapshot.entityVersion());

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
