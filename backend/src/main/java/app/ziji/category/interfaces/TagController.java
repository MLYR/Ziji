package app.ziji.category.interfaces;

import java.security.Principal;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.category.application.TagCommand;
import app.ziji.category.application.TagNameConflictException;
import app.ziji.category.application.TagNotVisibleException;
import app.ziji.category.application.TagPage;
import app.ziji.category.application.TagService;
import app.ziji.category.application.TagSnapshot;
import app.ziji.category.application.TagStatus;
import app.ziji.category.application.TagUpdateCommand;
import app.ziji.category.application.TagVersionConflictException;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 个人标签 HTTP 边界；创建走统一幂等门禁。 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

	private static final java.util.Set<String> CREATE_FIELDS = java.util.Set.of("name");
	private static final java.util.Set<String> UPDATE_FIELDS = java.util.Set.of("name", "status");

	private final TagService tagService;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;

	public TagController(
		TagService tagService,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency) {
		this.tagService = tagService;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
	}

	@GetMapping(name = "listTags")
	public ResponseEntity<TagListEnvelope> listTags(
		@RequestParam(name = "limit", required = false) String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		TagPage page = tagService.listTags(userId, parseLimit(rawLimit), cursor);
		return ResponseEntity.ok(new TagListEnvelope(
			page.tags().stream().map(TagController::view).toList(),
			new TagPageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, name = "createTag")
	public ResponseEntity<?> createTag(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		TagCommand command = parseCreate(body);
		String resource = "/api/v1/tags";
		String key = idempotencyKey(request);
		IdempotencyExecution<TagSnapshot> execution = idempotency.executeAuthenticated(
			userId, 1, "createTag", key, requestHash(command), () -> {
				try {
					Optional<TagSnapshot> created = tagService.createTag(userId, command);
					if (created.isPresent()) {
						TagSnapshot tag = created.get();
						return IdempotencyWorkResult.completed(tag, IdempotencyResponse.succeededResource(
							201, "TAG", tag.id(), new IdempotencyResponse.ResourceReference(
								resource + "/" + tag.id(), etag(tag.version()), (long) tag.version())));
					}
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(409, "TAG_NAME_ALREADY_EXISTS"));
				} catch (TagNameConflictException exception) {
					// 并发唯一约束冲突无业务事实副作用，统一幂等终态仍保存稳定 409。
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(409, "TAG_NAME_ALREADY_EXISTS"));
				}
			});
		return writeCreate(execution, userId, response);
	}

	@PatchMapping(path = "/{tagId}", consumes = "application/merge-patch+json", name = "updateTag")
	public ResponseEntity<TagEnvelope> updateTag(
		@PathVariable String tagId,
		@RequestHeader(value = "If-Match", required = false) String ifMatch,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedTagId = parseUuid(tagId);
		int expectedVersion = parseIfMatch(ifMatch);
		TagUpdateCommand command = parseUpdate(body);
		TagSnapshot tag = tagService.updateTag(userId, parsedTagId, command, expectedVersion);
		return ResponseEntity.ok().eTag(etag(tag.version()))
			.body(new TagEnvelope(view(tag), new TagResponseMeta(requestId(response))));
	}

	private ResponseEntity<?> writeCreate(
		IdempotencyExecution<TagSnapshot> execution,
		UUID userId,
		HttpServletResponse response) {
		IdempotencyResponse stored = execution.response();
		if (execution.status() == IdempotencyExecution.Status.EXECUTED
			&& stored != null && stored.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			return problem(HttpStatus.resolve(stored.responseStatus()), problem.errorCode(), response, false);
		}
		if (execution.status() == IdempotencyExecution.Status.EXECUTED && execution.value() != null) {
			return success(execution.value(), response, true);
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED && stored != null
			&& stored.reference() instanceof IdempotencyResponse.ResourceReference reference
			&& stored.resourceId() != null && reference.resourceVersion() != null) {
			TagSnapshot current;
			try {
				current = tagService.getVisibleTag(userId, stored.resourceId());
			} catch (RuntimeException exception) {
				// 撤权后不回放不可见资源，也不伪造首次成功。
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			if (!reference.etag().equals(etag(current.version()))
				|| reference.resourceVersion() != current.version()) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			return success(current, response, true);
		}
		return idempotencyProblem(execution, response);
	}

	private ResponseEntity<TagEnvelope> success(
		TagSnapshot tag,
		HttpServletResponse response,
		boolean created) {
		ResponseEntity.BodyBuilder builder = created
			? ResponseEntity.status(HttpStatus.CREATED) : ResponseEntity.status(HttpStatus.OK);
		return builder.eTag(etag(tag.version()))
			.body(new TagEnvelope(view(tag), new TagResponseMeta(requestId(response))));
	}

	private TagCommand parseCreate(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new app.ziji.category.application.TagValidationException();
		}
		for (String field : body.propertyNames()) {
			if (!CREATE_FIELDS.contains(field)) {
				throw new app.ziji.category.application.TagValidationException();
			}
		}
		if (!body.has("name") || !body.get("name").isTextual()) {
			throw new app.ziji.category.application.TagValidationException();
		}
		return new TagCommand(body.get("name").textValue());
	}

	private TagUpdateCommand parseUpdate(JsonNode body) {
		if (body == null || !body.isObject() || body.isEmpty()) {
			throw new app.ziji.category.application.TagValidationException();
		}
		for (String field : body.propertyNames()) {
			if (!UPDATE_FIELDS.contains(field)) {
				throw new app.ziji.category.application.TagValidationException();
			}
		}
		String name = null;
		TagStatus status = null;
		if (body.has("name")) {
			if (!body.get("name").isTextual()) {
				throw new app.ziji.category.application.TagValidationException();
			}
			name = body.get("name").textValue();
		}
		if (body.has("status")) {
			if (!body.get("status").isTextual()) {
				throw new app.ziji.category.application.TagValidationException();
			}
			try {
				status = TagStatus.valueOf(body.get("status").textValue());
			} catch (RuntimeException exception) {
				throw new app.ziji.category.application.TagValidationException();
			}
		}
		return new TagUpdateCommand(name, status);
	}

	private String requestHash(TagCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", command.name());
		return IdempotencyRequestHasher.hash(
			"POST", MediaType.APPLICATION_JSON_VALUE, "/api/v1/tags", payload, null);
	}

	private String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw new app.ziji.category.application.TagValidationException();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw new app.ziji.category.application.TagValidationException();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw new app.ziji.category.application.TagValidationException();
			}
		}
		return key;
	}

	private Integer parseLimit(String rawLimit) {
		if (rawLimit == null) {
			return null;
		}
		if (!rawLimit.matches("[1-9][0-9]*") || rawLimit.length() > 3) {
			throw new app.ziji.category.application.TagValidationException();
		}
		return Integer.valueOf(rawLimit);
	}

	private UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (RuntimeException exception) {
			throw new app.ziji.category.application.TagValidationException();
		}
	}

	private int parseIfMatch(String value) {
		if (value == null || !value.matches("\"[1-9][0-9]*\"")) {
			throw new app.ziji.category.application.TagValidationException();
		}
		return Integer.parseInt(value.substring(1, value.length() - 1));
	}

	private static TagView view(TagSnapshot tag) {
		return new TagView(tag.id(), tag.name(), tag.status().name(), tag.version());
	}

	private static String etag(int version) {
		return "\"" + version + "\"";
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	private ResponseEntity<ProblemDetail> problem(
		HttpStatus status,
		String code,
		HttpServletResponse response,
		boolean retryAfter) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfter) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	private ResponseEntity<ProblemDetail> idempotencyProblem(
		IdempotencyExecution<?> execution,
		HttpServletResponse response) {
		if (execution.status() == IdempotencyExecution.Status.REQUEST_IN_PROGRESS) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", response, true);
		}
		if (execution.status() == IdempotencyExecution.Status.KEY_REUSED) {
			return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", response, false);
		}
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
	}

	public record TagView(UUID id, String name, String status, int version) {
	}

	public record TagEnvelope(TagView data, TagResponseMeta meta) {
	}

	public record TagListEnvelope(List<TagView> data, TagPageMeta meta) {
		public TagListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record TagPageMeta(String requestId, String nextCursor, boolean hasMore) {
	}

	public record TagResponseMeta(String requestId) {
	}
}
