package app.ziji.auth.infrastructure;

import java.time.Duration;
import java.util.Arrays;

import app.ziji.shared.application.IdempotencySubject;
import app.ziji.shared.application.IdempotencyValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 匿名幂等主体使用专用 HMAC、认证邮箱规范化和 7 天上一版本保留期。 */
class HmacIdempotencyAnonymousSubjectHasherTests {

	@Test
	void normalizedEmailUsesCurrentAndPreviousVersionWithoutExposingDigestOrEmail() {
		HmacIdempotencyAnonymousSubjectHasher hasher = new HmacIdempotencyAnonymousSubjectHasher(ring());

		IdempotencySubject.Anonymous first = hasher.forEmail("  ＵＳＥＲ@EXAMPLE.TEST ");
		IdempotencySubject.Anonymous second = hasher.forEmail("user@example.test");

		assertEquals(2, first.current().keyVersion());
		assertEquals(2, first.lookupCandidatesInVersionOrder().size());
		assertEquals(first.current(), second.current());
		assertFalse(first.current().toString().contains("user@example.test"));
		assertFalse(first.current().toString().contains(Arrays.toString(first.current().valueCopy())));
		assertNotEquals(first.current(), first.lookupCandidatesInVersionOrder().getFirst());
	}

	@Test
	void rejectsInvalidEmailAndUnsafeKeyRotationConfiguration() {
		HmacIdempotencyAnonymousSubjectHasher hasher = new HmacIdempotencyAnonymousSubjectHasher(ring());
		assertThrows(IdempotencyValidationException.class, () -> hasher.forEmail("not-an-email"));
		assertThrows(AuthInfrastructureException.class, () -> new IdempotencyHmacKeyRing(
			new AuthHmacKey(2, key((byte) 2)), new AuthHmacKey(1, key((byte) 1)), Duration.ofDays(6)));
		assertThrows(AuthInfrastructureException.class, () -> new IdempotencyHmacKeyRing(
			new AuthHmacKey(2, key((byte) 2)), new AuthHmacKey(2, key((byte) 1)), Duration.ofDays(7)));
		assertThrows(AuthInfrastructureException.class, () -> new IdempotencyHmacKeyRing(
			new AuthHmacKey(Short.MAX_VALUE + 1, key((byte) 2)), null, Duration.ofDays(7)));
		assertThrows(IdempotencyValidationException.class, () -> new IdempotencySubject.AnonymousDigest(
			Short.MAX_VALUE + 1, key((byte) 2)));
	}

	@Test
	void configurationRequiresPairedPreviousValuesAndDoesNotInventAnEmptyPreviousKey() {
		AuthSecurityConfiguration configuration = new AuthSecurityConfiguration();
		AuthSecurityProperties properties = new AuthSecurityProperties();
		AuthSecurityProperties.IdempotencyProperties idempotency = properties.getIdempotency();
		idempotency.setCurrentKeyVersion(2);
		idempotency.setCurrentKeyBase64(java.util.Base64.getEncoder().encodeToString(key((byte) 2)));
		idempotency.setPreviousKeyVersion(" ");
		idempotency.setPreviousKeyBase64("");

		assertNull(configuration.idempotencyHmacKeyRing(properties).previous());
		idempotency.setPreviousKeyBase64(java.util.Base64.getEncoder().encodeToString(key((byte) 1)));
		assertThrows(AuthInfrastructureException.class, () -> configuration.idempotencyHmacKeyRing(properties));
	}

	private static IdempotencyHmacKeyRing ring() {
		return new IdempotencyHmacKeyRing(
			new AuthHmacKey(2, key((byte) 2)), new AuthHmacKey(1, key((byte) 1)), Duration.ofDays(7));
	}

	private static byte[] key(byte value) {
		byte[] key = new byte[32];
		Arrays.fill(key, value);
		return key;
	}
}
