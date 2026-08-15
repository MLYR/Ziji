package app.ziji.auth.interfaces;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import app.ziji.auth.application.AuthenticatedPasswordChangeCommand;
import app.ziji.auth.application.EmailRegistrationCommand;
import app.ziji.auth.application.PasswordResetCommand;
import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import tools.jackson.databind.JsonNode;

/** 认证 JSON 仅做传输格式校验并保留客户端原字符串；不会规范化 Hash 载荷或记录敏感字段。 */
final class AuthHttpRequests {

	private static final Set<String> CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	private AuthHttpRequests() {
	}

	static EmailChallengeInput emailChallenge(JsonNode body) {
		requireFields(body, Set.of("email", "deviceId"));
		String email = requiredText(body, "email", 1, 320);
		validateEmail(email);
		return new EmailChallengeInput(email, optionalText(body, "deviceId", 1, 200, false));
	}

	static RegistrationInput registration(JsonNode body) {
		requireFields(body, Set.of("email", "verificationCode", "password", "nickname", "timezone", "baseCurrency", "locale"));
		String email = requiredText(body, "email", 1, 320);
		validateEmail(email);
		String verificationCode = requiredText(body, "verificationCode", 6, 6);
		if (!verificationCode.matches("[0-9]{6}")) {
			throw invalid();
		}
		String password = requiredText(body, "password", 10, 128);
		String nickname = requiredText(body, "nickname", 1, 100);
		String timezone = requiredText(body, "timezone", 1, 64);
		try {
			ZoneId.of(timezone);
		} catch (DateTimeException exception) {
			throw invalid();
		}
		String baseCurrency = requiredText(body, "baseCurrency", 3, 3);
		if (!CURRENCIES.contains(baseCurrency)) {
			throw invalid();
		}
		String locale = requiredText(body, "locale", 2, 16);
		return new RegistrationInput(email, verificationCode, password, nickname, timezone, baseCurrency, locale);
	}

	static LoginInput login(JsonNode body) {
		requireFields(body, Set.of("email", "password", "deviceName", "deviceId"));
		String email = requiredText(body, "email", 1, 320);
		validateEmail(email);
		return new LoginInput(
			email,
			requiredText(body, "password", 1, 128),
			requiredText(body, "deviceName", 1, 100),
			optionalText(body, "deviceId", 1, 200, true));
	}

	static String mobileRefreshToken(JsonNode body) {
		requireFields(body, Set.of("refreshToken"));
		return requiredText(body, "refreshToken", 32, 500);
	}

	static ResetInput passwordReset(JsonNode body) {
		requireFields(body, Set.of("email", "challengeCode", "newPassword"));
		String email = requiredText(body, "email", 1, 320);
		validateEmail(email);
		String challengeCode = requiredText(body, "challengeCode", 6, 6);
		if (!challengeCode.matches("[0-9]{6}")) {
			throw invalid();
		}
		return new ResetInput(email, challengeCode, requiredText(body, "newPassword", 10, 128));
	}

	static AuthenticatedPasswordChangeCommand passwordChange(JsonNode body, java.util.UUID userId) {
		requireFields(body, Set.of("currentPassword", "newPassword"));
		return new AuthenticatedPasswordChangeCommand(
			userId,
			requiredText(body, "currentPassword", 1, 128),
			requiredText(body, "newPassword", 10, 128));
	}

	private static void requireFields(JsonNode body, Set<String> allowed) {
		if (body == null || !body.isObject()) {
			throw invalid();
		}
		for (String field : body.propertyNames()) {
			if (!allowed.contains(field)) {
				throw invalid();
			}
		}
	}

	private static String requiredText(JsonNode body, String field, int minLength, int maxLength) {
		if (!body.has(field)) {
			throw invalid();
		}
		JsonNode value = body.get(field);
		if (value == null || value.isNull() || !value.isTextual()) {
			throw invalid();
		}
		String text = value.textValue();
		if (text == null || text.isBlank() || text.length() < minLength || text.length() > maxLength) {
			throw invalid();
		}
		return text;
	}

	private static String optionalText(
		JsonNode body,
		String field,
		int minLength,
		int maxLength,
		boolean allowsNull) {
		if (!body.has(field)) {
			return null;
		}
		JsonNode value = body.get(field);
		if (value == null || value.isNull()) {
			if (allowsNull) {
				return null;
			}
			throw invalid();
		}
		if (!value.isTextual()) {
			throw invalid();
		}
		String text = value.textValue();
		if (text == null || text.isBlank() || text.length() < minLength || text.length() > maxLength) {
			throw invalid();
		}
		return text;
	}

	private static void validateEmail(String email) {
		try {
			EmailAddress.normalize(email);
		} catch (AuthDomainException exception) {
			throw invalid();
		}
	}

	private static AuthHttpValidationException invalid() {
		return new AuthHttpValidationException();
	}

	static final class EmailChallengeInput {
		private final String email;
		private final String deviceId;

		EmailChallengeInput(String email, String deviceId) {
			this.email = email;
			this.deviceId = deviceId;
		}

		String email() {
			return email;
		}

		String deviceId() {
			return deviceId;
		}
	}

	static final class RegistrationInput {
		private final String email;
		private final String verificationCode;
		private final String password;
		private final String nickname;
		private final String timezone;
		private final String baseCurrency;
		private final String locale;

		RegistrationInput(
			String email,
			String verificationCode,
			String password,
			String nickname,
			String timezone,
			String baseCurrency,
			String locale) {
			this.email = email;
			this.verificationCode = verificationCode;
			this.password = password;
			this.nickname = nickname;
			this.timezone = timezone;
			this.baseCurrency = baseCurrency;
			this.locale = locale;
		}

		EmailRegistrationCommand command() {
			return new EmailRegistrationCommand(email, verificationCode, password, nickname, timezone, baseCurrency, locale);
		}

		String email() {
			return email;
		}

		Map<String, Object> hashPayload() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("email", email);
			payload.put("verificationCode", verificationCode);
			payload.put("password", password);
			payload.put("nickname", nickname);
			payload.put("timezone", timezone);
			payload.put("baseCurrency", baseCurrency);
			payload.put("locale", locale);
			return Map.copyOf(payload);
		}
	}

	static final class LoginInput {
		private final String email;
		private final String password;
		private final String deviceName;
		private final String deviceId;

		LoginInput(String email, String password, String deviceName, String deviceId) {
			this.email = email;
			this.password = password;
			this.deviceName = deviceName;
			this.deviceId = deviceId;
		}

		String email() {
			return email;
		}

		String password() {
			return password;
		}

		String deviceName() {
			return deviceName;
		}

		String deviceId() {
			return deviceId;
		}
	}

	static final class ResetInput {
		private final String email;
		private final String challengeCode;
		private final String newPassword;

		ResetInput(String email, String challengeCode, String newPassword) {
			this.email = email;
			this.challengeCode = challengeCode;
			this.newPassword = newPassword;
		}

		PasswordResetCommand command() {
			return new PasswordResetCommand(email, challengeCode, newPassword);
		}

		String email() {
			return email;
		}

		Map<String, Object> hashPayload() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("email", email);
			payload.put("challengeCode", challengeCode);
			payload.put("newPassword", newPassword);
			return Map.copyOf(payload);
		}
	}
}
