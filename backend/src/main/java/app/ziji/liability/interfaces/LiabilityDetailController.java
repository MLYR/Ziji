package app.ziji.liability.interfaces;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.ziji.liability.application.LiabilityDetailPutCondition;
import app.ziji.liability.application.LiabilityDetailUseCase;
import app.ziji.liability.application.LiabilityDetailWriteResult;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailException;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 独立负债详情 HTTP seam；安全前置顺序由 application port 与本边界共同保持。 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/liability-details")
public class LiabilityDetailController {

	private static final Set<String> FIELDS = Set.of(
		"interestRate", "loanDate", "dueDate", "billingDay", "repaymentDay", "currentAmountDue");
	private static final String RATE_PATTERN = "^(0(\\.[0-9]{1,8})?|1(\\.0{1,8})?)$";
	private static final String MONEY_PATTERN = "^(0|[1-9][0-9]{0,21})(\\.[0-9]{1,2})?$";

	private final LiabilityDetailUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;

	public LiabilityDetailController(
		LiabilityDetailUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(name = "getLiabilityDetails")
	public ResponseEntity<LiabilityDetailEnvelope> get(
		@PathVariable String accountId,
		java.security.Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		LiabilityDetail detail = useCase.get(userId, parseUuid(accountId));
		return ResponseEntity.ok().eTag(detail.etag())
			.body(new LiabilityDetailEnvelope(view(detail), new ResponseMeta(requestId(response))));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, name = "putLiabilityDetails")
	public ResponseEntity<?> put(
		@PathVariable String accountId,
		@RequestBody JsonNode body,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		// 不可见账户和写权限必须先于条件头、载荷与幂等前置，避免资源枚举和记录污染。
		useCase.authorizeWrite(userId, parsedAccountId);
		LiabilityDetailPutCondition condition = parsePutCondition(request);
		String key = idempotencyKey(request);
		LiabilityDetailValues values = parseComplete(body);
		LiabilityDetailWriteResult result = useCase.put(userId, parsedAccountId, condition, values, key);
		return ResponseEntity.status(result.status()).eTag(result.detail().etag())
			.body(new LiabilityDetailEnvelope(view(result.detail()), new ResponseMeta(requestId(response))));
	}

	@PatchMapping(consumes = "application/merge-patch+json", name = "patchLiabilityDetails")
	public ResponseEntity<?> patch(
		@PathVariable String accountId,
		@RequestBody JsonNode body,
		java.security.Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		UUID parsedAccountId = parseUuid(accountId);
		useCase.authorizeWrite(userId, parsedAccountId);
		int expectedVersion = parseIfMatch(request);
		String key = idempotencyKey(request);
		LiabilityDetailPatch patch = parsePatch(body);
		LiabilityDetailWriteResult result = useCase.patch(userId, parsedAccountId, expectedVersion, patch, key);
		return ResponseEntity.status(HttpStatus.OK).eTag(result.detail().etag())
			.body(new LiabilityDetailEnvelope(view(result.detail()), new ResponseMeta(requestId(response))));
	}

	private LiabilityDetailPutCondition parsePutCondition(HttpServletRequest request) {
		List<String> ifMatch = headers(request, "If-Match");
		List<String> ifNoneMatch = headers(request, "If-None-Match");
		if (ifMatch.size() > 1 || ifNoneMatch.size() > 1 || ifMatch.size() + ifNoneMatch.size() != 1) {
			throw invalid();
		}
		if (!ifNoneMatch.isEmpty()) {
			if (!"*".equals(ifNoneMatch.getFirst())) {
				throw invalid();
			}
			return LiabilityDetailPutCondition.initial();
		}
		return LiabilityDetailPutCondition.replace(parseStrongVersion(ifMatch.getFirst()));
	}

	private int parseIfMatch(HttpServletRequest request) {
		List<String> values = headers(request, "If-Match");
		if (!headers(request, "If-None-Match").isEmpty()) {
			throw invalid();
		}
		if (values.size() != 1) {
			throw invalid();
		}
		return parseStrongVersion(values.getFirst());
	}

	private int parseStrongVersion(String value) {
		if (value == null || !value.matches("\"[1-9][0-9]*\"")) {
			throw invalid();
		}
		try {
			return Math.toIntExact(Long.parseLong(value.substring(1, value.length() - 1)));
		} catch (ArithmeticException | NumberFormatException exception) {
			throw invalid();
		}
	}

	private LiabilityDetailValues parseComplete(JsonNode body) {
		validateObject(body, true);
		return values(body);
	}

	private LiabilityDetailPatch parsePatch(JsonNode body) {
		validateObject(body, false);
		return new LiabilityDetailPatch(
			body.has("interestRate"), parseRate(body.get("interestRate")),
			body.has("loanDate"), parseDate(body.get("loanDate")),
			body.has("dueDate"), parseDate(body.get("dueDate")),
			body.has("billingDay"), parseDay(body.get("billingDay")),
			body.has("repaymentDay"), parseDay(body.get("repaymentDay")),
			body.has("currentAmountDue"), parseMoney(body.get("currentAmountDue")));
	}

	private LiabilityDetailValues values(JsonNode body) {
		return new LiabilityDetailValues(
			parseRate(body.get("interestRate")),
			parseDate(body.get("loanDate")),
			parseDate(body.get("dueDate")),
			parseDay(body.get("billingDay")),
			parseDay(body.get("repaymentDay")),
			parseMoney(body.get("currentAmountDue")));
	}

	private void validateObject(JsonNode body, boolean complete) {
		if (body == null || !body.isObject() || (!complete && body.size() < 1)) {
			throw invalid();
		}
		for (String field : body.propertyNames()) {
			if (!FIELDS.contains(field)) {
				throw invalid();
			}
		}
		if (complete) {
			for (String field : FIELDS) {
				if (!body.has(field)) {
					throw invalid();
				}
			}
		}
	}

	private BigDecimal parseRate(JsonNode node) {
		if (node == null || node.isNull()) return null;
		if (!node.isTextual() || !node.textValue().matches(RATE_PATTERN)) throw invalid();
		try {
			return new BigDecimal(node.textValue());
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private BigDecimal parseMoney(JsonNode node) {
		if (node == null || node.isNull()) return null;
		if (!node.isTextual() || !node.textValue().matches(MONEY_PATTERN)) throw invalid();
		try {
			return new BigDecimal(node.textValue());
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private LocalDate parseDate(JsonNode node) {
		if (node == null || node.isNull()) return null;
		if (!node.isTextual()) throw invalid();
		try {
			return LocalDate.parse(node.textValue(), DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private Integer parseDay(JsonNode node) {
		if (node == null || node.isNull()) return null;
		if (!node.isIntegralNumber() || !node.canConvertToInt()) throw invalid();
		return node.intValue();
	}

	private String idempotencyKey(HttpServletRequest request) {
		List<String> values = headers(request, "Idempotency-Key");
		if (values.size() != 1) throw invalid();
		String key = values.getFirst();
		if (key.length() < 16 || key.length() > 100) throw invalid();
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) throw invalid();
		}
		return key;
	}

	private static List<String> headers(HttpServletRequest request, String name) {
		Enumeration<String> values = request.getHeaders(name);
		if (values == null) return List.of();
		return java.util.Collections.list(values);
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw invalid();
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private static LiabilityDetailException.Validation invalid() {
		return new LiabilityDetailException.Validation();
	}

	private static LiabilityDetailView view(LiabilityDetail detail) {
		return new LiabilityDetailView(
			detail.accountId(), decimalText(detail.interestRate()), detail.loanDate(), detail.dueDate(),
			detail.billingDay(), detail.repaymentDay(), decimalText(detail.currentAmountDue()), detail.version());
	}

	private static String decimalText(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	private static String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	public record LiabilityDetailEnvelope(LiabilityDetailView data, ResponseMeta meta) {
	}

	public record LiabilityDetailView(
		UUID accountId,
		String interestRate,
		LocalDate loanDate,
		LocalDate dueDate,
		Integer billingDay,
		Integer repaymentDay,
		String currentAmountDue,
		int version) {
	}

	public record ResponseMeta(String requestId) {
	}
}
