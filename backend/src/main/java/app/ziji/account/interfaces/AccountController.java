package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountCreationCommand;
import app.ziji.account.application.AccountCreationResult;
import app.ziji.account.application.AccountCreationService;
import app.ziji.account.application.AccountOpeningBalance;
import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.application.AccountPage;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryUseCase;
import app.ziji.account.application.AccountQueryValidationException;
import app.ziji.account.domain.AccountClass;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.AccountPatch;
import app.ziji.account.domain.AccountStatus;
import app.ziji.account.domain.AccountType;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.CurrentUserTimezonePort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账户查询与资料更新的 HTTP 边界；merge-patch 只转换为类型化应用命令。 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

	private static final String MERGE_PATCH = "application/merge-patch+json";
	private static final List<String> PATCH_FIELDS = List.of("name", "institution");
	private static final Set<String> CREATE_FIELDS = Set.of(
		"accountClass", "accountType", "name", "currency", "institution", "note", "openingBalance");
	private static final Set<String> OPENING_FIELDS = Set.of("amount", "businessAt", "note");

	private final AccountQueryUseCase useCase;
	private final AccountCreationService creationService;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;
	private final CurrentUserTimezonePort timezones;

	/** 保留既有查询/更新 MVC 测试构造入口；创建路由使用完整依赖构造器。 */
	public AccountController(
		AccountQueryUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver) {
		this(useCase, null, currentUserIdResolver, null, null);
	}

	@Autowired
	public AccountController(
		AccountQueryUseCase useCase,
		AccountCreationService creationService,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency,
		CurrentUserTimezonePort timezones) {
		this.useCase = useCase;
		this.creationService = creationService;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
		this.timezones = timezones;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, name = "createAccount")
	public ResponseEntity<?> createAccount(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		ParsedCreate parsed = parseCreate(body, userId);
		String resource = "/api/v1/accounts";
		IdempotencyExecution<AccountCreationResult> execution = idempotency.executeAuthenticated(
			userId, 1, "createAccount", idempotencyKey(request), requestHash(resource, parsed), () -> {
				AccountCreationResult created = creationService.createAccountWithOpening(parsed.command());
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "ACCOUNT", created.account().id(), new IdempotencyResponse.ResourceReference(
						"/api/v1/accounts/" + created.account().id(), etag(created.account().version()),
						(long) created.account().version())));
			});
		return writeCreate(execution, userId, parsed.command().openingBalance() != null, response);
	}

	@GetMapping(name = "listAccounts")
	public ResponseEntity<AccountListEnvelope> listAccounts(
		@RequestParam(name = "limit", required = false) String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		AccountPage page = useCase.listVisibleAccounts(userId, parseLimit(rawLimit), cursor);
		List<AccountView> data = page.accounts().stream().map(this::view).toList();
		return ResponseEntity.ok(new AccountListEnvelope(
			data, new PageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@GetMapping(path = "/{accountId}", name = "getAccount")
	public ResponseEntity<AccountEnvelope> getAccount(
		@PathVariable String accountId,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		AccountQueryResult account = useCase.getVisibleAccount(userId, parseAccountId(accountId));
		return ResponseEntity.ok()
			.eTag(account.etag())
			.body(new AccountEnvelope(view(account), new ResponseMeta(requestId(response))));
	}

	@PatchMapping(
		path = "/{accountId}",
		name = "updateAccount",
		consumes = MERGE_PATCH,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<AccountEnvelope> updateAccount(
		@PathVariable String accountId,
		@RequestHeader(value = "If-Match", required = false) String ifMatch,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		int expectedVersion = parseIfMatch(ifMatch, request);
		AccountPatch patch = parsePatch(body);
		UUID userId = currentUserIdResolver.resolve(principal);
		AccountQueryResult account = useCase.updateAccount(userId, parseAccountId(accountId), expectedVersion, patch);
		return ResponseEntity.ok()
			.eTag(account.etag())
			.body(new AccountEnvelope(view(account), new ResponseMeta(requestId(response))));
	}

	private AccountPatch parsePatch(JsonNode body) {
		if (body == null || !body.isObject() || body.size() == 0) {
			throw invalid();
		}
		boolean namePresent = false;
		String name = null;
		boolean institutionPresent = false;
		String institution = null;
		for (String field : body.propertyNames()) {
			if (!PATCH_FIELDS.contains(field)) {
				// 未知字段不能静默丢弃，避免客户端误以为资料已保存。
				throw invalid();
			}
			if ("name".equals(field)) {
				namePresent = true;
				name = requiredText(body, field);
			} else {
				institutionPresent = true;
				institution = nullableText(body, field);
			}
		}
		return new AccountPatch(namePresent, name, institutionPresent, institution);
	}

	private ParsedCreate parseCreate(JsonNode body, UUID userId) {
		if (body == null || !body.isObject()) {
			throw invalid();
		}
		for (String field : body.propertyNames()) {
			if (!CREATE_FIELDS.contains(field)) {
				// 未知字段不能静默丢弃，尤其不能把 creditLimit 映射为负债事实。
				throw invalid();
			}
		}
		for (String field : List.of("accountClass", "accountType", "name", "currency")) {
			if (!body.has(field) || body.get(field).isNull() || !body.get(field).isTextual()) {
				throw invalid();
			}
		}
		try {
			AccountClass accountClass = AccountClass.valueOf(body.get("accountClass").textValue());
			AccountType accountType = AccountType.valueOf(body.get("accountType").textValue());
			if (!accountType.isAllowedFor(accountClass)) {
				throw invalid();
			}
			AccountCurrency currency = AccountCurrency.fromCode(body.get("currency").textValue());
			AccountOpeningBalance openingBalance = openingBalance(body.get("openingBalance"));
			if (openingBalance != null && !hasPostingPrecision(openingBalance.amount(), currency)) {
				throw invalid();
			}
			ZoneId timezone = openingBalance == null ? null : timezones.currentTimezone(userId);
			return new ParsedCreate(new AccountCreationCommand(
				accountClass, accountType, requiredText(body, "name"), nullableText(body, "institution"),
				currency, nullableText(body, "note"), userId,
				openingBalance, timezone));
		} catch (AccountQueryValidationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private boolean hasPostingPrecision(BigDecimal amount, AccountCurrency currency) {
		try {
			amount.setScale(currency == AccountCurrency.JPY ? 0 : 2, java.math.RoundingMode.UNNECESSARY);
			return true;
		} catch (ArithmeticException exception) {
			return false;
		}
	}

	private AccountOpeningBalance openingBalance(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isObject()) {
			throw invalid();
		}
		for (String field : value.propertyNames()) {
			if (!OPENING_FIELDS.contains(field)) {
				throw invalid();
			}
		}
		if (!value.has("amount") || !value.has("businessAt") || value.get("amount").isNull()
			|| value.get("businessAt").isNull() || !value.get("amount").isTextual()
			|| !value.get("businessAt").isTextual()
			|| value.has("note") && !value.get("note").isNull() && !value.get("note").isTextual()) {
			throw invalid();
		}
		String amount = value.get("amount").textValue();
		if (amount == null || !amount.matches("^(0*[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$|^0\\.(0[1-9]|[1-9][0-9]?)$")) {
			throw invalid();
		}
		try {
			return new AccountOpeningBalance(new BigDecimal(amount), parseInstant(value.get("businessAt").textValue()),
				value.has("note") && !value.get("note").isNull() ? value.get("note").textValue() : null);
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private String requiredText(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull() || !value.isTextual()) {
			throw invalid();
		}
		String text = value.textValue();
		if (text == null || text.isBlank()) {
			throw invalid();
		}
		return text;
	}

	private String nullableText(JsonNode body, String field) {
		JsonNode value = body.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw invalid();
		}
		String text = value.textValue();
		if (text == null || text.isBlank()) {
			throw invalid();
		}
		return text;
	}

	private Instant parseInstant(String value) {
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private ResponseEntity<?> writeCreate(
		IdempotencyExecution<AccountCreationResult> execution,
		UUID userId,
		boolean openingExpected,
		HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			AccountCreationResult created = execution.value();
			AccountQueryResult account = useCase.getVisibleAccount(userId, created.account().id());
			return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(account.version()))
				.body(new AccountCreatedEnvelope(new AccountCreatedData(view(account), created.openingTransactionId()),
					new ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null && execution.response().reference() instanceof IdempotencyResponse.ResourceReference reference
			&& execution.response().resourceId() != null && reference.resourceVersion() != null) {
			AccountQueryResult account;
			try {
				account = useCase.getVisibleAccount(userId, execution.response().resourceId());
			} catch (AccountNotVisibleException exception) {
				// 首次 OWNER 周期已结束或移除时不能降级为 404，必须禁止伪造首次成功响应。
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			if (account.status() != AccountStatus.ACTIVE
				|| !"OWNER".equals(account.currentUserRole())
				// DB 约束 included=false 时 ratio 必为 0，因此 ratio=1 同时证明 included=true。
				|| account.inclusionRatio().compareTo(BigDecimal.ONE) != 0
				|| !reference.etag().equals(etag(account.version())) || reference.resourceVersion() != account.version()) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			UUID openingTransactionId = creationService.findOpeningTransactionId(account.id());
			// 同 Hash 请求仍可证明是否携带期初余额；缺失或意外 OPENING 均不得伪造首次响应。
			if (openingExpected != (openingTransactionId != null)) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			return ResponseEntity.status(HttpStatus.CREATED).eTag(reference.etag())
				.body(new AccountCreatedEnvelope(new AccountCreatedData(
					view(account), openingTransactionId), new ResponseMeta(requestId(response))));
		}
		return idempotencyProblem(execution, response);
	}

	private ResponseEntity<ProblemDetail> idempotencyProblem(
		IdempotencyExecution<?> execution, HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.REQUEST_IN_PROGRESS) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", response, true);
		}
		if (execution.status() == IdempotencyExecution.Status.KEY_REUSED) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", response, false);
		}
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
	}

	private ResponseEntity<ProblemDetail> problem(
		HttpStatus status, String code, HttpServletResponse response, boolean retryAfter) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfter) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	private String requestHash(String resource, ParsedCreate parsed) {
		Map<String, Object> payload = new LinkedHashMap<>();
		AccountCreationCommand command = parsed.command();
		payload.put("accountClass", command.accountClass());
		payload.put("accountType", command.accountType());
		payload.put("name", command.name());
		payload.put("institution", command.institution());
		payload.put("currency", command.currency());
		payload.put("note", command.note());
		payload.put("openingBalance", openingPayload(command.openingBalance()));
		return IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, resource, payload, null);
	}

	private Map<String, Object> openingPayload(AccountOpeningBalance openingBalance) {
		if (openingBalance == null) {
			return null;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("amount", IdempotencyRequestHasher.decimal(openingBalance.amount().toPlainString()));
		payload.put("businessAt", openingBalance.businessAt());
		payload.put("note", openingBalance.note());
		return payload;
	}

	private String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw invalid();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw invalid();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw invalid();
			}
		}
		return key;
	}

	private int parseIfMatch(String value, HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("If-Match");
		if (value == null || values == null || !values.hasMoreElements()) {
			throw invalid();
		}
		String first = values.nextElement();
		if (values.hasMoreElements() || !Objects.equals(first, value)
			|| !value.matches("\"[1-9][0-9]*\"")) {
			throw invalid();
		}
		try {
			return Integer.parseInt(value.substring(1, value.length() - 1));
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private Integer parseLimit(String rawLimit) {
		if (rawLimit == null) {
			return null;
		}
		if (!rawLimit.matches("[1-9][0-9]*")) {
			throw invalid();
		}
		try {
			return Integer.valueOf(rawLimit);
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private UUID parseAccountId(String accountId) {
		try {
			return UUID.fromString(accountId);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private AccountQueryValidationException invalid() {
		return new AccountQueryValidationException();
	}

	private AccountView view(AccountQueryResult account) {
		return new AccountView(
			account.id(),
			account.name(),
			account.accountClass().name(),
			account.accountType().name(),
			account.currency().name(),
			account.institution(),
			account.status().name(),
			account.currentUserRole(),
			ratio(account.inclusionRatio()),
			account.version());
	}

	private String ratio(BigDecimal value) {
		return value.setScale(6).toPlainString();
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	private String etag(int version) {
		return "\"" + version + "\"";
	}

	public record AccountListEnvelope(List<AccountView> data, PageMeta meta) {
		public AccountListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record AccountEnvelope(AccountView data, ResponseMeta meta) {
	}

	public record AccountCreatedEnvelope(AccountCreatedData data, ResponseMeta meta) {
	}

	public record AccountCreatedData(AccountView account, UUID openingTransactionId) {
	}

	public record AccountView(
		UUID id,
		String name,
		String accountClass,
		String accountType,
		String currency,
		String institution,
		String status,
		String currentUserRole,
		String inclusionRatio,
		int version) {
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}

	public record ResponseMeta(String requestId) {
	}

	private record ParsedCreate(AccountCreationCommand command) {
	}
}
