package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** LiquidityHold 四个冻结 operation 的 HTTP 边界；校验后只调用 application port。 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/liquidity-holds")
public class LiquidityHoldController {

	private static final Set<String> COMMAND_FIELDS = Set.of("type", "amount", "currency", "effectiveAt", "expiresAt", "reason");

	private final LiquidityHoldUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;
	private final Clock clock;

	public LiquidityHoldController(
		LiquidityHoldUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency,
		Clock clock) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
		this.clock = clock;
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
		@RequestBody JsonNode body,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		LiquidityHoldCommand command = parseCommand(body);
		useCase.preflightCreate(userId, parsedAccountId);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds";
		String requestHash = requestHash(resource, commandPayload(command), null);
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "createLiquidityHold", key, requestHash, () -> {
				LiquidityHold created = useCase.create(userId, parsedAccountId, command, requestId(response));
				return IdempotencyWorkResult.completed(created, IdempotencyResponse.succeededResource(
					201, "LIQUIDITY_HOLD", created.id(), resourceReference(resource, created)));
			});
		return writeCreate(execution, parsedAccountId, request, response);
	}

	@PostMapping(path = "/{holdId}/revisions", consumes = MediaType.APPLICATION_JSON_VALUE, name = "reviseLiquidityHold")
	public ResponseEntity<?> revise(
		@PathVariable String accountId,
		@PathVariable String holdId,
		@RequestBody JsonNode body,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		UUID parsedHoldId = parseUuid(holdId);
		LiquidityHoldCommand command = parseCommand(body);
		int expectedVersion = parseIfMatch(request);
		useCase.preflightMutation(userId, parsedAccountId, parsedHoldId);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds/" + parsedHoldId + "/revisions";
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "reviseLiquidityHold", key, requestHash(resource, commandPayload(command), request.getHeader("If-Match")), () -> {
				LiquidityHold revised = useCase.revise(userId, parsedAccountId, parsedHoldId, expectedVersion, command, requestId(response));
				return IdempotencyWorkResult.completed(revised, IdempotencyResponse.succeededResource(
					201, "LIQUIDITY_HOLD", revised.id(), resourceReference(resource, revised)));
			});
		return writeMutation(execution, parsedAccountId, parsedHoldId, request, response, HttpStatus.CREATED);
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
		int expectedVersion = parseIfMatch(request);
		useCase.preflightMutation(userId, parsedAccountId, parsedHoldId);
		String key = idempotencyKey(request);
		String resource = "/api/v1/accounts/" + parsedAccountId + "/liquidity-holds/" + parsedHoldId + "/release";
		IdempotencyExecution<LiquidityHold> execution = idempotency.executeAuthenticated(
			userId, 1, "releaseLiquidityHold", key, requestHash(resource, Map.of(), request.getHeader("If-Match")), () -> {
				LiquidityHold released = useCase.release(userId, parsedAccountId, parsedHoldId, expectedVersion, requestId(response));
				return IdempotencyWorkResult.completed(released, IdempotencyResponse.succeededResource(
					200, "LIQUIDITY_HOLD", released.id(), resourceReference(resource, released)));
			});
		return writeMutation(execution, parsedAccountId, parsedHoldId, request, response, HttpStatus.OK);
	}

	private ResponseEntity<?> writeCreate(
		IdempotencyExecution<LiquidityHold> execution,
		UUID accountId,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			return ResponseEntity.status(HttpStatus.CREATED).eTag(execution.value().etag())
				.body(new LiquidityHoldEnvelope(view(execution.value()), new ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null && execution.response().reference() instanceof IdempotencyResponse.ResourceReference reference
			&& execution.response().resourceId() != null && reference.resourceVersion() != null) {
			LiquidityHold hold = useCase.replay(currentUserId(request), accountId, execution.response().resourceId(), replayVersion(reference));
			return ResponseEntity.status(HttpStatus.CREATED).eTag(replayEtag(hold, reference))
				.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
		}
		return idempotencyProblem(execution, request, response);
	}

	private ResponseEntity<?> writeMutation(
		IdempotencyExecution<LiquidityHold> execution,
		UUID accountId,
		UUID holdId,
		HttpServletRequest request,
		HttpServletResponse response,
		HttpStatus successStatus) {
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			return ResponseEntity.status(successStatus).eTag(execution.value().etag())
				.body(new LiquidityHoldEnvelope(view(execution.value()), new ResponseMeta(requestId(response))));
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED
			&& execution.response() != null && execution.response().reference() instanceof IdempotencyResponse.ResourceReference reference
			&& execution.response().resourceId() != null && reference.resourceVersion() != null) {
			LiquidityHold hold = useCase.replay(currentUserId(request), accountId, execution.response().resourceId(), replayVersion(reference));
			return ResponseEntity.status(successStatus).eTag(replayEtag(hold, reference))
				.body(new LiquidityHoldEnvelope(view(hold), new ResponseMeta(requestId(response))));
		}
		return idempotencyProblem(execution, request, response);
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
		org.springframework.http.ProblemDetail detail = org.springframework.http.ProblemDetail.forStatusAndDetail(status, code);
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfter) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	private LiquidityHoldCommand parseCommand(JsonNode body) {
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
