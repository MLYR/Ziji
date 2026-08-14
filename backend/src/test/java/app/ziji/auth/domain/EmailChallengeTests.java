package app.ziji.auth.domain;

import java.time.Instant;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EmailChallenge 状态机、十分钟有效期和五次错误上限的领域测试。 */
class EmailChallengeTests {

	private static final Instant CREATED_AT = Instant.parse("2026-08-14T00:00:00Z");

	@Test
	void supportsOnlyRegisterAndResetPasswordPurposes() {
		assertEquals(EnumSet.of(EmailChallengePurpose.REGISTER, EmailChallengePurpose.RESET_PASSWORD),
			EnumSet.allOf(EmailChallengePurpose.class));
	}

	@Test
	void expiresAtTenMinutesAndExactBoundaryCannotConsume() {
		EmailChallenge challenge = challenge();

		assertTrue(challenge.canConsumeAt(CREATED_AT.plusSeconds(599)));
		assertFalse(challenge.canConsumeAt(CREATED_AT.plusSeconds(600)));
		assertEquals(EmailChallengeStatus.EXPIRED,
			challenge.expireAt(CREATED_AT.plusSeconds(600)).status());
	}

	@Test
	void fifthWrongAttemptInvalidatesChallenge() {
		EmailChallenge challenge = challenge();
		for (int attempt = 1; attempt < EmailChallenge.MAX_ATTEMPTS; attempt++) {
			challenge = challenge.recordFailedAttemptAt(CREATED_AT.plusSeconds(attempt));
			assertEquals(attempt, challenge.attemptCount());
			assertEquals(EmailChallengeStatus.ACTIVE, challenge.status());
		}

		challenge = challenge.recordFailedAttemptAt(CREATED_AT.plusSeconds(5));
		assertEquals(EmailChallenge.MAX_ATTEMPTS, challenge.attemptCount());
		assertEquals(EmailChallengeStatus.MAX_ATTEMPTS, challenge.status());
		EmailChallenge finalChallenge = challenge;
		assertThrows(AuthDomainException.class,
			() -> finalChallenge.recordFailedAttemptAt(CREATED_AT.plusSeconds(6)));
	}

	@Test
	void correctConsumptionIsOneTimeAndMutuallyExclusiveWithInvalidation() {
		EmailChallenge challenge = challenge();
		EmailChallenge consumed = challenge.consumeAt(CREATED_AT.plusSeconds(1));

		assertEquals(EmailChallengeStatus.CONSUMED, consumed.status());
		assertEquals(CREATED_AT.plusSeconds(1), consumed.consumedAt());
		assertThrows(AuthDomainException.class,
			() -> consumed.consumeAt(CREATED_AT.plusSeconds(2)));
		assertThrows(AuthDomainException.class,
			() -> consumed.replaceAt(CREATED_AT.plusSeconds(2)));
	}

	@Test
	void replacementAndSecurityRevocationUseExplicitReasons() {
		assertEquals(EmailChallengeStatus.REPLACED,
			challenge().replaceAt(CREATED_AT.plusSeconds(1)).status());
		assertEquals(EmailChallengeStatus.SECURITY_REVOKED,
			challenge().securityRevokeAt(CREATED_AT.plusSeconds(1)).status());
	}

	private static EmailChallenge challenge() {
		return EmailChallenge.issue(
			java.util.UUID.randomUUID(), EmailChallengePurpose.REGISTER,
			"user@example.com", "v1:2:opaque-hash", CREATED_AT);
	}
}
