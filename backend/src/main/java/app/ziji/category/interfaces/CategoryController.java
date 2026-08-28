package app.ziji.category.interfaces;

import java.security.Principal;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.category.application.CategoryCommand;
import app.ziji.category.application.CategoryNameConflictException;
import app.ziji.category.application.CategoryPage;
import app.ziji.category.application.CategoryService;
import app.ziji.category.application.CategorySnapshot;
import app.ziji.category.application.CategoryStatus;
import app.ziji.category.application.CategoryType;
import app.ziji.category.application.CategoryUpdateCommand;
import app.ziji.category.application.CategoryVersionConflictException;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 分类查询与创建 HTTP 边界；创建走统一幂等门禁。 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

	private static final java.util.Set<String> CREATE_FIELDS = java.util.Set.of(
		"name", "categoryType", "parentId", "accountId");
	private static final java.util.Set<String> UPDATE_FIELDS = java.util.Set.of("name", "status");

	private final CategoryService categoryService;
	private final CurrentUserIdResolver currentUserIdResolver;
	private final UnifiedIdempotencyService idempotency;

	public CategoryController(
		CategoryService categoryService,
		CurrentUserIdResolver currentUserIdResolver,
		UnifiedIdempotencyService idempotency) {
		this.categoryService = categoryService;
		this.currentUserIdResolver = currentUserIdResolver;
		this.idempotency = idempotency;
	}

	@PatchMapping(
		path = "/{categoryId}",
		consumes = "application/merge-patch+json",
		name = "updateCategory")
	public ResponseEntity<CategoryEnvelope> updateCategory(
		@PathVariable String categoryId,
		@RequestHeader(value = "If-Match", required = false) String ifMatch,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedCategoryId = parseUuid(categoryId);
		int expectedVersion = parseIfMatch(ifMatch);
		CategoryUpdateCommand command = parseUpdate(body);
		CategorySnapshot category = categoryService.updateCategory(
			userId, parsedCategoryId, command, expectedVersion);
		return success(category, category.version(), response, false);
	}

	@PostMapping(path = "/{categoryId}/merge", name = "mergeCategory")
	public ResponseEntity<?> mergeCategory(
		@PathVariable String categoryId,
		@RequestHeader(value = "If-Match", required = false) String ifMatch,
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedCategoryId = parseUuid(categoryId);
		UUID targetCategoryId = parseMergeTarget(body);
		int expectedVersion = parseIfMatch(ifMatch);
		String key = idempotencyKey(request);
		String resource = resource() + "/" + parsedCategoryId;
		IdempotencyExecution<CategorySnapshot> execution = idempotency.executeAuthenticated(
			userId, 1, "mergeCategory", key,
			requestHashMerge(resource, targetCategoryId, expectedVersion), () -> {
				try {
					CategorySnapshot merged = categoryService.mergeCategory(
						userId, parsedCategoryId, targetCategoryId, expectedVersion);
					return IdempotencyWorkResult.completed(merged, IdempotencyResponse.succeededResource(
						200, "CATEGORY", merged.id(), new IdempotencyResponse.ResourceReference(
							resource, etag(merged.version()), (long) merged.version())));
				} catch (CategoryVersionConflictException conflict) {
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinalVersionConflict(
							409, conflict.currentVersion(), resource));
				}
			});
		return writeMerge(execution, userId, response);
	}

	@GetMapping(name = "listCategories")
	public ResponseEntity<CategoryListEnvelope> listCategories(
		@RequestParam(name = "accountId", required = false) String rawAccountId,
		@RequestParam(name = "limit", required = false) String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID accountId = rawAccountId == null ? null : parseUuid(rawAccountId);
		CategoryPage page = categoryService.listCategories(userId, accountId, parseLimit(rawLimit), cursor);
		return ResponseEntity.ok(new CategoryListEnvelope(
			page.categories().stream().map(CategoryController::view).toList(),
			new PageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, name = "createCategory")
	public ResponseEntity<?> createCategory(
		@RequestBody JsonNode body,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		CategoryCommand command = parseCreate(body);
		String resource = "/api/v1/categories";
		String key = idempotencyKey(request);
		IdempotencyExecution<CategorySnapshot> execution = idempotency.executeAuthenticated(
			userId, 1, "createCategory", key, requestHash(command), () -> {
				try {
					Optional<CategorySnapshot> created = categoryService.createCategory(userId, command);
					if (created.isPresent()) {
						CategorySnapshot category = created.get();
						return IdempotencyWorkResult.completed(category, IdempotencyResponse.succeededResource(
							201, "CATEGORY", category.id(), new IdempotencyResponse.ResourceReference(
								resource + "/" + category.id(), etag(category.version()), (long) category.version())));
					}
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(409, "CATEGORY_NAME_ALREADY_EXISTS"));
				} catch (CategoryNameConflictException exception) {
					// 并发唯一约束冲突无业务事实副作用，仍映射稳定 409；外层回滚由统一幂等门禁接管。
					return IdempotencyWorkResult.completed(null,
						IdempotencyResponse.failedFinal(409, "CATEGORY_NAME_ALREADY_EXISTS"));
				}
			});
		return writeCreate(execution, userId, response);
	}

	private ResponseEntity<?> writeCreate(
		IdempotencyExecution<CategorySnapshot> execution,
		UUID userId,
		HttpServletResponse response) {
		IdempotencyResponse stored = execution.response();
		if (execution.status() == IdempotencyExecution.Status.EXECUTED
			&& stored != null && stored.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			return problem(HttpStatus.resolve(stored.responseStatus()), problem.errorCode(), response, false);
		}
		if (execution.status() == IdempotencyExecution.Status.EXECUTED && execution.value() != null) {
			return success(execution.value(), execution.value().version(), response, true);
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED && stored != null
			&& stored.reference() instanceof IdempotencyResponse.ResourceReference reference
			&& stored.resourceId() != null && reference.resourceVersion() != null) {
			CategorySnapshot current;
			try {
				current = categoryService.getVisibleCategory(userId, stored.resourceId());
			} catch (RuntimeException exception) {
				// 撤权后不回放不可见资源，也不降级为 404 伪造首次成功。
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			if (!reference.etag().equals(etag(current.version())) || reference.resourceVersion() != current.version()) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			return success(current, current.version(), response, true);
		}
		return idempotencyProblem(execution, response);
	}

	private ResponseEntity<?> writeMerge(
		IdempotencyExecution<CategorySnapshot> execution,
		UUID userId,
		HttpServletResponse response) {
		IdempotencyResponse stored = execution.response();
		if (execution.status() == IdempotencyExecution.Status.EXECUTED
			&& stored != null && stored.reference() instanceof IdempotencyResponse.VersionConflictReference conflict) {
			ProblemDetail detail = problemDetail(HttpStatus.CONFLICT, "VERSION_CONFLICT", response);
			detail.setProperty("versionConflict", java.util.Map.of(
				"currentVersion", conflict.currentVersion(),
				"currentEtag", conflict.currentEtag(),
				"resourceLocation", conflict.resourceLocation()));
			return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
		}
		if (execution.status() == IdempotencyExecution.Status.EXECUTED
			&& stored != null && stored.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			return problem(HttpStatus.resolve(stored.responseStatus()), problem.errorCode(), response, false);
		}
		if (execution.status() == IdempotencyExecution.Status.EXECUTED && execution.value() != null) {
			return success(execution.value(), execution.value().version(), response, false);
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED && stored != null
			&& stored.reference() instanceof IdempotencyResponse.ResourceReference reference
			&& stored.resourceId() != null && reference.resourceVersion() != null) {
			CategorySnapshot current;
			try {
				current = categoryService.getVisibleCategory(userId, stored.resourceId());
			} catch (RuntimeException exception) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			if (!reference.etag().equals(etag(current.version())) || reference.resourceVersion() != current.version()) {
				return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", response, false);
			}
			return success(current, current.version(), response, false);
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED && stored != null
			&& stored.reference() instanceof IdempotencyResponse.VersionConflictReference conflict) {
			ProblemDetail detail = problemDetail(HttpStatus.CONFLICT, "VERSION_CONFLICT", response);
			detail.setProperty("versionConflict", java.util.Map.of(
				"currentVersion", conflict.currentVersion(),
				"currentEtag", conflict.currentEtag(),
				"resourceLocation", conflict.resourceLocation()));
			return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
		}
		return idempotencyProblem(execution, response);
	}

	private ResponseEntity<CategoryEnvelope> success(
		CategorySnapshot category,
		int version,
		HttpServletResponse response,
		boolean created) {
		ResponseEntity.BodyBuilder builder = created
			? ResponseEntity.status(HttpStatus.CREATED) : ResponseEntity.status(HttpStatus.OK);
		return builder.eTag(etag(version))
			.body(new CategoryEnvelope(view(category), new ResponseMeta(requestId(response))));
	}

	private CategoryCommand parseCreate(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		for (String field : body.propertyNames()) {
			if (!CREATE_FIELDS.contains(field)) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
		}
		if (!body.has("name") || !body.get("name").isTextual()
			|| !body.has("categoryType") || !body.get("categoryType").isTextual()) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		try {
			return new CategoryCommand(
				body.get("name").textValue(),
				CategoryType.valueOf(body.get("categoryType").textValue()),
				body.has("parentId") && !body.get("parentId").isNull()
					? UUID.fromString(body.get("parentId").textValue()) : null,
				body.has("accountId") && !body.get("accountId").isNull()
					? UUID.fromString(body.get("accountId").textValue()) : null);
		} catch (RuntimeException exception) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
	}

	private CategoryUpdateCommand parseUpdate(JsonNode body) {
		if (body == null || !body.isObject() || body.size() == 0) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		for (String field : body.propertyNames()) {
			if (!UPDATE_FIELDS.contains(field)) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
		}
		String name = null;
		CategoryStatus status = null;
		if (body.has("name")) {
			if (!body.get("name").isTextual()) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
			name = body.get("name").textValue();
		}
		if (body.has("status")) {
			if (!body.get("status").isTextual()) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
			try {
				status = CategoryStatus.valueOf(body.get("status").textValue());
			} catch (RuntimeException exception) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
			if (status == CategoryStatus.MERGED) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
		}
		return new CategoryUpdateCommand(name, status);
	}

	private UUID parseMergeTarget(JsonNode body) {
		if (body == null || !body.isObject()
			|| !body.has("targetCategoryId") || !body.get("targetCategoryId").isTextual()
			|| body.size() != 1) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		return parseUuid(body.get("targetCategoryId").textValue());
	}

	private String requestHash(CategoryCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", command.name());
		payload.put("categoryType", command.categoryType());
		payload.put("parentId", command.parentId());
		payload.put("accountId", command.accountId());
		return IdempotencyRequestHasher.hash("POST", MediaType.APPLICATION_JSON_VALUE, resource(), payload, null);
	}

	private String requestHashMerge(String resource, UUID targetCategoryId, int expectedVersion) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetCategoryId", targetCategoryId);
		return IdempotencyRequestHasher.hash(
			"POST", MediaType.APPLICATION_JSON_VALUE, resource, payload, etag(expectedVersion));
	}

	private static String resource() {
		return "/api/v1/categories";
	}

	private String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw new app.ziji.category.application.CategoryValidationException();
			}
		}
		return key;
	}

	private Integer parseLimit(String rawLimit) {
		if (rawLimit == null) {
			return null;
		}
		if (!rawLimit.matches("[1-9][0-9]*") || rawLimit.length() > 3) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		try {
			return Integer.valueOf(rawLimit);
		} catch (NumberFormatException exception) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
	}

	private UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (RuntimeException exception) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
	}

	private int parseIfMatch(String value) {
		if (value == null || !value.matches("\"[1-9][0-9]*\"")) {
			throw new app.ziji.category.application.CategoryValidationException();
		}
		return Integer.parseInt(value.substring(1, value.length() - 1));
	}

	private static CategoryView view(CategorySnapshot category) {
		return new CategoryView(
			category.id(), category.type().name(), category.name(), category.parentId(),
			category.status().name(), category.mergedIntoId(), category.version());
	}

	private static String etag(int version) {
		return "\"" + version + "\"";
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	private ResponseEntity<org.springframework.http.ProblemDetail> problem(
		HttpStatus status,
		String code,
		HttpServletResponse response,
		boolean retryAfter) {
		org.springframework.http.ProblemDetail detail = org.springframework.http.ProblemDetail
			.forStatusAndDetail(status, code);
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (retryAfter) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	private org.springframework.http.ProblemDetail problemDetail(
		HttpStatus status,
		String code,
		HttpServletResponse response) {
		org.springframework.http.ProblemDetail detail = org.springframework.http.ProblemDetail
			.forStatusAndDetail(status, code);
		detail.setProperty("code", code);
		detail.setProperty("requestId", requestId(response));
		return detail;
	}

	private ResponseEntity<org.springframework.http.ProblemDetail> idempotencyProblem(
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

	public record CategoryView(
		UUID id,
		String categoryType,
		String name,
		UUID parentId,
		String status,
		UUID mergedIntoId,
		int version) {
	}

	public record CategoryEnvelope(CategoryView data, ResponseMeta meta) {
	}

	public record CategoryListEnvelope(java.util.List<CategoryView> data, PageMeta meta) {
		public CategoryListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}

	public record ResponseMeta(String requestId) {
	}
}
