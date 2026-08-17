package app.ziji.ledger.interfaces;

import java.math.BigDecimal;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.ledger.application.BalanceAdjustmentCommand;
import app.ziji.ledger.application.ExpenseCommand;
import app.ziji.ledger.application.IncomeCommand;
import app.ziji.ledger.application.LedgerCommandApplicationService;
import app.ziji.ledger.application.LedgerCommandPreflightService;
import app.ziji.ledger.application.LedgerCommandValidationException;
import app.ziji.ledger.application.LedgerPermissionDeniedException;
import app.ziji.ledger.application.LedgerVersionConflictException;
import app.ziji.ledger.application.LiabilityBorrowingCommand;
import app.ziji.ledger.application.LiabilityRepaymentCommand;
import app.ziji.ledger.application.RefundCommand;
import app.ziji.ledger.application.RevisePostedTransactionCommand;
import app.ziji.ledger.application.TransactionQueryReadPort.TransactionSnapshot;
import app.ziji.ledger.application.TransactionQueryService;
import app.ziji.ledger.application.TransactionNotVisibleException;
import app.ziji.ledger.application.TransactionRevisionDetails;
import app.ziji.ledger.application.TransactionRevisionResult;
import app.ziji.ledger.application.TransactionVoidResult;
import app.ziji.ledger.application.TransferCommand;
import app.ziji.ledger.application.VoidPostedTransactionCommand;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.ledger.domain.LedgerDomainException;
import app.ziji.ledger.domain.Money;
import app.ziji.ledger.domain.Transaction;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.CurrentUserTimezonePort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 交易写 HTTP 边界；请求只转换为受控语义命令，绝不接收或拼接任意分录。 */
@RestController
public class TransactionCommandController {

	private static final int API_MAJOR_VERSION = 1;
	private static final String RESOURCE_TYPE = "TRANSACTION";
	private static final String POSITIVE_MONEY =
		"^(0*[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$|^0\\.(0[1-9]|[1-9][0-9]?)$";
	private static final String POSITIVE_MONEY_AMOUNT =
		"^([1-9][0-9]{0,21})(\\.[0-9]{1,2})?$|^0\\.(0[1-9]|[1-9][0-9]?)$";
	private static final String NON_NEGATIVE_MONEY = "^(0|[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$";
	private static final String SIGNED_MONEY = "^-?(0|[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$";
	private static final String RATE =
		"^(0*[1-9][0-9]{0,27})(\\.[0-9]{1,12})?$|^0\\.[0-9]*[1-9][0-9]{0,11}$";
	private static final Set<String> COMMON_FIELDS = Set.of(
		"id", "type", "businessAt", "businessDate", "timezone", "note", "tagIds");

	private final LedgerCommandApplicationService commands;
	private final LedgerCommandPreflightService preflight;
	private final TransactionQueryService queries;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final CurrentUserTimezonePort timezones;
	private final UnifiedIdempotencyService idempotency;

	public TransactionCommandController(
		LedgerCommandApplicationService commands,
		LedgerCommandPreflightService preflight,
		TransactionQueryService queries,
		CurrentUserIdResolver currentUserIdResolver,
		CurrentUserTimezonePort timezones,
		UnifiedIdempotencyService idempotency) {
		this.commands = commands;
		this.preflight = preflight;
		this.queries = queries;
		this.currentUserIdResolver = currentUserIdResolver;
		this.timezones = timezones;
		this.idempotency = idempotency;
	}

	@PostMapping(path = "/api/v1/transactions", consumes = MediaType.APPLICATION_JSON_VALUE, name = "postTransaction")
	public ResponseEntity<?> postTransaction(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		ParsedTransaction parsed = parseTransaction(body, userId);
		List<Transaction> related = relatedTransactions(userId, parsed.relatedTransactionIds(), null);
		preflight.requireWritable(userId, parsed.businessAt(), related, parsed.accountIds());
		parsed.validateSupportedBusinessRules();
		String resource = "/api/v1/transactions";
		IdempotencyExecution<Transaction> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "postTransaction", idempotencyKey(request),
			requestHash(resource, parsed.hashPayload(), null),
			() -> transactionWork(() -> parsed.post(commands, userId), null));
		return resolve(execution, userId, response);
	}

	@PostMapping(
		path = "/api/v1/transactions/{transactionId}/revisions",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		name = "reviseTransaction")
	public ResponseEntity<?> reviseTransaction(
		@PathVariable String transactionId,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedTransactionId = parseUuid(transactionId);
		ParsedRevision revision = parseRevision(body, userId);
		TransactionSnapshot original = queries.get(userId, parsedTransactionId);
		List<Transaction> related = relatedTransactions(
			userId, revision.replacement().relatedTransactionIds(), original.transaction());
		preflight.requireWritable(
			userId, revision.replacement().businessAt(), related, revision.replacement().accountIds());
		revision.replacement().validateSupportedBusinessRules();
		int expectedVersion = parseIfMatch(request);
		String canonicalIfMatch = etag(expectedVersion);
		String key = idempotencyKey(request);
		String resource = "/api/v1/transactions/" + parsedTransactionId + "/revisions";
		String hash = requestHash(resource, revision.hashPayload(), canonicalIfMatch);
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, API_MAJOR_VERSION, "reviseTransaction", key, hash);
		if (inspected.isPresent()) {
			return resolve(inspected.get(), userId, response);
		}
		if (original.entityVersion() != expectedVersion) {
			return resolve(versionConflictExecution(
				userId, "reviseTransaction", key, hash, parsedTransactionId, original.entityVersion()), userId, response);
		}
		IdempotencyExecution<Transaction> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "reviseTransaction", key, hash, () -> revisionWork(
				() -> commands.revisePostedTransaction(revision.command(userId, parsedTransactionId, expectedVersion)),
				parsedTransactionId));
		return resolve(execution, userId, response);
	}

	@PostMapping(
		path = "/api/v1/transactions/{transactionId}/reversal",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		name = "reverseTransaction")
	public ResponseEntity<?> reverseTransaction(
		@PathVariable String transactionId,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedTransactionId = parseUuid(transactionId);
		ParsedReason parsed = parseReason(body);
		TransactionSnapshot original = queries.get(userId, parsedTransactionId);
		preflight.requireWritable(
			userId, original.transaction().businessAt(), List.of(original.transaction()), List.of());
		int expectedVersion = parseIfMatch(request);
		String canonicalIfMatch = etag(expectedVersion);
		String key = idempotencyKey(request);
		String resource = "/api/v1/transactions/" + parsedTransactionId + "/reversal";
		String hash = requestHash(resource, parsed.hashPayload(), canonicalIfMatch);
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, API_MAJOR_VERSION, "reverseTransaction", key, hash);
		if (inspected.isPresent()) {
			return resolve(inspected.get(), userId, response);
		}
		if (original.entityVersion() != expectedVersion) {
			return resolve(versionConflictExecution(
				userId, "reverseTransaction", key, hash, parsedTransactionId, original.entityVersion()), userId, response);
		}
		IdempotencyExecution<Transaction> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "reverseTransaction", key, hash, () -> voidWork(
				() -> commands.voidPostedTransaction(new VoidPostedTransactionCommand(
					userId, parsedTransactionId, expectedVersion, parsed.reason())), parsedTransactionId));
		return resolve(execution, userId, response);
	}

	@PostMapping(
		path = "/api/v1/accounts/{accountId}/balance-adjustments",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		name = "createBalanceAdjustment")
	public ResponseEntity<?> createBalanceAdjustment(
		@PathVariable String accountId,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		ParsedBalanceAdjustment parsed = parseBalanceAdjustment(body);
		var accounts = preflight.requireWritable(userId, parsed.businessAt(), parsedAccountId);
		CurrencyCode currency;
		try {
			currency = CurrencyCode.fromCode(accounts.get(parsedAccountId).currency());
		} catch (LedgerDomainException exception) {
			throw new TransactionApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", false);
		}
		Money actualBalance = new Money(parsed.actualBalance(), currency);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/balance-adjustments";
		IdempotencyExecution<Transaction> execution = idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, "createBalanceAdjustment", idempotencyKey(request),
			requestHash(resource, parsed.hashPayload(), null),
			() -> transactionWork(() -> commands.postBalanceAdjustment(new BalanceAdjustmentCommand(
				userId, parsedAccountId, actualBalance, parsed.businessAt(), parsed.businessDate(),
				parsed.timezone(), parsed.reason())), null));
		return resolve(execution, userId, response);
	}

	private IdempotencyWorkResult<Transaction> transactionWork(
		Supplier<Transaction> work,
		UUID conflictResourceId) {
		try {
			Transaction transaction = work.get();
			return IdempotencyWorkResult.completed(transaction, succeeded(transaction));
		} catch (TransactionNotVisibleException | LedgerPermissionDeniedException exception) {
			// 并发权限变化必须穿透 REQUIRED 幂等事务，不能写成 FAILED_FINAL 422。
			throw exception;
		} catch (LedgerVersionConflictException conflict) {
			UUID resourceId = conflictResourceId == null ? conflict.transactionId() : conflictResourceId;
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinalVersionConflict(
				409, conflict.currentVersion(), transactionLocation(resourceId)));
		} catch (LedgerCommandValidationException | LedgerDomainException exception) {
			return IdempotencyWorkResult.completed(
				null, IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"));
		}
	}

	private IdempotencyWorkResult<Transaction> revisionWork(
		Supplier<TransactionRevisionResult> work,
		UUID originalTransactionId) {
		return transactionWork(() -> work.get().replacement(), originalTransactionId);
	}

	private IdempotencyWorkResult<Transaction> voidWork(
		Supplier<TransactionVoidResult> work,
		UUID originalTransactionId) {
		return transactionWork(() -> work.get().reversal(), originalTransactionId);
	}

	private IdempotencyExecution<Transaction> versionConflictExecution(
		UUID userId,
		String operationId,
		String key,
		String hash,
		UUID transactionId,
		int currentVersion) {
		return idempotency.executeAuthenticated(
			userId, API_MAJOR_VERSION, operationId, key, hash,
			() -> IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinalVersionConflict(
				409, currentVersion, transactionLocation(transactionId))));
	}

	private ResponseEntity<?> resolve(
		IdempotencyExecution<?> execution,
		UUID userId,
		HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.KEY_REUSED) {
			throw new TransactionApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", false);
		}
		if (execution.status() == IdempotencyExecution.Status.REQUEST_IN_PROGRESS) {
			throw new TransactionApiProblemException(
				HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", true);
		}
		if (execution.status() == IdempotencyExecution.Status.SAFE_REPLAY_UNAVAILABLE) {
			throw internal();
		}
		IdempotencyResponse stored = execution.response();
		if (stored == null) {
			throw internal();
		}
		if (stored.status() != IdempotencyResponse.Status.SUCCEEDED) {
			throw storedProblem(stored);
		}
		if (stored.responseStatus() != 201 || !RESOURCE_TYPE.equals(stored.resourceType())
			|| stored.resourceId() == null
			|| !(stored.reference() instanceof IdempotencyResponse.ResourceReference reference)
			|| reference.resourceVersion() == null) {
			throw internal();
		}
		String location = transactionLocation(stored.resourceId());
		if (!location.equals(reference.location())) {
			throw internal();
		}
		TransactionSnapshot snapshot;
		try {
			snapshot = queries.get(userId, stored.resourceId());
		} catch (RuntimeException exception) {
			// 成功幂等引用只有在同一可见、同一版本快照仍可证明时才能重建。
			throw internal();
		}
		if (reference.resourceVersion() != snapshot.entityVersion()
			|| !etag(snapshot.entityVersion()).equals(reference.etag())
			|| execution.status() == IdempotencyExecution.Status.EXECUTED
				&& (!(execution.value() instanceof Transaction transaction)
					|| !transaction.transactionId().equals(stored.resourceId()))) {
			throw internal();
		}
		return ResponseEntity.created(URI.create(location)).eTag(reference.etag())
			.body(new TransactionController.TransactionEnvelope(
				TransactionController.view(snapshot),
				new TransactionController.ResponseMeta(requestId(response))));
	}

	private TransactionApiProblemException storedProblem(IdempotencyResponse response) {
		if (response.reference() instanceof IdempotencyResponse.VersionConflictReference conflict
			&& response.status() == IdempotencyResponse.Status.FAILED_FINAL
			&& response.responseStatus() == 409) {
			return new TransactionApiProblemException(conflict);
		}
		if (response.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			if (response.responseStatus() == 422 && "BUSINESS_RULE_VIOLATION".equals(problem.errorCode())
				&& response.status() == IdempotencyResponse.Status.FAILED_FINAL) {
				return new TransactionApiProblemException(
					HttpStatus.UNPROCESSABLE_CONTENT, problem.errorCode(), false);
			}
			if (response.responseStatus() == 500 && "INTERNAL_ERROR".equals(problem.errorCode())
				&& response.status() == IdempotencyResponse.Status.FAILED_RETRYABLE) {
				return new TransactionApiProblemException(
					HttpStatus.INTERNAL_SERVER_ERROR, problem.errorCode(), true);
			}
		}
		return internal();
	}

	private static TransactionApiProblemException internal() {
		return new TransactionApiProblemException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", false);
	}

	private List<Transaction> relatedTransactions(
		UUID userId,
		List<UUID> transactionIds,
		Transaction requiredTransaction) {
		Map<UUID, Transaction> related = new LinkedHashMap<>();
		if (requiredTransaction != null) {
			related.put(requiredTransaction.transactionId(), requiredTransaction);
		}
		for (UUID transactionId : transactionIds) {
			related.put(transactionId, queries.get(userId, transactionId).transaction());
		}
		return List.copyOf(related.values());
	}

	private ParsedTransaction parseTransaction(JsonNode body, UUID userId) {
		if (body == null || !body.isObject()) {
			throw invalid();
		}
		RequestType type = parseType(body.get("type"));
		Set<String> allowed = new LinkedHashSet<>(COMMON_FIELDS);
		allowed.addAll(type.specificFields());
		assertAllowed(body, allowed);
		CommonFields common = parseCommon(body, userId, type);
		SemanticPayload payload = switch (type) {
			case INCOME -> parseIncome(body, common.hashPayload());
			case EXPENSE -> parseExpense(body, common.hashPayload());
			case REFUND -> parseRefund(body, common.hashPayload());
			case TRANSFER -> parseTransfer(body, common.hashPayload());
			case LIABILITY_BORROWING -> parseBorrowing(body, common.hashPayload());
			case LIABILITY_REPAYMENT -> parseRepayment(body, common.hashPayload());
		};
		return new ParsedTransaction(common, payload);
	}

	private ParsedRevision parseRevision(JsonNode body, UUID userId) {
		validateExactObject(body, Set.of("reason", "replacement"));
		String reason = requiredText(body.get("reason"), 500, false);
		ParsedTransaction replacement = parseTransaction(body.get("replacement"), userId);
		Map<String, Object> hash = new LinkedHashMap<>();
		hash.put("reason", reason);
		hash.put("replacement", replacement.hashPayload());
		return new ParsedRevision(reason, replacement, immutable(hash));
	}

	private ParsedReason parseReason(JsonNode body) {
		validateExactObject(body, Set.of("reason"));
		String reason = requiredText(body.get("reason"), 500, false);
		return new ParsedReason(reason, Map.of("reason", reason));
	}

	private ParsedBalanceAdjustment parseBalanceAdjustment(JsonNode body) {
		validateExactObject(body, Set.of("actualBalance", "businessAt", "timezone", "reason"));
		BigDecimal actualBalance = parseDecimal(body.get("actualBalance"), SIGNED_MONEY);
		Instant businessAt = parseInstant(body.get("businessAt"));
		String timezone = requiredText(body.get("timezone"), 64, false);
		ZoneId zone = parseZone(timezone);
		String reason = requiredText(body.get("reason"), 500, false);
		Map<String, Object> hash = new LinkedHashMap<>();
		hash.put("actualBalance", decimal(actualBalance));
		hash.put("businessAt", businessAt);
		hash.put("timezone", timezone);
		hash.put("reason", reason);
		return new ParsedBalanceAdjustment(
			actualBalance, businessAt, businessAt.atZone(zone).toLocalDate(), zone.getId(), reason, immutable(hash));
	}

	private CommonFields parseCommon(JsonNode body, UUID userId, RequestType type) {
		requireFields(body, "type", "businessAt");
		UUID requestedId = body.has("id") ? parseUuidNode(body.get("id")) : null;
		Instant businessAt = parseInstant(body.get("businessAt"));
		String suppliedTimezone = body.has("timezone")
			? nullableText(body.get("timezone"), 64, false) : null;
		ZoneId zone = suppliedTimezone == null ? timezones.currentTimezone(userId) : parseZone(suppliedTimezone);
		if (zone == null) {
			throw invalid();
		}
		LocalDate derivedDate = businessAt.atZone(zone).toLocalDate();
		LocalDate businessDate = body.has("businessDate") ? parseDate(body.get("businessDate")) : derivedDate;
		if (!derivedDate.equals(businessDate)) {
			throw invalid();
		}
		String note = body.has("note") ? nullableText(body.get("note"), 2000, true) : null;
		List<UUID> tagIds = body.has("tagIds") ? parseTagIds(body.get("tagIds")) : List.of();
		Map<String, Object> hash = new LinkedHashMap<>();
		putIfPresent(hash, body, "id", requestedId);
		hash.put("type", type);
		hash.put("businessAt", businessAt);
		putIfPresent(hash, body, "businessDate", businessDate);
		putIfPresent(hash, body, "timezone", suppliedTimezone);
		putIfPresent(hash, body, "note", note);
		putIfPresent(hash, body, "tagIds", tagIds);
		return new CommonFields(
			requestedId, businessAt, businessDate, zone.getId(), note, tagIds, immutable(hash));
	}

	private SemanticPayload parseIncome(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "accountId", "amount", "currency", "categoryId");
		UUID accountId = parseUuidNode(body.get("accountId"));
		CurrencyCode currency = parseCurrency(body.get("currency"));
		Money amount = parseMoney(body.get("amount"), currency, true);
		UUID categoryId = parseUuidNode(body.get("categoryId"));
		String counterparty = body.has("counterparty")
			? nullableText(body.get("counterparty"), 200, true) : null;
		Map<String, Object> hash = copy(commonHash);
		hash.put("accountId", accountId);
		hash.put("amount", decimal(amount.amount()));
		hash.put("currency", currency);
		hash.put("categoryId", categoryId);
		putIfPresent(hash, body, "counterparty", counterparty);
		return new IncomePayload(accountId, amount, categoryId, counterparty, immutable(hash));
	}

	private SemanticPayload parseExpense(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "accountId", "amount", "currency", "categoryId");
		UUID accountId = parseUuidNode(body.get("accountId"));
		CurrencyCode currency = parseCurrency(body.get("currency"));
		Money amount = parseMoney(body.get("amount"), currency, true);
		UUID categoryId = parseUuidNode(body.get("categoryId"));
		String merchant = body.has("merchant") ? nullableText(body.get("merchant"), 200, true) : null;
		Map<String, Object> hash = copy(commonHash);
		hash.put("accountId", accountId);
		hash.put("amount", decimal(amount.amount()));
		hash.put("currency", currency);
		hash.put("categoryId", categoryId);
		putIfPresent(hash, body, "merchant", merchant);
		return new ExpensePayload(accountId, amount, categoryId, merchant, immutable(hash));
	}

	private SemanticPayload parseRefund(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "accountId", "amount", "currency", "originalTransactionId");
		UUID accountId = parseUuidNode(body.get("accountId"));
		CurrencyCode currency = parseCurrency(body.get("currency"));
		Money amount = parseMoney(body.get("amount"), currency, true);
		UUID originalTransactionId = parseUuidNode(body.get("originalTransactionId"));
		Map<String, Object> hash = copy(commonHash);
		hash.put("accountId", accountId);
		hash.put("amount", decimal(amount.amount()));
		hash.put("currency", currency);
		hash.put("originalTransactionId", originalTransactionId);
		return new RefundPayload(accountId, originalTransactionId, amount, immutable(hash));
	}

	private SemanticPayload parseTransfer(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "fromAccountId", "toAccountId", "fromAmount", "toAmount", "fee");
		UUID fromAccountId = parseUuidNode(body.get("fromAccountId"));
		UUID toAccountId = parseUuidNode(body.get("toAccountId"));
		ParsedMoneyAmount fromAmount = parseMoneyAmount(body.get("fromAmount"), true);
		ParsedMoneyAmount toAmount = parseMoneyAmount(body.get("toAmount"), true);
		ParsedMoneyAmount fee = parseMoneyAmount(body.get("fee"), false);
		UUID feeCategoryId = body.has("feeCategoryId") ? nullableUuid(body.get("feeCategoryId")) : null;
		BigDecimal exchangeRate = body.has("exchangeRate")
			? nullableDecimal(body.get("exchangeRate"), RATE) : null;
		Map<String, Object> hash = copy(commonHash);
		hash.put("fromAccountId", fromAccountId);
		hash.put("toAccountId", toAccountId);
		hash.put("fromAmount", fromAmount.hashPayload());
		hash.put("toAmount", toAmount.hashPayload());
		hash.put("fee", fee.hashPayload());
		putIfPresent(hash, body, "feeCategoryId", feeCategoryId);
		putIfPresent(hash, body, "exchangeRate", exchangeRate == null ? null : decimal(exchangeRate));
		return new TransferPayload(
			fromAccountId, toAccountId, fromAmount.money(), toAmount.money(), fee.money(),
			feeCategoryId, exchangeRate, immutable(hash));
	}

	private SemanticPayload parseBorrowing(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "assetAccountId", "liabilityAccountId", "currency", "amount");
		UUID assetAccountId = parseUuidNode(body.get("assetAccountId"));
		UUID liabilityAccountId = parseUuidNode(body.get("liabilityAccountId"));
		CurrencyCode currency = parseCurrency(body.get("currency"));
		Money amount = parseMoney(body.get("amount"), currency, true);
		Map<String, Object> hash = copy(commonHash);
		hash.put("assetAccountId", assetAccountId);
		hash.put("liabilityAccountId", liabilityAccountId);
		hash.put("currency", currency);
		hash.put("amount", decimal(amount.amount()));
		return new BorrowingPayload(assetAccountId, liabilityAccountId, amount, immutable(hash));
	}

	private SemanticPayload parseRepayment(JsonNode body, Map<String, Object> commonHash) {
		requireFields(body, "cashAccountId", "liabilityAccountId", "currency",
			"principalAmount", "interestAmount", "feeAmount");
		UUID cashAccountId = parseUuidNode(body.get("cashAccountId"));
		UUID liabilityAccountId = parseUuidNode(body.get("liabilityAccountId"));
		CurrencyCode currency = parseCurrency(body.get("currency"));
		Money principal = parseMoney(body.get("principalAmount"), currency, true);
		Money interest = parseMoney(body.get("interestAmount"), currency, false);
		Money fee = parseMoney(body.get("feeAmount"), currency, false);
		UUID interestCategoryId = body.has("interestCategoryId")
			? nullableUuid(body.get("interestCategoryId")) : null;
		UUID feeCategoryId = body.has("feeCategoryId") ? nullableUuid(body.get("feeCategoryId")) : null;
		Map<String, Object> hash = copy(commonHash);
		hash.put("cashAccountId", cashAccountId);
		hash.put("liabilityAccountId", liabilityAccountId);
		hash.put("currency", currency);
		hash.put("principalAmount", decimal(principal.amount()));
		hash.put("interestAmount", decimal(interest.amount()));
		hash.put("feeAmount", decimal(fee.amount()));
		putIfPresent(hash, body, "interestCategoryId", interestCategoryId);
		putIfPresent(hash, body, "feeCategoryId", feeCategoryId);
		return new RepaymentPayload(
			cashAccountId, liabilityAccountId, principal, interest, fee,
			interestCategoryId, feeCategoryId, immutable(hash));
	}

	private ParsedMoneyAmount parseMoneyAmount(JsonNode node, boolean positive) {
		validateExactObject(node, Set.of("amount", "currency"));
		CurrencyCode currency = parseCurrency(node.get("currency"));
		BigDecimal amount = parseDecimal(node.get("amount"), positive ? POSITIVE_MONEY_AMOUNT : NON_NEGATIVE_MONEY);
		Money money = new Money(amount, currency);
		if (!money.hasPostingPrecision()) {
			throw invalid();
		}
		Map<String, Object> hash = new LinkedHashMap<>();
		hash.put("amount", decimal(money.amount()));
		hash.put("currency", currency);
		return new ParsedMoneyAmount(money, immutable(hash));
	}

	private Money parseMoney(JsonNode node, CurrencyCode currency, boolean positive) {
		BigDecimal amount = parseDecimal(node, positive ? POSITIVE_MONEY : NON_NEGATIVE_MONEY);
		Money money = new Money(amount, currency);
		if (!money.hasPostingPrecision()) {
			throw invalid();
		}
		return money;
	}

	private CurrencyCode parseCurrency(JsonNode node) {
		if (node == null || !node.isTextual() || !node.textValue().matches("^[A-Z]{3}$")) {
			throw invalid();
		}
		try {
			return CurrencyCode.fromCode(node.textValue());
		} catch (LedgerDomainException exception) {
			throw invalid();
		}
	}

	private BigDecimal parseDecimal(JsonNode node, String pattern) {
		if (node == null || !node.isTextual() || !node.textValue().matches(pattern)) {
			throw invalid();
		}
		try {
			return new BigDecimal(node.textValue());
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private BigDecimal nullableDecimal(JsonNode node, String pattern) {
		return node == null || node.isNull() ? null : parseDecimal(node, pattern);
	}

	private List<UUID> parseTagIds(JsonNode node) {
		if (node == null || !node.isArray() || node.size() > 20) {
			throw invalid();
		}
		List<UUID> values = new ArrayList<>();
		Set<UUID> unique = new LinkedHashSet<>();
		for (JsonNode value : node) {
			UUID id = parseUuidNode(value);
			if (!unique.add(id)) {
				throw invalid();
			}
			values.add(id);
		}
		return List.copyOf(values);
	}

	private RequestType parseType(JsonNode node) {
		if (node == null || !node.isTextual()) {
			throw invalid();
		}
		try {
			return RequestType.valueOf(node.textValue());
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private Instant parseInstant(JsonNode node) {
		if (node == null || !node.isTextual()) {
			throw invalid();
		}
		try {
			return OffsetDateTime.parse(node.textValue()).toInstant();
		} catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private LocalDate parseDate(JsonNode node) {
		if (node == null || !node.isTextual()) {
			throw invalid();
		}
		try {
			return LocalDate.parse(node.textValue());
		} catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private ZoneId parseZone(String value) {
		if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) {
			throw invalid();
		}
		try {
			return ZoneId.of(value);
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private String requiredText(JsonNode node, int maximumCodePoints, boolean allowBlank) {
		if (node == null || node.isNull() || !node.isTextual()) {
			throw invalid();
		}
		String value = node.textValue();
		if (value == null || !allowBlank && value.isBlank()
			|| value.codePointCount(0, value.length()) > maximumCodePoints) {
			throw invalid();
		}
		return value;
	}

	private String nullableText(JsonNode node, int maximumCodePoints, boolean allowBlank) {
		return node == null || node.isNull() ? null : requiredText(node, maximumCodePoints, allowBlank);
	}

	private static UUID nullableUuid(JsonNode node) {
		return node == null || node.isNull() ? null : parseUuidNode(node);
	}

	private static UUID parseUuidNode(JsonNode node) {
		if (node == null || !node.isTextual()) {
			throw invalid();
		}
		return parseUuid(node.textValue());
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || !raw.matches(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw invalid();
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private int parseIfMatch(HttpServletRequest request) {
		List<String> values = headers(request, "If-Match");
		if (values.size() != 1 || !values.getFirst().matches("\"[1-9][0-9]*\"")) {
			throw invalid();
		}
		try {
			return Math.toIntExact(Long.parseLong(values.getFirst().substring(1, values.getFirst().length() - 1)));
		} catch (ArithmeticException | NumberFormatException exception) {
			throw invalid();
		}
	}

	private String idempotencyKey(HttpServletRequest request) {
		List<String> values = headers(request, "Idempotency-Key");
		if (values.size() != 1) {
			throw invalid();
		}
		String key = values.getFirst();
		if (key == null || key.length() < 16 || key.length() > 100) {
			throw invalid();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw invalid();
			}
		}
		return key;
	}

	private static List<String> headers(HttpServletRequest request, String name) {
		Enumeration<String> values = request.getHeaders(name);
		return values == null ? List.of() : Collections.list(values);
	}

	private static void validateExactObject(JsonNode body, Set<String> fields) {
		if (body == null || !body.isObject()) {
			throw invalid();
		}
		assertAllowed(body, fields);
		requireFields(body, fields.toArray(String[]::new));
	}

	private static void assertAllowed(JsonNode body, Set<String> fields) {
		for (String field : body.propertyNames()) {
			if (!fields.contains(field)) {
				// 未知字段不能静默丢弃，尤其不能让客户端伪造内部账务入口。
				throw invalid();
			}
		}
	}

	private static void requireFields(JsonNode body, String... fields) {
		for (String field : fields) {
			if (!body.has(field) || body.get(field).isNull()) {
				throw invalid();
			}
		}
	}

	private static void putIfPresent(
		Map<String, Object> target,
		JsonNode source,
		String field,
		Object value) {
		if (source.has(field)) {
			// Hash 必须区分字段缺失与显式 null，不能用构造后的命令默认值覆盖原请求形状。
			target.put(field, value);
		}
	}

	private static Map<String, Object> copy(Map<String, Object> source) {
		return new LinkedHashMap<>(source);
	}

	private static Map<String, Object> immutable(Map<String, Object> source) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	private static IdempotencyRequestHasher.Decimal decimal(BigDecimal value) {
		return IdempotencyRequestHasher.decimal(value.toPlainString());
	}

	private static String requestHash(String resource, Object payload, String ifMatch) {
		return IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, resource, payload, ifMatch);
	}

	private static IdempotencyResponse succeeded(Transaction transaction) {
		return IdempotencyResponse.succeededResource(
			201, RESOURCE_TYPE, transaction.transactionId(), new IdempotencyResponse.ResourceReference(
				transactionLocation(transaction.transactionId()), etag(1), 1L));
	}

	private static String transactionLocation(UUID transactionId) {
		return "/api/v1/transactions/" + transactionId;
	}

	private static String etag(int version) {
		return "\"" + version + "\"";
	}

	private static String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	private static TransactionRequestValidationException invalid() {
		return new TransactionRequestValidationException();
	}

	private enum RequestType {
		INCOME(Set.of("accountId", "amount", "currency", "categoryId", "counterparty")),
		EXPENSE(Set.of("accountId", "amount", "currency", "categoryId", "merchant")),
		REFUND(Set.of("accountId", "amount", "currency", "originalTransactionId")),
		TRANSFER(Set.of(
			"fromAccountId", "toAccountId", "fromAmount", "toAmount", "fee", "feeCategoryId", "exchangeRate")),
		LIABILITY_BORROWING(Set.of("assetAccountId", "liabilityAccountId", "currency", "amount")),
		LIABILITY_REPAYMENT(Set.of(
			"cashAccountId", "liabilityAccountId", "currency", "principalAmount", "interestAmount",
			"feeAmount", "interestCategoryId", "feeCategoryId"));

		private final Set<String> specificFields;

		RequestType(Set<String> specificFields) {
			this.specificFields = specificFields;
		}

		Set<String> specificFields() {
			return specificFields;
		}
	}

	private sealed interface SemanticPayload permits IncomePayload, ExpensePayload, RefundPayload,
		TransferPayload, BorrowingPayload, RepaymentPayload {

		Map<String, Object> hashPayload();
	}

	private record IncomePayload(
		UUID accountId,
		Money amount,
		UUID categoryId,
		String counterparty,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record ExpensePayload(
		UUID accountId,
		Money amount,
		UUID categoryId,
		String merchant,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record RefundPayload(
		UUID accountId,
		UUID originalTransactionId,
		Money amount,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record TransferPayload(
		UUID fromAccountId,
		UUID toAccountId,
		Money fromAmount,
		Money toAmount,
		Money fee,
		UUID feeCategoryId,
		BigDecimal exchangeRate,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record BorrowingPayload(
		UUID assetAccountId,
		UUID liabilityAccountId,
		Money amount,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record RepaymentPayload(
		UUID cashAccountId,
		UUID liabilityAccountId,
		Money principal,
		Money interest,
		Money fee,
		UUID interestCategoryId,
		UUID feeCategoryId,
		Map<String, Object> hashPayload) implements SemanticPayload {
	}

	private record CommonFields(
		UUID requestedId,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String note,
		List<UUID> tagIds,
		Map<String, Object> hashPayload) {
	}

	private record ParsedTransaction(CommonFields common, SemanticPayload payload) {

		Instant businessAt() {
			return common.businessAt();
		}

		Map<String, Object> hashPayload() {
			return payload.hashPayload();
		}

		List<UUID> accountIds() {
			return switch (payload) {
				case IncomePayload value -> List.of(value.accountId());
				case ExpensePayload value -> List.of(value.accountId());
				case RefundPayload value -> List.of(value.accountId());
				case TransferPayload value -> List.of(value.fromAccountId(), value.toAccountId());
				case BorrowingPayload value -> List.of(value.assetAccountId(), value.liabilityAccountId());
				case RepaymentPayload value -> List.of(value.cashAccountId(), value.liabilityAccountId());
			};
		}

		List<UUID> relatedTransactionIds() {
			return payload instanceof RefundPayload refund
				? List.of(refund.originalTransactionId()) : List.of();
		}

		void validateSupportedBusinessRules() {
			if (!common.tagIds().isEmpty()) {
				throw new LedgerCommandValidationException("交易标签事实链尚未开放。");
			}
			if (payload instanceof TransferPayload transfer) {
				if (!transfer.fromAmount().equals(transfer.toAmount())
					|| transfer.fee().currency() != transfer.fromAmount().currency()
					|| transfer.exchangeRate() != null) {
					throw new LedgerCommandValidationException("B1 仅支持同币种同金额转账。");
				}
				if (transfer.fee().amount().signum() > 0 && transfer.feeCategoryId() == null
					|| transfer.fee().amount().signum() == 0 && transfer.feeCategoryId() != null) {
					throw new LedgerCommandValidationException("手续费分类必须与正手续费同时出现。");
				}
			}
			if (payload instanceof RepaymentPayload repayment) {
				if (repayment.interest().amount().signum() > 0 && repayment.interestCategoryId() == null
					|| repayment.interest().amount().signum() == 0 && repayment.interestCategoryId() != null
					|| repayment.fee().amount().signum() > 0 && repayment.feeCategoryId() == null
					|| repayment.fee().amount().signum() == 0 && repayment.feeCategoryId() != null) {
					throw new LedgerCommandValidationException("利息和手续费分类必须与正金额同时出现。");
				}
			}
		}

		Transaction post(LedgerCommandApplicationService service, UUID userId) {
			return switch (payload) {
				case IncomePayload value -> service.postIncome(new IncomeCommand(
					userId, value.accountId(), value.categoryId(), value.amount(), common.businessAt(),
					common.businessDate(), common.timezone(), value.counterparty(), common.note()), common.requestedId());
				case ExpensePayload value -> service.postExpense(new ExpenseCommand(
					userId, value.accountId(), value.categoryId(), value.amount(), common.businessAt(),
					common.businessDate(), common.timezone(), value.merchant(), common.note()), common.requestedId());
				case RefundPayload value -> service.postRefund(new RefundCommand(
					userId, value.accountId(), value.originalTransactionId(), value.amount(), common.businessAt(),
					common.businessDate(), common.timezone(), common.note()), common.requestedId());
				case TransferPayload value -> service.postTransfer(new TransferCommand(
					userId, value.fromAccountId(), value.toAccountId(), value.feeCategoryId(), value.fromAmount(),
					value.fee(), common.businessAt(), common.businessDate(), common.timezone(), common.note()),
					common.requestedId());
				case BorrowingPayload value -> service.postLiabilityBorrowing(new LiabilityBorrowingCommand(
					userId, value.assetAccountId(), value.liabilityAccountId(), value.amount(), common.businessAt(),
					common.businessDate(), common.timezone(), common.note()), common.requestedId());
				case RepaymentPayload value -> service.postLiabilityRepayment(new LiabilityRepaymentCommand(
					userId, value.cashAccountId(), value.liabilityAccountId(), value.principal(), value.interest(),
					value.fee(), value.interestCategoryId(), value.feeCategoryId(), common.businessAt(),
					common.businessDate(), common.timezone(), common.note()), common.requestedId());
			};
		}

		TransactionRevisionDetails revisionDetails() {
			return switch (payload) {
				case IncomePayload value -> new TransactionRevisionDetails.Income(
					value.accountId(), value.amount(), value.categoryId());
				case ExpensePayload value -> new TransactionRevisionDetails.Expense(
					value.accountId(), value.amount(), value.categoryId());
				case RefundPayload value -> new TransactionRevisionDetails.Refund(
					value.accountId(), value.originalTransactionId(), value.amount());
				case TransferPayload value -> new TransactionRevisionDetails.Transfer(
					value.fromAccountId(), value.toAccountId(), value.feeCategoryId(), value.fromAmount(), value.fee());
				case BorrowingPayload value -> new TransactionRevisionDetails.LiabilityBorrowing(
					value.assetAccountId(), value.liabilityAccountId(), value.amount());
				case RepaymentPayload value -> new TransactionRevisionDetails.LiabilityRepayment(
					value.cashAccountId(), value.liabilityAccountId(), value.principal(), value.interest(),
					value.fee(), value.interestCategoryId(), value.feeCategoryId());
			};
		}

		String counterparty() {
			return payload instanceof IncomePayload income ? income.counterparty() : null;
		}

		String merchant() {
			return payload instanceof ExpensePayload expense ? expense.merchant() : null;
		}
	}

	private record ParsedRevision(
		String reason,
		ParsedTransaction replacement,
		Map<String, Object> hashPayload) {

		RevisePostedTransactionCommand command(UUID userId, UUID transactionId, int expectedVersion) {
			return new RevisePostedTransactionCommand(
				userId, transactionId, replacement.common().requestedId(), expectedVersion,
				replacement.common().businessAt(), replacement.common().businessDate(), replacement.common().timezone(),
				replacement.counterparty(), replacement.merchant(), replacement.common().note(), reason,
				replacement.revisionDetails());
		}
	}

	private record ParsedReason(String reason, Map<String, Object> hashPayload) {
	}

	private record ParsedBalanceAdjustment(
		BigDecimal actualBalance,
		Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String reason,
		Map<String, Object> hashPayload) {
	}

	private record ParsedMoneyAmount(Money money, Map<String, Object> hashPayload) {
	}
}
