package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.LiquidityHoldCommand;
import app.ziji.account.application.LiquidityHoldPage;
import app.ziji.account.application.LiquidityHoldUseCase;
import app.ziji.account.domain.AccountCurrency;
import app.ziji.account.domain.LiquidityHold;
import app.ziji.account.domain.LiquidityHoldStatus;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** LiquidityHold 四个冻结 operation 的 HTTP 边界；校验后只调用 application port。 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/liquidity-holds")
public class LiquidityHoldController {

	private static final Set<String> COMMAND_FIELDS = Set.of("type", "amount", "currency", "effectiveAt", "expiresAt", "reason");

	private final LiquidityHoldUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	public LiquidityHoldController(
		LiquidityHoldUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency,
		Clock clock) {
		this(useCase, currentUserIdResolver, idempotency, clock, new ObjectMapper());
	}

	@Autowired
	public LiquidityHoldController(
		LiquidityHoldUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency,
		Clock clock,
		ObjectMapper objectMapper) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	@GetMapping(name = "listLiquidityHolds")
	public ResponseEntity<LiquidityHoldListEnvelope> list(
		@PathVariable String accountId,
		@RequestParam(required = false) String limit,
		@RequestParam(required = false) String cursor,
		java.security.Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		LiquidityHoldPage page = useCase.list(userId, parseUuid(accountId), parseLimit(limit), cursor);
		return ResponseEntity.ok(new LiquidityHoldListEnvelope(
			page.holds().stream().map(this::view).toList(),
			new PageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, name = "createLiquidityHold")
	public ResponseEntity<?> create(
		@PathVariable String accountId,
		@RequestBody(required = false) String rawBody,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		// 先证明账户对当前用户可见，再手动解析 JSON，避免不可见账户因畸形 body 先暴露 400。
		useCase.preflightCreateAccess(userId, parsedAccountId);
		LiquidityHoldCommand command = parseCommand(rawBody);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds";
		String requestHash = requestHash(resource, commandPayload(command), null);
		// 已先校验当前可见性/角色；既有终态必须先于 ARCHIVED 等可变资格重放，撤权仍不会泄露。
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "createLiquidityHold", key, requestHash);
		if (inspected.isPresent()) {
			// inspect 与渲染之间可能撤权；终态也必须重新证明当前可见性，不能回显历史摘要。
			useCase.preflightCreateAccess(userId, parsedAccountId);
			return writeCreate(inspected.get(), parsedAccountId, request, response);
		}
		useCase.preflightCreate(userId, parsedAccountId);
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "createLiquidityHold", key, requestHash, () -> {
				try {
					LiquidityHold created = useCase.create(userId, parsedAccountId, command, requestId(response));
					return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
						201, "LIQUIDITY_HOLD", created.id(), resourceReference(resource, created)));
				} catch (app.ziji.account.application.LiquidityHoldException.BusinessRule exception) {
					// 稳定业务规则失败没有事实副作用，固化为可安全重放的 FAILED_FINAL。
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"));
				}
			});
		requireCurrentAccessForExistingExecution(execution,
			() -> useCase.preflightCreateAccess(userId, parsedAccountId));
		return writeCreate(execution, parsedAccountId, request, response);
	}

	@PostMapping(path = "/{holdId}/revisions", consumes = MediaType.APPLICATION_JSON_VALUE, name = "reviseLiquidityHold")
	public ResponseEntity<?> revise(
		@PathVariable String accountId,
		@PathVariable String holdId,
		@RequestBody(required = false) String rawBody,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		UUID parsedHoldId = parseUuid(holdId);
		// If-Match 和 body 的格式尚未可信前，只使用路径资源做可见性证明，隐藏不可见 hold 的输入差异。
		useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId);
		LiquidityHoldCommand command = parseCommand(rawBody);
		int expectedVersion = parseIfMatch(request);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds/" + parsedHoldId + "/revisions";
		String requestHash = requestHash(resource, commandPayload(command), request.getHeader("If-Match"));
		// 版本、逻辑时点和账户状态会变化；同键终态只能由保存的安全引用直接重放。
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "reviseLiquidityHold", key, requestHash);
		if (inspected.isPresent()) {
			useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId, expectedVersion);
			return writeMutation(inspected.get(), parsedAccountId, parsedHoldId, resource, request, response,
				HttpStatus.CREATED, true, false);
		}
		useCase.preflightMutation(userId, parsedAccountId, parsedHoldId, expectedVersion, false);
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "reviseLiquidityHold", key, requestHash, () -> {
				try {
					LiquidityHold revised = useCase.revise(userId, parsedAccountId, parsedHoldId, expectedVersion, command, requestId(response));
					return IdempotencyWorkResult.completed(revised, IdempotencyResponse.succeededResource(
						201, "LIQUIDITY_HOLD", revised.id(), resourceReference(resource, revised)));
				} catch (app.ziji.account.application.LiquidityHoldException.VersionConflict conflict) {
					return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinalVersionConflict(
						409, conflict.current().version(), holdLocation(parsedAccountId)));
				} catch (app.ziji.account.application.LiquidityHoldException.BusinessRule exception) {
					// 过期、币种或账户生命周期规则失败不执行二次写入，固定保存 422 终态。
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"));
				}
			});
		requireCurrentAccessForExistingExecution(execution,
			() -> useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId, expectedVersion));
		return writeMutation(execution, parsedAccountId, parsedHoldId, resource, request, response,
			HttpStatus.CREATED, true, false);
	}

	@PostMapping(path = "/{holdId}/release", name = "releaseLiquidityHold")
	public ResponseEntity<?> release(
		@PathVariable String accountId,
		@PathVariable String holdId,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		UUID parsedHoldId = parseUuid(holdId);
		// release 没有 body，但仍须先证明 hold 可见，才能把畸形 If-Match 映射为 404 或 400。
		useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId);
		int expectedVersion = parseIfMatch(request);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds/" + parsedHoldId + "/release";
		String requestHash = requestHash(resource, Map.of(), request.getHeader("If-Match"));
		// release 的归档放行只属于新请求资格；已有终态同样必须避免被后续状态伪造或改写。
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "releaseLiquidityHold", key, requestHash);
		if (inspected.isPresent()) {
			useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId, expectedVersion);
			return writeMutation(inspected.get(), parsedAccountId, parsedHoldId, resource, request, response,
				HttpStatus.OK, false, true);
		}
		useCase.preflightMutation(userId, parsedAccountId, parsedHoldId, expectedVersion, true);
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "releaseLiquidityHold", key, requestHash, () -> {
				try {
					LiquidityHold released = useCase.release(userId, parsedAccountId, parsedHoldId, expectedVersion, requestId(response));
					return IdempotencyWorkResult.completed(released, IdempotencyResponse.succeededResource(
						200, "LIQUIDITY_HOLD", released.id(), resourceReference(resource, released)));
				} catch (app.ziji.account.application.LiquidityHoldException.VersionConflict conflict) {
					return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinalVersionConflict(
						409, conflict.current().version(), holdLocation(parsedAccountId)));
				} catch (app.ziji.account.application.LiquidityHoldException.BusinessRule exception) {
					// 过期或账户生命周期规则失败不执行二次写入，固定保存 422 终态。
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(422, "BUSINESS_RULE_VIOLATION"));
				}
			});
		requireCurrentAccessForExistingExecution(execution,
			() -> useCase.preflightMutationAccess(userId, parsedAccountId, parsedHoldId, expectedVersion));
		return writeMutation(execution, parsedAccountId, parsedHoldId, resource, request, response,
			HttpStatus.OK, false, true);
	}

	private static void requireCurrentAccessForExistingExecution(
		IdempotencyExecution<?> execution,
		Runnable accessProof) {
		if (execution.status() != IdempotencyExecution.Status.EXECUTED) {
			// inspect 为空后 acquire 仍可能读到并发提交的旧记录，渲染前必须再次验证当前权限。
			accessProof.run();
		}
	}

	private ResponseEntity<?> writeCreate(
		IdempotencyExecution<?> execution,
		UUID accountId,
		HttpServletRequest request,
		HttpServletResponse response) {
		String resource = holdLocation(accountId);
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			if (!(execution.value() instanceof LiquidityHold hold)
				|| !validResourceResponse(execution.response(), 201, "LIQUIDITY_HOLD", resource, hold.id(), hold, false, false)) {
				return storedProblem(execution.response(), holdLocation(accountId), request, response);
			}
			return ResponseEntity.status(HttpStatus.CREATED).eTag(hold.etag())
				.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null) {
			if (execution.response().status() != IdempotencyResponse.Status.SUCCEEDED) {
				return storedProblem(execution.response(), holdLocation(accountId), request, response);
			}
			if (validResourceReference(execution.response(), 201, "LIQUIDITY_HOLD", resource, null)) {
				IdempotencyResponse.ResourceReference reference = (IdempotencyResponse.ResourceReference) execution.response().reference();
				LiquidityHold hold = useCase.replay(currentUserId(request), accountId, execution.response().resourceId(), replayVersion(reference));
				if (!validResourceResponse(execution.response(), 201, "LIQUIDITY_HOLD", resource, hold.id(), hold, false, false)) {
					throw new app.ziji.account.application.LiquidityHoldException.SafeReplayUnavailable();
				}
				return ResponseEntity.status(HttpStatus.CREATED).eTag(replayEtag(hold, reference))
					.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
			}
		}
		return idempotencyProblem(execution, request, response);
	}

	private ResponseEntity<?> writeMutation(
		IdempotencyExecution<?> execution,
		UUID accountId,
		UUID holdId,
		String resource,
		HttpServletRequest request,
		HttpServletResponse response,
		HttpStatus successStatus,
		boolean revision,
		boolean release) {
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			if (!(execution.value() instanceof LiquidityHold hold)
				|| !validResourceResponse(execution.response(), successStatus.value(), "LIQUIDITY_HOLD", resource,
					hold.id(), hold, revision, release, holdId)) {
				return storedProblem(execution.response(), holdLocation(accountId), request, response);
			}
			return ResponseEntity.status(successStatus).eTag(hold.etag())
				.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null) {
			if (execution.response().status() != IdempotencyResponse.Status.SUCCEEDED) {
				return storedProblem(execution.response(), holdLocation(accountId), request, response);
			}
			if (validResourceReference(execution.response(), successStatus.value(), "LIQUIDITY_HOLD", resource,
				release ? holdId : null)) {
				IdempotencyResponse.ResourceReference reference = (IdempotencyResponse.ResourceReference) execution.response().reference();
				LiquidityHold hold = useCase.replay(currentUserId(request), accountId, execution.response().resourceId(), replayVersion(reference));
				if (!validResourceResponse(execution.response(), successStatus.value(), "LIQUIDITY_HOLD", resource,
					hold.id(), hold, revision, release, holdId)) {
					throw new app.ziji.account.application.LiquidityHoldException.SafeReplayUnavailable();
				}
				return ResponseEntity.status(successStatus).eTag(replayEtag(hold, reference))
					.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
			}
		}
		return idempotencyProblem(execution, request, response);
	}

	private static boolean validResourceReference(
		IdempotencyResponse stored,
		int expectedStatus,
		String expectedType,
		String expectedLocation,
		UUID expectedResourceId) {
		// 保存的引用必须仍精确属于本 operation，避免错误路径或资源被当成首次成功响应回放。
		if (stored == null || stored.status() != IdempotencyResponse.Status.SUCCEEDED
			|| stored.responseStatus() != expectedStatus
			|| !expectedType.equals(stored.resourceType())
			|| !(stored.reference() instanceof IdempotencyResponse.ResourceReference reference)
			|| stored.resourceId() == null || !expectedLocation.equals(reference.location())
			|| reference.resourceVersion() == null || reference.etag() == null) {
			return false;
		}
		return expectedResourceId == null || expectedResourceId.equals(stored.resourceId());
	}

	private boolean validResourceResponse(
		IdempotencyResponse stored,
		int expectedStatus,
		String expectedType,
		String expectedLocation,
		UUID expectedResourceId,
		LiquidityHold hold,
		boolean revision,
		boolean release) {
		return validResourceResponse(stored, expectedStatus, expectedType, expectedLocation, expectedResourceId, hold,
			revision, release, null);
	}

	private boolean validResourceResponse(
		IdempotencyResponse stored,
		int expectedStatus,
		String expectedType,
		String expectedLocation,
		UUID expectedResourceId,
		LiquidityHold hold,
		boolean revision,
		boolean release,
		UUID previousHoldId) {
		if (!validResourceReference(stored, expectedStatus, expectedType, expectedLocation, expectedResourceId)
			|| hold == null || !expectedResourceId.equals(hold.id())) {
			return false;
		}
		IdempotencyResponse.ResourceReference reference = (IdempotencyResponse.ResourceReference) stored.reference();
		if (reference.resourceVersion() != hold.version() || !reference.etag().equals(hold.etag())) {
			return false;
		}
		if (revision && (previousHoldId == null || !previousHoldId.equals(hold.previousRevisionId()))) {
			return false;
		}
		return !release || hold.statusAt(clock.instant()) == LiquidityHoldStatus.RELEASED;
	}

	private ResponseEntity<ProblemDetail> storedProblem(
		IdempotencyResponse stored,
		String expectedVersionConflictLocation,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (stored != null && stored.status() == IdempotencyResponse.Status.FAILED_FINAL
			&& stored.responseStatus() == 409
			&& stored.reference() instanceof IdempotencyResponse.VersionConflictReference conflict) {
			// 共享层只保证路径形状；当前账户的 canonical hold 集合路径必须再次绑定，错绑历史行直接拒绝回显。
			if (expectedVersionConflictLocation == null
				|| !expectedVersionConflictLocation.equals(conflict.resourceLocation())) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, false);
			}
			ProblemDetail detail = problemDetail(HttpStatus.CONFLICT, "VERSION_CONFLICT", request, response);
			detail.setProperty("versionConflict", Map.of(
				"currentVersion", conflict.currentVersion(),
				"currentEtag", conflict.currentEtag(),
				"resourceLocation", conflict.resourceLocation()));
			return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
		}
		if (stored != null && stored.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			HttpStatus finalStatus = stored.status() == IdempotencyResponse.Status.FAILED_FINAL
				? stableFinalStatus(stored.responseStatus(), problem.errorCode()) : null;
			if (finalStatus != null && !problem.retryable()) {
				return problem(finalStatus, problem.errorCode(), request, response, false);
			}
			HttpStatus retryableStatus = stored.status() == IdempotencyResponse.Status.FAILED_RETRYABLE
				&& problem.retryable() && "INTERNAL_ERROR".equals(problem.errorCode())
				? HttpStatus.resolve(stored.responseStatus()) : null;
			if (retryableStatus != null && retryableStatus.is5xxServerError()) {
				return problem(retryableStatus, problem.errorCode(), request, response, true);
			}
		}
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, false);
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
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, false);
	}

	private static int replayVersion(IdempotencyResponse.ResourceReference reference) {
		try {
			return Math.toIntExact(reference.resourceVersion());
		} catch (ArithmeticException exception) {
			// 幂等引用的版本超出当前领域整数范围时不能猜测或截断。
			throw new app.ziji.account.application.LiquidityHoldException.SafeReplayUnavailable();
		}
	}

	private static String replayEtag(
		LiquidityHold hold,
		IdempotencyResponse.ResourceReference reference) {
		if (hold == null || reference.etag() == null || !reference.etag().equals(hold.etag())) {
			// 只允许以保存的安全引用重建首次响应，禁止返回当前版本配旧 ETag。
			throw new app.ziji.account.application.LiquidityHoldException.SafeReplayUnavailable();
		}
		return reference.etag();
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

	private static HttpStatus stableFinalStatus(int responseStatus, String code) {
		// 前置校验不可通过历史幂等记录回放；此处只放行已确认不会泄露访问状态的业务终态。
		return switch (code) {
			case "BUSINESS_RULE_VIOLATION" -> responseStatus == 422 ? HttpStatus.UNPROCESSABLE_ENTITY : null;
			default -> null;
		};
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

	private LiquidityHoldCommand parseCommand(String rawBody) {
		final JsonNode body;
		try {
			// 访问证明已在 Controller 入口完成；手动解析可避免 Spring 消息转换器先暴露不可见资源的格式差异。
			body = rawBody == null ? null : objectMapper.readTree(rawBody);
		} catch (RuntimeException exception) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		if (body == null || !body.isObject() || body.size() < 5) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		for (String field : body.propertyNames()) {
			if (!COMMAND_FIELDS.contains(field)) {
				throw new app.ziji.account.application.LiquidityHoldException.Validation();
			}
		}
		for (String required : List.of("type", "amount", "currency", "effectiveAt", "reason")) {
			if (!body.has(required) || body.get(required).isNull()) {
				throw new app.ziji.account.application.LiquidityHoldException.Validation();
			}
		}
		if (!body.get("type").isTextual() || !body.get("amount").isTextual()
			|| !body.get("currency").isTextual() || !body.get("effectiveAt").isTextual()
			|| !body.get("reason").isTextual()
			|| (body.has("expiresAt") && !body.get("expiresAt").isNull() && !body.get("expiresAt").isTextual())) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		JsonNode amountNode = body.get("amount");
		// amount/currency are deliberately separate top-level values; the former nested draft shape is invalid.
		if (!amountNode.isTextual()) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		try {
			String amountText = amountNode.textValue();
			if (amountText == null || !amountText.matches("^(0*[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$|^0\\.(0[1-9]|[1-9][0-9]?)$")) {
				throw new NumberFormatException();
			}
			BigDecimal amount = new BigDecimal(amountText);
			AccountCurrency currency = AccountCurrency.fromCode(body.get("currency").textValue());
			Instant effectiveAt = parseInstant(body.get("effectiveAt").textValue());
			Instant expiresAt = body.has("expiresAt") && !body.get("expiresAt").isNull()
				? parseInstant(body.get("expiresAt").textValue()) : null;
			String reason = body.get("reason").textValue();
			if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
				throw new NumberFormatException();
			}
			return new LiquidityHoldCommand(
				app.ziji.account.domain.LiquidityHoldType.valueOf(body.get("type").textValue()),
				amount, currency, effectiveAt, expiresAt, reason);
		} catch (RuntimeException exception) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
	}

	private String requestHash(String resource, Object payload, String ifMatch) {
		return IdempotencyRequestHasher.hash("POST", "application/json", resource, payload, ifMatch);
	}

	private static Map<String, Object> commandPayload(LiquidityHoldCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", command.type());
		payload.put("amount", IdempotencyRequestHasher.decimal(command.amount().toPlainString()));
		payload.put("currency", command.currency());
		payload.put("effectiveAt", command.effectiveAt());
		payload.put("expiresAt", command.expiresAt());
		payload.put("reason", command.reason());
		return payload;
	}

	private static IdempotencyResponse.ResourceReference resourceReference(String location, LiquidityHold hold) {
		return new IdempotencyResponse.ResourceReference(location, hold.etag(), (long) hold.version());
	}

	private static String holdLocation(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liquidity-holds";
	}

	private static Instant parseInstant(String value) {
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException exception) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
	}

	private static int parseIfMatch(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("If-Match");
		String value = request.getHeader("If-Match");
		if (value == null || values == null || !values.hasMoreElements()) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		String first = values.nextElement();
		if (values.hasMoreElements() || !value.equals(first) || !value.matches("\"[1-9][0-9]*\"")) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		try {
			return Integer.parseInt(value.substring(1, value.length() - 1));
		} catch (NumberFormatException exception) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
	}

	private static String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw new app.ziji.account.application.LiquidityHoldException.Validation();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw new app.ziji.account.application.LiquidityHoldException.Validation();
			}
		}
		return key;
	}

	private static Integer parseLimit(String raw) {
		if (raw == null) return null;
		if (!raw.matches("[1-9][0-9]*")) throw new app.ziji.account.application.LiquidityHoldException.Validation();
		try { return Integer.valueOf(raw); } catch (NumberFormatException exception) { throw new app.ziji.account.application.LiquidityHoldException.Validation(); }
	}

	private static UUID parseUuid(String raw) {
		try { return UUID.fromString(raw); } catch (RuntimeException exception) { throw new app.ziji.account.application.LiquidityHoldException.Validation(); }
	}

	private UUID currentUserId(HttpServletRequest request) {
		return currentUserIdResolver.resolve(request.getUserPrincipal());
	}

	private LiquidityHoldView view(LiquidityHold hold) {
		return new LiquidityHoldView(hold.id(), hold.accountId(), hold.rootHoldId(), hold.previousRevisionId(), hold.revisionNo(),
				hold.type().name(), hold.amount().toPlainString(), hold.currency().name(),
				hold.statusAt(clock.instant()).name(), hold.effectiveAt(), hold.expiresAt(), hold.source().name(), hold.note(),
			hold.createdBy(), hold.createdAt(), hold.updatedAt(), hold.releasedAt(), hold.endedAt(),
			hold.endReason() == null ? null : hold.endReason().name(), hold.version());
	}

	private static String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	public record LiquidityHoldListEnvelope(List<LiquidityHoldView> data, PageMeta meta) {}
	public record LiquidityHoldEnvelope(LiquidityHoldView data, ResponseMeta meta) {}
	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {}
	public record ResponseMeta(String requestId) {}
	public record LiquidityHoldView(
		UUID id, UUID accountId, UUID rootHoldId, UUID supersedesId, int revisionNo, String type, String amount,
		String currency, String status, Instant effectiveAt, Instant expiresAt, String source, String reason,
		UUID createdBy, Instant createdAt, Instant updatedAt, Instant releasedAt, Instant endedAt, String endReason, int version) {}
}
