package app.ziji.account.interfaces;

import java.net.URI;
import java.security.Principal;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountArchiveException;
import app.ziji.account.application.AccountArchiveUseCase;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryValidationException;
import app.ziji.account.application.AccountVersionConflictException;
import app.ziji.account.domain.AccountStatus;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 账户归档 HTTP 边界；先证明当前 OWNER，再固化业务终态并只用安全引用重放。 */
@RestController
@RequestMapping("/api/v1/accounts")
public final class AccountArchiveController {

	private static final Set<String> REQUEST_FIELDS = Set.of("reason", "confirmNonZeroBalance");

	private final AccountArchiveUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;
	private final ObjectMapper objectMapper;

	public AccountArchiveController(
		AccountArchiveUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency) {
		this(useCase, currentUserIdResolver, idempotency, new ObjectMapper());
	}

	@Autowired
	public AccountArchiveController(
		AccountArchiveUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency,
		ObjectMapper objectMapper) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
		this.objectMapper = objectMapper;
	}

	@PostMapping(
		path = "/{accountId}/archive",
		consumes = MediaType.APPLICATION_JSON_VALUE,
		produces = MediaType.APPLICATION_JSON_VALUE,
		name = "archiveAccount")
	public ResponseEntity<?> archive(
		@PathVariable String accountId,
		@RequestBody(required = false) String rawBody,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseAccountId(accountId);
		// 可见性/OWNER 证明先于请求体、条件头和幂等记录，避免无关用户观察归档语义。
		useCase.preflightAccess(userId, parsedAccountId);
		ArchiveRequest command = parseRequest(rawBody);
		String ifMatch = parseIfMatch(request);
		int expectedVersion = parseVersion(ifMatch);
		String key = idempotencyKey(request);
		String resource = archiveLocation(parsedAccountId);
		String requestHash = requestHash(resource, parsedAccountId, command, ifMatch);

		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "archiveAccount", key, requestHash);
		if (inspected.isPresent()) {
			// inspect 之后重新证明当前 OWNER；成员被移除时不能回显历史归档响应。
			useCase.preflightAccess(userId, parsedAccountId);
			return writeArchive(inspected.get(), userId, parsedAccountId, request, response);
		}

		IdempotencyExecution<AccountQueryResult> execution = idempotency.executeAuthenticated(
			userId, 1, "archiveAccount", key, requestHash, () -> {
				try {
					AccountQueryResult archived = useCase.archive(
						userId, parsedAccountId, expectedVersion, command.reason(),
						command.confirmNonZeroBalance(), requestId(response));
					return IdempotencyWorkResult.completed(archived, IdempotencyResponse.succeededResource(
						200, "ACCOUNT", archived.id(), new IdempotencyResponse.ResourceReference(
							accountLocation(parsedAccountId), archived.etag(), (long) archived.version())));
				} catch (AccountArchiveException.NonZeroBalanceConfirmationRequired exception) {
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(422, "NON_ZERO_BALANCE_CONFIRMATION_REQUIRED"));
				} catch (AccountArchiveException.AlreadyArchived exception) {
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(409, "ACCOUNT_ALREADY_ARCHIVED"));
				} catch (AccountVersionConflictException conflict) {
					return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinalVersionConflict(
						409, conflict.current().version(), accountLocation(parsedAccountId)));
				}
			});
		requireCurrentAccessForExistingExecution(execution,
			() -> useCase.preflightAccess(userId, parsedAccountId));
		return writeArchive(execution, userId, parsedAccountId, request, response);
	}

	private ResponseEntity<?> writeArchive(
		IdempotencyExecution<?> execution,
		UUID userId,
		UUID accountId,
		HttpServletRequest request,
		HttpServletResponse response) {
		String accountResource = accountLocation(accountId);
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			if (!(execution.value() instanceof AccountQueryResult account)
				|| !validResourceResponse(execution.response(), accountResource, accountId, account)) {
				if (execution.response() != null
					&& execution.response().status() != IdempotencyResponse.Status.SUCCEEDED) {
					return storedProblem(execution.response(), accountId, request, response);
				}
				return internalProblem(request, response);
			}
			return accountResponse(account, response);
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null
			&& execution.response().status() == IdempotencyResponse.Status.SUCCEEDED
			&& validResourceReference(execution.response(), accountResource, accountId)) {
			IdempotencyResponse.ResourceReference reference =
				(IdempotencyResponse.ResourceReference) execution.response().reference();
			int version = replayVersion(reference);
			AccountQueryResult account = useCase.replay(userId, accountId, version);
			if (!validResourceResponse(execution.response(), accountResource, accountId, account)) {
				return internalProblem(request, response);
			}
			return ResponseEntity.ok().eTag(reference.etag())
				.body(new AccountController.AccountEnvelope(
					view(account), new AccountController.ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null
			&& execution.response().status() != IdempotencyResponse.Status.SUCCEEDED) {
			return storedProblem(execution.response(), accountId, request, response);
		}
		return idempotencyProblem(execution, request, response);
	}

	private static void requireCurrentAccessForExistingExecution(
		IdempotencyExecution<?> execution,
		Runnable accessProof) {
		if (execution.status() != IdempotencyExecution.Status.EXECUTED) {
			// acquire 可能在 inspect 为空后观察到并发终态，渲染前仍需重新验证当前权限。
			accessProof.run();
		}
	}

	private static boolean validResourceReference(
		IdempotencyResponse stored,
		String expectedLocation,
		UUID expectedResourceId) {
		return stored != null
			&& stored.status() == IdempotencyResponse.Status.SUCCEEDED
			&& stored.responseStatus() == 200
			&& "ACCOUNT".equals(stored.resourceType())
			&& expectedResourceId.equals(stored.resourceId())
			&& stored.reference() instanceof IdempotencyResponse.ResourceReference reference
			&& expectedLocation.equals(reference.location())
			&& reference.etag() != null
			&& reference.resourceVersion() != null;
	}

	private static boolean validResourceResponse(
		IdempotencyResponse stored,
		String expectedLocation,
		UUID expectedResourceId,
		AccountQueryResult account) {
		if (!validResourceReference(stored, expectedLocation, expectedResourceId)
			|| account == null || account.status() != AccountStatus.ARCHIVED) {
			return false;
		}
		IdempotencyResponse.ResourceReference reference =
			(IdempotencyResponse.ResourceReference) stored.reference();
		return reference.resourceVersion() == account.version()
			&& reference.etag().equals(account.etag());
	}

	private ResponseEntity<ProblemDetail> storedProblem(
		IdempotencyResponse stored,
		UUID accountId,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (stored.status() == IdempotencyResponse.Status.FAILED_FINAL
			&& stored.responseStatus() == 409
			&& stored.reference() instanceof IdempotencyResponse.VersionConflictReference conflict) {
			// 版本摘要来自历史幂等行，仍必须绑定当前路由账户；错绑历史行只能 fail-closed。
			if (!accountLocation(accountId).equals(conflict.resourceLocation())) {
				return internalProblem(request, response);
			}
			ProblemDetail detail = problemDetail(HttpStatus.CONFLICT, "VERSION_CONFLICT", request, response);
			detail.setProperty("versionConflict", Map.of(
				"currentVersion", conflict.currentVersion(),
				"currentEtag", conflict.currentEtag(),
				"resourceLocation", conflict.resourceLocation()));
			return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
		}
		if (stored.status() == IdempotencyResponse.Status.FAILED_FINAL
			&& stored.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			HttpStatus status = stableFinalStatus(stored.responseStatus(), problem.errorCode());
			if (status != null) {
				return problem(status, problem.errorCode(), request, response, false);
			}
		}
		if (stored.status() == IdempotencyResponse.Status.FAILED_RETRYABLE
			&& stored.reference() instanceof IdempotencyResponse.ProblemReference problem
			&& problem.retryable() && "INTERNAL_ERROR".equals(problem.errorCode())) {
			HttpStatus status = HttpStatus.resolve(stored.responseStatus());
			if (status != null && status.is5xxServerError()) {
				return problem(status, problem.errorCode(), request, response, true);
			}
		}
		return internalProblem(request, response);
	}

	private ResponseEntity<ProblemDetail> idempotencyProblem(
		IdempotencyExecution<?> execution,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.REQUEST_IN_PROGRESS) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", request, response, true);
		}
		if (execution.status() == IdempotencyExecution.Status.KEY_REUSED) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", request, response, false);
		}
		return internalProblem(request, response);
	}

	private ResponseEntity<AccountController.AccountEnvelope> accountResponse(
		AccountQueryResult account,
		HttpServletResponse response) {
		return ResponseEntity.ok().eTag(account.etag())
			.body(new AccountController.AccountEnvelope(
				view(account), new AccountController.ResponseMeta(requestId(response))));
	}

	private AccountController.AccountView view(AccountQueryResult account) {
		return new AccountController.AccountView(
			account.id(), account.name(), account.accountClass().name(), account.accountType().name(),
			account.currency().name(), account.institution(), account.status().name(),
			account.currentUserRole(), account.inclusionRatio().setScale(6).toPlainString(), account.version());
	}

	private ResponseEntity<ProblemDetail> internalProblem(
		HttpServletRequest request,
		HttpServletResponse response) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, false);
	}

	private ResponseEntity<ProblemDetail> problem(
		HttpStatus status,
		String code,
		HttpServletRequest request,
		HttpServletResponse response,
		boolean retryAfter) {
		ProblemDetail detail = problemDetail(status, code, request, response);
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfter) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	private ProblemDetail problemDetail(
		HttpStatus status,
		String code,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
		detail.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		detail.setTitle(status.getReasonPhrase());
		detail.setInstance(URI.create(request.getRequestURI()));
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		return detail;
	}

	private static HttpStatus stableFinalStatus(int responseStatus, String code) {
		return switch (code) {
			case "ACCOUNT_ALREADY_ARCHIVED" -> responseStatus == 409 ? HttpStatus.CONFLICT : null;
			case "NON_ZERO_BALANCE_CONFIRMATION_REQUIRED" -> responseStatus == 422
				? HttpStatus.UNPROCESSABLE_ENTITY : null;
			default -> null;
		};
	}

	private ArchiveRequest parseRequest(String rawBody) {
		final JsonNode body;
		try {
			// 先做资源权限证明，再手动解析 JSON，避免消息转换器在 Controller 之前暴露不可见资源的格式差异。
			body = rawBody == null ? null : objectMapper.readTree(rawBody);
		} catch (RuntimeException exception) {
			throw new AccountArchiveException.Validation();
		}
		if (body == null || !body.isObject() || body.size() != REQUEST_FIELDS.size()) {
			throw new AccountArchiveException.Validation();
		}
		for (String field : body.propertyNames()) {
			if (!REQUEST_FIELDS.contains(field)) {
				throw new AccountArchiveException.Validation();
			}
		}
		JsonNode reasonNode = body.get("reason");
		JsonNode confirmationNode = body.get("confirmNonZeroBalance");
		if (reasonNode == null || !reasonNode.isTextual()
			|| confirmationNode == null || !confirmationNode.isBoolean()) {
			throw new AccountArchiveException.Validation();
		}
		String reason = reasonNode.textValue();
		if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
			throw new AccountArchiveException.Validation();
		}
		return new ArchiveRequest(reason, confirmationNode.booleanValue());
	}

	private static String requestHash(
		String resource,
		UUID accountId,
		ArchiveRequest command,
		String ifMatch) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("accountId", accountId);
		payload.put("reason", command.reason());
		payload.put("confirmNonZeroBalance", command.confirmNonZeroBalance());
		return IdempotencyRequestHasher.hash(
			"POST", MediaType.APPLICATION_JSON_VALUE, resource, payload, ifMatch);
	}

	private static String parseIfMatch(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("If-Match");
		String value = request.getHeader("If-Match");
		if (value == null || values == null || !values.hasMoreElements()) {
			throw new AccountArchiveException.Validation();
		}
		String first = values.nextElement();
		if (values.hasMoreElements() || !value.equals(first) || !value.matches("\"[1-9][0-9]*\"")) {
			throw new AccountArchiveException.Validation();
		}
		return value;
	}

	private static int parseVersion(String ifMatch) {
		try {
			return Integer.parseInt(ifMatch.substring(1, ifMatch.length() - 1));
		} catch (RuntimeException exception) {
			throw new AccountArchiveException.Validation();
		}
	}

	private static String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw new AccountArchiveException.Validation();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw new AccountArchiveException.Validation();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw new AccountArchiveException.Validation();
			}
		}
		return key;
	}

	private static UUID parseAccountId(String raw) {
		try {
			return UUID.fromString(raw);
		} catch (RuntimeException exception) {
			throw new AccountQueryValidationException();
		}
	}

	private static int replayVersion(IdempotencyResponse.ResourceReference reference) {
		try {
			return Math.toIntExact(reference.resourceVersion());
		} catch (ArithmeticException | NullPointerException exception) {
			throw new AccountArchiveException.SafeReplayUnavailable();
		}
	}

	private static String accountLocation(UUID accountId) {
		return "/api/v1/accounts/" + accountId;
	}

	private static String archiveLocation(UUID accountId) {
		return accountLocation(accountId) + "/archive";
	}

	private static String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	private record ArchiveRequest(String reason, boolean confirmNonZeroBalance) {
	}
}
