package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 已完成载荷校验和解密的验证码邮件；验证码只在内存中流向投递端口。 */
public record EmailChallengeEmail(
	UUID eventId,
	UUID challengeId,
	EmailChallengePurpose purpose,
	String email,
	String verificationCode,
	Instant expiresAt) {

	public EmailChallengeEmail {
		if (eventId == null || challengeId == null || purpose == null || email == null || email.isBlank()
			|| verificationCode == null || !verificationCode.matches("[0-9]{6}") || expiresAt == null) {
			throw new IllegalArgumentException("验证码邮件载荷无效。");
		}
	}
}
