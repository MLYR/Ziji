package app.ziji.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import app.ziji.auth.application.AccessTokenValidationException;
import app.ziji.auth.application.IssuedAccessToken;
import app.ziji.auth.application.VerifiedAccessToken;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RS256 Access Token 的 header、Claims、时间上限、公钥轮换和启动密钥校验测试。 */
class Rs256AccessTokenServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private static final KeyPair CURRENT = keyPair(2048);
	private static final KeyPair PREVIOUS = keyPair(2048);

	@Test
	void issuesAndVerifiesExactRs256HeaderAndRequiredClaims() throws Exception {
		AccessTokenKeyRing keyRing = keyRing(CURRENT, "current-kid-1", null, null, Duration.ofHours(24));
		Rs256AccessTokenService service = service(keyRing);
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();

		IssuedAccessToken issued = service.issue(userId, sessionId, NOW, NOW.plus(Duration.ofDays(30)));
		String[] parts = issued.value().split("\\.");
		ObjectMapper mapper = new ObjectMapper();
		JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
		JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
		VerifiedAccessToken verified = service.verify(issued.value(), NOW.plusSeconds(10));

		assertEquals("RS256", header.get("alg").asString());
		assertEquals("at+jwt", header.get("typ").asString());
		assertEquals("current-kid-1", header.get("kid").asString());
		assertEquals("ziji-backend", claims.get("iss").asString());
		assertEquals("ziji-api", claims.get("aud").asString());
		assertEquals(userId.toString(), claims.get("sub").asString());
		assertEquals(sessionId.toString(), claims.get("sid").asString());
		assertTrue(claims.has("jti") && claims.has("iat") && claims.has("nbf") && claims.has("exp"));
		assertEquals(NOW.plusSeconds(1800), issued.expiresAt());
		assertEquals(userId, verified.userId());
		assertEquals(sessionId, verified.sessionId());
		assertEquals("current-kid-1", verified.keyId());
		assertFalse(issued.toString().contains(issued.value()));
	}

	@Test
	void accessTokenExpiryNeverExceedsStableSessionExpiry() {
		Rs256AccessTokenService service = service(keyRing(CURRENT, "current-kid-1", null, null, Duration.ofHours(24)));
		Instant sessionExpiresAt = NOW.plus(Duration.ofMinutes(7));

		IssuedAccessToken issued = service.issue(UUID.randomUUID(), UUID.randomUUID(), NOW, sessionExpiresAt);

		assertEquals(sessionExpiresAt, issued.expiresAt());
	}

	@Test
	void verifierAcceptsCurrentAndPreviousTrustedKidOnly() {
		Rs256AccessTokenService previousIssuer = service(keyRing(PREVIOUS, "previous-kid-1", null, null, Duration.ofHours(24)));
		AccessTokenKeyRing verifierRing = keyRing(CURRENT, "current-kid-1", PREVIOUS, "previous-kid-1", Duration.ofHours(24));
		Rs256AccessTokenService verifier = service(verifierRing);
		IssuedAccessToken previousToken = previousIssuer.issue(
			UUID.randomUUID(), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(30)));

		VerifiedAccessToken verified = verifier.verify(previousToken.value(), NOW);

		assertEquals("previous-kid-1", verified.keyId());
		String unknownKid = signed(verifierRing, "{\"alg\":\"RS256\",\"typ\":\"at+jwt\",\"kid\":\"unknown-kid\"}",
			payload("ziji-backend", "ziji-api", NOW, NOW.plusSeconds(1800)));
		assertThrows(AccessTokenValidationException.class, () -> verifier.verify(unknownKid, NOW));
	}

	@Test
	void rejectsWrongAlgorithmTypeIssuerAudienceAndIllegalTimes() {
		AccessTokenKeyRing keyRing = keyRing(CURRENT, "current-kid-1", null, null, Duration.ofHours(24));
		Rs256AccessTokenService service = service(keyRing);
		String validHeader = "{\"alg\":\"RS256\",\"typ\":\"at+jwt\",\"kid\":\"current-kid-1\"}";

		String wrongAlgorithm = signed(keyRing,
			"{\"alg\":\"HS256\",\"typ\":\"at+jwt\",\"kid\":\"current-kid-1\"}",
			payload("ziji-backend", "ziji-api", NOW, NOW.plusSeconds(1800)));
		String wrongType = signed(keyRing,
			"{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"current-kid-1\"}",
			payload("ziji-backend", "ziji-api", NOW, NOW.plusSeconds(1800)));
		String wrongIssuer = signed(keyRing, validHeader, payload("other", "ziji-api", NOW, NOW.plusSeconds(1800)));
		String wrongAudience = signed(keyRing, validHeader, payload("ziji-backend", "other", NOW, NOW.plusSeconds(1800)));
		String futureNotBefore = signed(keyRing, validHeader, payload("ziji-backend", "ziji-api",
			NOW.plusSeconds(61), NOW.plusSeconds(1861)));
		String excessiveLifetime = signed(keyRing, validHeader, payload("ziji-backend", "ziji-api", NOW, NOW.plusSeconds(1801)));

		assertThrows(AccessTokenValidationException.class, () -> service.verify(wrongAlgorithm, NOW));
		assertThrows(AccessTokenValidationException.class, () -> service.verify(wrongType, NOW));
		assertThrows(AccessTokenValidationException.class, () -> service.verify(wrongIssuer, NOW));
		assertThrows(AccessTokenValidationException.class, () -> service.verify(wrongAudience, NOW));
		assertThrows(AccessTokenValidationException.class, () -> service.verify(futureNotBefore, NOW));
		assertThrows(AccessTokenValidationException.class, () -> service.verify(excessiveLifetime, NOW));
	}

	@Test
	void keyConfigurationRejectsIncompleteRotationMismatchedOrWeakRsaMaterial() {
		AuthSecurityProperties.AccessTokenProperties partial = properties(CURRENT, "current-kid-1");
		partial.setPreviousKid("previous-kid-1");
		assertThrows(AuthInfrastructureException.class, () -> AccessTokenKeyRing.from(partial));

		AuthSecurityProperties.AccessTokenProperties shortRetention = properties(CURRENT, "current-kid-1");
		shortRetention.setPreviousKid("previous-kid-1");
		shortRetention.setPreviousPublicKeyX509Base64(base64(PREVIOUS.getPublic().getEncoded()));
		shortRetention.setPreviousPublicKeyRetention(Duration.ofHours(23));
		assertThrows(AuthInfrastructureException.class, () -> AccessTokenKeyRing.from(shortRetention));

		AuthSecurityProperties.AccessTokenProperties mismatch = properties(CURRENT, "current-kid-1");
		mismatch.setCurrentPublicKeyX509Base64(base64(PREVIOUS.getPublic().getEncoded()));
		assertThrows(AuthInfrastructureException.class, () -> AccessTokenKeyRing.from(mismatch));

		AuthSecurityProperties.AccessTokenProperties malformed = properties(CURRENT, "current-kid-1");
		malformed.setCurrentPrivateKeyPkcs8Base64("not-base64");
		assertThrows(AuthInfrastructureException.class, () -> AccessTokenKeyRing.from(malformed));

		AuthSecurityProperties.AccessTokenProperties malformedDer = properties(CURRENT, "current-kid-1");
		malformedDer.setCurrentPublicKeyX509Base64(base64(new byte[] { 1, 2, 3, 4 }));
		assertThrows(AuthInfrastructureException.class, () -> AccessTokenKeyRing.from(malformedDer));

		KeyPair weak = keyPair(1024);
		assertThrows(AuthInfrastructureException.class,
			() -> AccessTokenKeyRing.from(properties(weak, "weak-kid-1")));
	}

	private static Rs256AccessTokenService service(AccessTokenKeyRing keyRing) {
		return new Rs256AccessTokenService(keyRing, new ObjectMapper(), UUID::randomUUID);
	}

	private static AccessTokenKeyRing keyRing(
		KeyPair current,
		String currentKid,
		KeyPair previous,
		String previousKid,
		Duration retention) {
		AuthSecurityProperties.AccessTokenProperties properties = properties(current, currentKid);
		if (previous != null) {
			properties.setPreviousKid(previousKid);
			properties.setPreviousPublicKeyX509Base64(base64(previous.getPublic().getEncoded()));
			properties.setPreviousPublicKeyRetention(retention);
		}
		return AccessTokenKeyRing.from(properties);
	}

	private static AuthSecurityProperties.AccessTokenProperties properties(KeyPair pair, String kid) {
		AuthSecurityProperties.AccessTokenProperties properties = new AuthSecurityProperties.AccessTokenProperties();
		properties.setCurrentKid(kid);
		properties.setCurrentPrivateKeyPkcs8Base64(base64(pair.getPrivate().getEncoded()));
		properties.setCurrentPublicKeyX509Base64(base64(pair.getPublic().getEncoded()));
		return properties;
	}

	private static KeyPair keyPair(int bits) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(bits);
			return generator.generateKeyPair();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String signed(AccessTokenKeyRing keyRing, String header, String payload) {
		try {
			String input = encode(header.getBytes(StandardCharsets.UTF_8)) + "."
				+ encode(payload.getBytes(StandardCharsets.UTF_8));
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(keyRing.current().privateKey());
			signature.update(input.getBytes(StandardCharsets.US_ASCII));
			return input + "." + encode(signature.sign());
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String payload(String issuer, String audience, Instant issuedAt, Instant expiresAt) {
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		return "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"sub\":\"" + userId
			+ "\",\"sid\":\"" + sessionId + "\",\"jti\":\"" + UUID.randomUUID() + "\",\"iat\":"
			+ issuedAt.getEpochSecond() + ",\"nbf\":" + issuedAt.getEpochSecond() + ",\"exp\":"
			+ expiresAt.getEpochSecond() + "}";
	}

	private static String encode(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String base64(byte[] value) {
		return Base64.getEncoder().encodeToString(value);
	}
}
