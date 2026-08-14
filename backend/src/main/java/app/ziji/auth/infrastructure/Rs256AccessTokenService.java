package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.AccessTokenValidationException;
import app.ziji.auth.application.IssuedAccessToken;
import app.ziji.auth.application.VerifiedAccessToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 仅使用 JCA RS256 的 JWT 编解码器；签名私钥与原始 Token 从不落库或写日志。 */
@Component
final class Rs256AccessTokenService implements AccessTokenService {

	private static final String ISSUER = "ziji-backend";
	private static final String AUDIENCE = "ziji-api";
	private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(30);
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	private final AccessTokenKeyRing keyRing;
	private final ObjectMapper objectMapper;
	private final Supplier<UUID> uuidGenerator;

	@Autowired
	Rs256AccessTokenService(AccessTokenKeyRing keyRing, ObjectMapper objectMapper) {
		this(keyRing, objectMapper, UUID::randomUUID);
	}

	Rs256AccessTokenService(
		AccessTokenKeyRing keyRing,
		ObjectMapper objectMapper,
		Supplier<UUID> uuidGenerator) {
		if (keyRing == null || objectMapper == null || uuidGenerator == null) {
			throw new AuthInfrastructureException("Access Token 服务依赖缺失。");
		}
		this.keyRing = keyRing;
		this.objectMapper = objectMapper;
		this.uuidGenerator = uuidGenerator;
	}

	@Override
	public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant sessionExpiresAt) {
		if (userId == null || sessionId == null || issuedAt == null || sessionExpiresAt == null) {
			throw new AccessTokenValidationException();
		}
		// JWT 使用秒精度；向下截断保证 exp 永不超过稳定会话 expiresAt。
		Instant issued = issuedAt.truncatedTo(ChronoUnit.SECONDS);
		Instant expires = min(issued.plus(MAXIMUM_LIFETIME), sessionExpiresAt.truncatedTo(ChronoUnit.SECONDS));
		if (!expires.isAfter(issued)) {
			throw new AccessTokenValidationException();
		}
		UUID tokenId = uuidGenerator.get();
		if (tokenId == null) {
			throw new AuthInfrastructureException("Access Token ID 生成失败。");
		}
		AccessTokenKeyRing.SigningKey signingKey = keyRing.current();
		String header = "{\"alg\":\"RS256\",\"typ\":\"at+jwt\",\"kid\":\"" + signingKey.kid() + "\"}";
		String payload = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
			+ "\",\"sub\":\"" + userId + "\",\"sid\":\"" + sessionId + "\",\"jti\":\"" + tokenId
			+ "\",\"iat\":" + issued.getEpochSecond() + ",\"nbf\":" + issued.getEpochSecond()
			+ ",\"exp\":" + expires.getEpochSecond() + "}";
		String signingInput = encode(header.getBytes(StandardCharsets.UTF_8)) + "."
			+ encode(payload.getBytes(StandardCharsets.UTF_8));
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(signingKey.privateKey());
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return new IssuedAccessToken(signingInput + "." + encode(signature.sign()), expires);
		} catch (Exception exception) {
			throw new AuthInfrastructureException("Access Token 签发失败。", exception);
		}
	}

	@Override
	public VerifiedAccessToken verify(String encodedToken, Instant now) {
		if (now == null) {
			throw new AccessTokenValidationException();
		}
		String[] parts = split(encodedToken);
		JsonNode header = readJson(parts[0]);
		String algorithm = text(header, "alg");
		String type = text(header, "typ");
		String kid = text(header, "kid");
		if (!"RS256".equals(algorithm) || !"at+jwt".equals(type)) {
			throw new AccessTokenValidationException();
		}
		PublicKey verificationKey = keyRing.verificationKey(kid).orElseThrow(AccessTokenValidationException::new);
		verifySignature(parts, verificationKey);

		JsonNode claims = readJson(parts[1]);
		if (!ISSUER.equals(text(claims, "iss")) || !AUDIENCE.equals(text(claims, "aud"))) {
			throw new AccessTokenValidationException();
		}
		UUID userId = uuid(text(claims, "sub"));
		UUID sessionId = uuid(text(claims, "sid"));
		UUID tokenId = uuid(text(claims, "jti"));
		Instant issuedAt = instant(claims, "iat");
		Instant notBefore = instant(claims, "nbf");
		Instant expiresAt = instant(claims, "exp");
		validateTimes(issuedAt, notBefore, expiresAt, now);
		return new VerifiedAccessToken(userId, sessionId, tokenId, issuedAt, notBefore, expiresAt, kid);
	}

	private static Instant min(Instant first, Instant second) {
		return first.isAfter(second) ? second : first;
	}

	private static String[] split(String value) {
		if (value == null || value.isBlank()) {
			throw new AccessTokenValidationException();
		}
		String[] parts = value.split("\\.", -1);
		if (parts.length != 3 || !base64Url(parts[0]) || !base64Url(parts[1]) || !base64Url(parts[2])) {
			throw new AccessTokenValidationException();
		}
		return parts;
	}

	private JsonNode readJson(String segment) {
		try {
			JsonNode value = objectMapper.readTree(decode(segment));
			if (value == null || !value.isObject()) {
				throw new AccessTokenValidationException();
			}
			return value;
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new AccessTokenValidationException();
		}
	}

	private static void verifySignature(String[] parts, PublicKey key) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(key);
			signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			if (!signature.verify(decode(parts[2]))) {
				throw new AccessTokenValidationException();
			}
		} catch (AccessTokenValidationException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new AccessTokenValidationException();
		}
	}

	private static String text(JsonNode object, String field) {
		JsonNode value = object.get(field);
		if (value == null || !value.isTextual() || value.asString().isBlank()) {
			throw new AccessTokenValidationException();
		}
		return value.asString();
	}

	private static UUID uuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw new AccessTokenValidationException();
		}
	}

	private static Instant instant(JsonNode object, String field) {
		JsonNode value = object.get(field);
		if (value == null || !value.isIntegralNumber()) {
			throw new AccessTokenValidationException();
		}
		try {
			return Instant.ofEpochSecond(Long.parseLong(value.asString()));
		} catch (NumberFormatException | java.time.DateTimeException exception) {
			throw new AccessTokenValidationException();
		}
	}

	private static void validateTimes(Instant issuedAt, Instant notBefore, Instant expiresAt, Instant now) {
		if (!notBefore.equals(issuedAt) || !expiresAt.isAfter(issuedAt)
			|| Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0
			|| issuedAt.isAfter(now.plus(CLOCK_SKEW)) || notBefore.isAfter(now.plus(CLOCK_SKEW))
			|| !expiresAt.isAfter(now.minus(CLOCK_SKEW))) {
			throw new AccessTokenValidationException();
		}
	}

	private static boolean base64Url(String value) {
		return value != null && !value.isEmpty() && value.matches("[A-Za-z0-9_-]+") && value.indexOf('=') < 0;
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static byte[] decode(String value) {
		try {
			return Base64.getUrlDecoder().decode(value);
		} catch (IllegalArgumentException exception) {
			throw new AccessTokenValidationException();
		}
	}
}
