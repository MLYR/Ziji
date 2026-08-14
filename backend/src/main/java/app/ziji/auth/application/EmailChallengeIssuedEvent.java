package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 邮件投递 outbox 事件；验证码字段仅允许以加密信封形式存在。 */
public final class EmailChallengeIssuedEvent {

	private final UUID eventId;
	private final UUID challengeId;
	private final EmailChallengePurpose purpose;
	private final String normalizedEmail;
	private final Instant expiresAt;
	private final EncryptedCodeEnvelope encryptedCode;
	private final Instant occurredAt;

	public EmailChallengeIssuedEvent(
		UUID eventId,
		UUID challengeId,
		EmailChallengePurpose purpose,
		String normalizedEmail,
		Instant expiresAt,
		EncryptedCodeEnvelope encryptedCode,
		Instant occurredAt) {
		this.eventId = eventId;
		this.challengeId = challengeId;
		this.purpose = purpose;
		this.normalizedEmail = normalizedEmail;
		this.expiresAt = expiresAt;
		this.encryptedCode = encryptedCode;
		this.occurredAt = occurredAt;
	}

	public UUID eventId() {
		return eventId;
	}

	public UUID challengeId() {
		return challengeId;
	}

	public EmailChallengePurpose purpose() {
		return purpose;
	}

	public String normalizedEmail() {
		return normalizedEmail;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public EncryptedCodeEnvelope encryptedCode() {
		return encryptedCode;
	}

	public Instant occurredAt() {
		return occurredAt;
	}
}
