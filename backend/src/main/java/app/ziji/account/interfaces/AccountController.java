package app.ziji.account.interfaces;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import app.ziji.account.application.AccountPage;
import app.ziji.account.application.AccountQueryResult;
import app.ziji.account.application.AccountQueryUseCase;
import app.ziji.account.application.AccountQueryValidationException;
import app.ziji.account.domain.AccountPatch;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

	private final AccountQueryUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;

	public AccountController(
		AccountQueryUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
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

	public record AccountListEnvelope(List<AccountView> data, PageMeta meta) {
		public AccountListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record AccountEnvelope(AccountView data, ResponseMeta meta) {
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
}
