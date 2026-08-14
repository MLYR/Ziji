package app.ziji.user.interfaces;

import java.security.Principal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.UserProfileUseCase;
import app.ziji.user.application.UserValidationException;
import app.ziji.user.domain.AmountFormat;
import app.ziji.user.domain.BaseCurrency;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户资料 HTTP 边界；merge-patch 只转换为类型化应用命令。 */
@RestController
@RequestMapping("/api/v1/users/me")
public final class UserProfileController {

	private static final String MERGE_PATCH = "application/merge-patch+json";
	private static final Set<String> PATCH_FIELDS = Set.of(
		"nickname", "timezone", "baseCurrency", "locale", "amountFormat");

	private final UserProfileUseCase useCase;
	private final CurrentUserIdResolver currentUserIdResolver;

	public UserProfileController(
		UserProfileUseCase useCase,
		CurrentUserIdResolver currentUserIdResolver) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(name = "getCurrentUser")
	public ResponseEntity<UserEnvelope> getCurrentUser(
		Principal principal,
		HttpServletResponse response) {
		UserProfile profile = useCase.getCurrentUser(currentUserIdResolver.resolve(principal));
		return ResponseEntity.ok()
			.eTag(profile.etag())
			.body(envelope(profile, requestId(response)));
	}

	@PatchMapping(
		name = "updateCurrentUser",
		consumes = MERGE_PATCH,
		produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<UserEnvelope> updateCurrentUser(
		Principal principal,
		@RequestHeader(value = "If-Match", required = false) String ifMatch,
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		int expectedVersion = parseIfMatch(ifMatch, request);
		UserProfilePatch patch = parsePatch(body);
		UUID userId = currentUserIdResolver.resolve(principal);
		UserProfile profile = useCase.updateCurrentUser(userId, expectedVersion, patch);
		return ResponseEntity.ok()
			.eTag(profile.etag())
			.body(envelope(profile, requestId(response)));
	}

	private UserProfilePatch parsePatch(JsonNode body) {
		if (body == null || !body.isObject() || body.size() == 0) {
			throw invalid();
		}
		for (Iterator<String> fields = body.fieldNames(); fields.hasNext();) {
			if (!PATCH_FIELDS.contains(fields.next())) {
				// 未知字段不能静默丢弃，避免客户端误以为设置已保存。
				throw invalid();
			}
		}
		return new UserProfilePatch(
			text(body, "nickname", 1, 100),
			timezone(body, "timezone"),
			currency(body, "baseCurrency"),
			text(body, "locale", 2, 16),
			amountFormat(body, "amountFormat"));
	}

	private Optional<String> text(JsonNode body, String field, int minLength, int maxLength) {
		if (!body.has(field)) {
			return Optional.empty();
		}
		JsonNode value = body.get(field);
		if (value == null || value.isNull() || !value.isTextual()) {
			throw invalid();
		}
		String text = value.textValue();
		if (text == null || text.isBlank() || text.length() < minLength || text.length() > maxLength) {
			throw invalid();
		}
		return Optional.of(text);
	}

	private Optional<ZoneId> timezone(JsonNode body, String field) {
		Optional<String> value = text(body, field, 1, 64);
		if (value.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(ZoneId.of(value.get()));
		} catch (DateTimeException exception) {
			// 不把 ZoneRulesProvider 的底层错误泄漏到 API 响应。
			throw invalid();
		}
	}

	private Optional<BaseCurrency> currency(JsonNode body, String field) {
		Optional<String> value = text(body, field, 3, 3);
		if (value.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(BaseCurrency.valueOf(value.get()));
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private Optional<AmountFormat> amountFormat(JsonNode body, String field) {
		Optional<String> value = text(body, field, 1, 32);
		if (value.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(AmountFormat.valueOf(value.get()));
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
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

	private UserValidationException invalid() {
		return new UserValidationException("用户资料请求无效。");
	}

	private UserEnvelope envelope(UserProfile profile, String requestId) {
		return new UserEnvelope(
			new UserView(profile.id(), profile.email(), profile.nickname(), profile.timezone().getId(),
				profile.baseCurrency().name(), profile.locale(), profile.amountFormat().name(),
				profile.status().name(), profile.version()),
			new ResponseMeta(requestId));
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	public record UserEnvelope(UserView data, ResponseMeta meta) {
	}

	public record UserView(
		UUID id,
		String email,
		String nickname,
		String timezone,
		String baseCurrency,
		String locale,
		String amountFormat,
		String status,
		int version) {
	}

	public record ResponseMeta(String requestId) {
	}
}
