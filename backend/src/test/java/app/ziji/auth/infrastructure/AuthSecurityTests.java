package app.ziji.auth.infrastructure;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.AuthRateLimitSubjects;
import app.ziji.auth.application.DeviceSessionQueryService;
import app.ziji.auth.application.DeviceSessionReadPort;
import app.ziji.auth.application.EncryptedCodeEnvelope;
import app.ziji.auth.application.IssuedAccessToken;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.VerifiedAccessToken;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.RateLimitDimension;
import app.ziji.auth.domain.SourceAddress;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HMAC 域分离、密钥轮换、来源地址和信封加密安全边界测试。 */
class AuthSecurityTests {

	@Test
	void keyRotationRequiresFortyEightHourRetentionAndProcessesVersionsAscending() {
		AuthHmacKey current = new AuthHmacKey(2, key((byte) 2));
		AuthHmacKey previous = new AuthHmacKey(1, key((byte) 1));
		AuthHmacKeyRing ring = new AuthHmacKeyRing(current, previous, Duration.ofHours(48));

		assertEquals(1, ring.keysInVersionOrder().get(0).version());
		assertEquals(2, ring.keysInVersionOrder().get(1).version());
		assertThrows(UnsupportedOperationException.class,
			() -> ring.keysInVersionOrder().add(current));
		assertThrows(AuthInfrastructureException.class,
			() -> new AuthHmacKeyRing(current, previous, Duration.ofHours(47)));
	}

	@Test
	void hmacUsesThirtyTwoBytesAndSeparatesPurposeAndDimension() {
		AuthHmacKey key = new AuthHmacKey(2, key((byte) 2));
		HmacSubjectHasher hasher = new HmacSubjectHasher(
			new AuthHmacKeyRing(key, null, Duration.ofHours(48)));
		byte[] subject = "same-subject".getBytes(StandardCharsets.UTF_8);

		byte[] registerIp = hasher.digest(EmailChallengePurpose.REGISTER,
			RateLimitDimension.IP, subject, key);
		byte[] resetIp = hasher.digest(EmailChallengePurpose.RESET_PASSWORD,
			RateLimitDimension.IP, subject, key);
		byte[] registerEmail = hasher.digest(EmailChallengePurpose.REGISTER,
			RateLimitDimension.EMAIL, subject, key);

		assertEquals(32, registerIp.length);
		assertFalse(Arrays.equals(registerIp, resetIp));
		assertFalse(Arrays.equals(registerIp, registerEmail));
	}

	@Test
	void codeHashAcceptsCurrentAndPreviousKeyButUsesConstantTimePath() {
		AuthHmacKey current = new AuthHmacKey(2, key((byte) 2));
		AuthHmacKey previous = new AuthHmacKey(1, key((byte) 1));
		HmacChallengeCodeHasher verifier = new HmacChallengeCodeHasher(
			new AuthHmacKeyRing(current, previous, Duration.ofHours(48)));
		HmacChallengeCodeHasher previousHasher = new HmacChallengeCodeHasher(
			new AuthHmacKeyRing(previous, null, Duration.ofHours(48)));

		String currentHash = verifier.hash(EmailChallengePurpose.REGISTER,
			"user@example.com", "123456");
		String previousHash = previousHasher.hash(EmailChallengePurpose.REGISTER,
			"user@example.com", "123456");

		assertTrue(verifier.matches(currentHash, EmailChallengePurpose.REGISTER,
			"user@example.com", "123456"));
		assertTrue(verifier.matches(previousHash, EmailChallengePurpose.REGISTER,
			"user@example.com", "123456"));
		assertFalse(verifier.matches(currentHash, EmailChallengePurpose.REGISTER,
			"user@example.com", "654321"));
		assertFalse(verifier.matches(currentHash, EmailChallengePurpose.RESET_PASSWORD,
			"user@example.com", "123456"));
	}

	@Test
	void missingDeviceUsesIpAndMarkerWithoutExposingMutableBytes() {
		SourceAddress address = SourceAddress.parseLiteral("192.0.2.10");
		AuthRateLimitSubjects missing = AuthRateLimitSubjects.of("user@example.com", null, address);
		AuthRateLimitSubjects present = AuthRateLimitSubjects.of("user@example.com", "device-1", address);
		byte[] first = missing.deviceBytes();
		byte[] second = missing.deviceBytes();
		first[0] = (byte) (first[0] ^ 1);

		assertArrayEquals(second, missing.deviceBytes());
		assertFalse(Arrays.equals(second, present.deviceBytes()));
		assertEquals(SourceAddress.parseLiteral("192.0.2.10"), address);
	}

	@Test
	void sourceResolverIgnoresUntrustedHeadersAndNormalizesTrustedIpv4Ipv6() throws Exception {
		SourceAddress trustedProxy = SourceAddress.parseLiteral("192.0.2.1");
		TrustedProxySourceAddressResolver resolver = new TrustedProxySourceAddressResolver(Set.of(trustedProxy));

		SourceAddress spoofed = resolver.resolve(
			InetAddress.getByName("198.51.100.1"),
			"for=203.0.113.10", null);
		SourceAddress trustedIpv4 = resolver.resolve(
			InetAddress.getByName("192.0.2.1"),
			null, "203.0.113.10, 198.51.100.2");
		SourceAddress trustedIpv6 = resolver.resolve(
			InetAddress.getByName("192.0.2.1"),
			"for=\"[2001:db8::10]:443\"", null);

		assertEquals(SourceAddress.parseLiteral("198.51.100.1"), spoofed);
		assertEquals(SourceAddress.parseLiteral("203.0.113.10"), trustedIpv4);
		assertEquals(SourceAddress.parseLiteral("2001:db8::10"), trustedIpv6);
		assertEquals(SourceAddress.parseLiteral("192.0.2.10"),
			SourceAddress.parseLiteral("::ffff:192.0.2.10"));
	}

	@Test
	void secureRandomGeneratorProducesSixDigits() {
		SecureRandomCodeGenerator generator = new SecureRandomCodeGenerator(new SecureRandom());
		for (int index = 0; index < 100; index++) {
			assertTrue(generator.generate().matches("[0-9]{6}"));
		}
	}

	@Test
	void argon2idPasswordHasherUsesRandomSaltAndDoesNotStorePlaintext() {
		PasswordHasher hasher = new Argon2idPasswordHasher();

		String first = hasher.hash("same-password");
		String second = hasher.hash("same-password");

		assertTrue(first.startsWith("$argon2id$"));
		assertTrue(second.startsWith("$argon2id$"));
		assertNotEquals(first, second);
		assertFalse(first.contains("same-password"));
		assertTrue(hasher.supports(1, first));
		assertTrue(hasher.matches("same-password", first));
		assertFalse(hasher.matches("other-password", first));
	}

	@Test
	void argon2idPasswordHasherSupportsOnlyCompleteCurrentFormat() {
		PasswordHasher hasher = new Argon2idPasswordHasher();
		String valid = hasher.hash("same-password");

		assertTrue(hasher.supports(1, valid));
		assertFalse(hasher.supports(1, "$argon2id$broken"));
		assertFalse(hasher.supports(2, valid));
		assertFalse(hasher.supports(1, valid.replace("$v=19$", "$v=16$")));
		assertFalse(hasher.supports(1, valid.replace("$m=16384,t=2,p=1$", "$m=8192,t=2,p=1$")));
		assertFalse(hasher.supports(1,
			"$argon2id$v=19$m=16384,t=2,p=1$%%%$" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32])));
		assertFalse(hasher.supports(1,
			"$argon2id$v=19$m=16384,t=2,p=1$" + Base64.getEncoder().withoutPadding().encodeToString(new byte[16]) + "$%%%"));
		assertFalse(hasher.supports(1, argon2idHash(new byte[15], new byte[32])));
		assertFalse(hasher.supports(1, argon2idHash(new byte[16], new byte[31])));
		assertFalse(hasher.supports(1, valid + "$extra"));
		String[] fields = valid.split("\\$", -1);
		assertFalse(hasher.supports(1,
			"$argon2id$m=16384,t=2,p=1$v=19$" + fields[4] + "$" + fields[5]));
		assertFalse(hasher.matches("same-password", "$argon2id$broken"));
	}

	@Test
	void envelopeUsesRandomDataKeyAndCanBeDecryptedWithTestKek() throws Exception {
		EnvelopeKey key = new EnvelopeKey(7, key((byte) 7));
		AesGcmEnvelopeEncryptor encryptor = new AesGcmEnvelopeEncryptor(key, new SecureRandom());
		UUID challengeId = UUID.randomUUID();
		EncryptedCodeEnvelope first = encryptor.encrypt(
			challengeId, EmailChallengePurpose.REGISTER, "123456");
		EncryptedCodeEnvelope second = encryptor.encrypt(
			challengeId, EmailChallengePurpose.REGISTER, "123456");

		assertEquals("A256GCM", first.algorithm());
		assertEquals(7, first.keyVersion());
		assertNotEquals(first.nonce(), second.nonce());
		assertNotEquals("123456", first.ciphertext());
		assertEquals("123456", new AesGcmEnvelopeDecryptor(key).decrypt(
			challengeId, EmailChallengePurpose.REGISTER, first));
	}

	@Test
	void keyAndSourceBytesAreDefensivelyCopied() {
		byte[] secret = key((byte) 3);
		AuthHmacKey hmacKey = new AuthHmacKey(3, secret);
		EnvelopeKey envelopeKey = new EnvelopeKey(3, secret);
		byte[] hmacCopy = hmacKey.secretCopy();
		byte[] envelopeCopy = envelopeKey.secretCopy();
		hmacCopy[0] = 9;
		envelopeCopy[0] = 9;

		assertEquals(3, hmacKey.secretCopy()[0]);
		assertEquals(3, envelopeKey.secretCopy()[0]);
	}

	@Test
	void bearerFilterDoesNotRewriteAuthenticatedDownstreamRuntimeException() {
		Instant now = Instant.parse("2026-08-15T00:00:00Z");
		UUID userId = UUID.randomUUID();
		UUID sessionId = UUID.randomUUID();
		VerifiedAccessToken verified = new VerifiedAccessToken(
			userId, sessionId, UUID.randomUUID(), now, now, now.plusSeconds(60), "test-key");
		AccessTokenAuthenticationFilter filter = new AccessTokenAuthenticationFilter(
			accessTokenService(verified),
			new DeviceSessionQueryService(currentSessionReadPort(), Clock.fixed(now, ZoneOffset.UTC)),
			Clock.fixed(now, ZoneOffset.UTC),
			new ObjectMapper());
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
		MockHttpServletResponse response = new MockHttpServletResponse();
		IllegalStateException downstream = new IllegalStateException("downstream failure");

		// 下游异常必须保持原类型向 MVC 传播，同时过滤器仍需清理认证上下文。
		IllegalStateException thrown = assertThrows(IllegalStateException.class,
			() -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
				throw downstream;
			}));

		assertSame(downstream, thrown);
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	private static AccessTokenService accessTokenService(VerifiedAccessToken verified) {
		return new AccessTokenService() {
			@Override
			public IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant sessionExpiresAt) {
				throw new UnsupportedOperationException();
			}

			@Override
			public VerifiedAccessToken verify(String encodedToken, Instant now) {
				return verified;
			}
		};
	}

	private static DeviceSessionReadPort currentSessionReadPort() {
		return new DeviceSessionReadPort() {
			@Override
			public boolean hasCurrentSession(UUID userId, UUID sessionId, Instant now) {
				return true;
			}

			@Override
			public Optional<Position> findPositionForUser(UUID userId, UUID sessionId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public List<Snapshot> listForUser(UUID userId, Position after, int maximumRecords) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static byte[] key(byte value) {
		byte[] key = new byte[32];
		Arrays.fill(key, value);
		return key;
	}

	private static String argon2idHash(byte[] salt, byte[] hash) {
		return "$argon2id$v=19$m=16384,t=2,p=1$"
			+ Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
			+ Base64.getEncoder().withoutPadding().encodeToString(hash);
	}
}
