package app.ziji.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallenge;
import app.ziji.auth.domain.EmailChallengeStatus;
import app.ziji.shared.application.TransactionRunner;

/**
 * 邮箱验证码签发与消费用例；所有数据库事实写入都包在一个 REQUIRED 事务中。
 */
public final class EmailChallengeApplicationService {

	private static final int EXPIRES_IN_SECONDS = (int) EmailChallenge.VALIDITY.toSeconds();

	private final TransactionRunner transactionRunner;
	private final EmailChallengeStore challengeStore;
	private final AuthRateLimitStore rateLimitStore;
	private final VerificationCodeGenerator codeGenerator;
	private final ChallengeCodeHasher codeHasher;
	private final EnvelopeEncryptor envelopeEncryptor;
	private final EmailChallengeOutbox outbox;
	private final Clock clock;
	private final Supplier<UUID> uuidGenerator;

	public EmailChallengeApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeStore challengeStore,
		AuthRateLimitStore rateLimitStore,
		VerificationCodeGenerator codeGenerator,
		ChallengeCodeHasher codeHasher,
		EnvelopeEncryptor envelopeEncryptor,
		EmailChallengeOutbox outbox,
		Clock clock) {
		this(transactionRunner, challengeStore, rateLimitStore, codeGenerator, codeHasher,
			envelopeEncryptor, outbox, clock, UUID::randomUUID);
	}

	public EmailChallengeApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeStore challengeStore,
		AuthRateLimitStore rateLimitStore,
		VerificationCodeGenerator codeGenerator,
		ChallengeCodeHasher codeHasher,
		EnvelopeEncryptor envelopeEncryptor,
		EmailChallengeOutbox outbox,
		Clock clock,
		Supplier<UUID> uuidGenerator) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.challengeStore = require(challengeStore, "挑战存储");
		this.rateLimitStore = require(rateLimitStore, "限流存储");
		this.codeGenerator = require(codeGenerator, "验证码生成器");
		this.codeHasher = require(codeHasher, "验证码 Hash 服务");
		this.envelopeEncryptor = require(envelopeEncryptor, "信封加密服务");
		this.outbox = require(outbox, "outbox 服务");
		this.clock = require(clock, "时钟");
		this.uuidGenerator = require(uuidGenerator, "UUID 生成器");
	}

	public EmailChallengeIssueResult issue(EmailChallengeIssueCommand command) {
		if (command == null || command.purpose() == null || command.sourceAddress() == null) {
			throw new AuthDomainException("验证码签发请求无效。");
		}
		EmailAddress email = EmailAddress.normalize(command.email());
		String deviceId = normalizeDeviceId(command.deviceId());
		AuthRateLimitSubjects subjects = AuthRateLimitSubjects.of(
			email.value(), deviceId, command.sourceAddress());

		return transactionRunner.required(() -> {
			Instant now = clock.instant();
			RateLimitDecision decision = rateLimitStore.consume(command.purpose(), subjects, now);
			if (!decision.allowed()) {
				// 事务正常返回以提交被拒绝请求的全部桶计数，不能用异常回滚。
				return EmailChallengeIssueResult.rateLimited(decision.retryAfterSeconds());
			}

			String code = codeGenerator.generate();
			if (code == null || !code.matches("[0-9]{6}")) {
				throw new AuthDomainException("验证码生成失败。");
			}
			UUID challengeId = uuidGenerator.get();
			String codeHash = codeHasher.hash(command.purpose(), email.value(), code);
			EmailChallenge challenge = EmailChallenge.issue(
				challengeId, command.purpose(), email.value(), codeHash, now);

			// 旧挑战、事实挑战和加密投递事件必须在同一事务内原子可见。
			challengeStore.replaceActive(email.value(), command.purpose(), now);
			challengeStore.insert(challenge);
			EncryptedCodeEnvelope encryptedCode = envelopeEncryptor.encrypt(
				challenge.id(), challenge.purpose(), code);
			outbox.append(new EmailChallengeIssuedEvent(
				uuidGenerator.get(), challenge.id(), challenge.purpose(), challenge.emailNormalized(),
				challenge.expiresAt(), encryptedCode, now));
			return EmailChallengeIssueResult.accepted(EXPIRES_IN_SECONDS);
		});
	}

	public EmailChallengeVerificationResult verify(EmailChallengeVerificationCommand command) {
		if (command == null || command.purpose() == null) {
			return EmailChallengeVerificationResult.INVALID;
		}
		EmailAddress email;
		try {
			email = EmailAddress.normalize(command.email());
		} catch (AuthDomainException exception) {
			return EmailChallengeVerificationResult.INVALID;
		}

		return transactionRunner.required(() -> {
			Instant now = clock.instant();
			EmailChallenge challenge = challengeStore
				.findLatestForUpdate(email.value(), command.purpose())
				.orElse(null);
			if (challenge == null || challenge.status() != EmailChallengeStatus.ACTIVE) {
				return EmailChallengeVerificationResult.INVALID;
			}
			if (!challenge.canConsumeAt(now)) {
				if (now.compareTo(challenge.expiresAt()) >= 0) {
					challengeStore.markExpired(challenge.id(), now);
				}
				return EmailChallengeVerificationResult.INVALID;
			}
			if (!codeHasher.matches(challenge.codeHash(), command.purpose(), email.value(),
				command.verificationCode())) {
				challengeStore.recordFailedAttempt(challenge.id(), now);
				return EmailChallengeVerificationResult.INVALID;
			}
			return challengeStore.consume(challenge.id(), now)
				? EmailChallengeVerificationResult.VALID
				: EmailChallengeVerificationResult.INVALID;
		});
	}

	private static String normalizeDeviceId(String deviceId) {
		if (deviceId == null) {
			return null;
		}
		String normalized = java.text.Normalizer.normalize(deviceId, java.text.Normalizer.Form.NFKC);
		if (normalized.isBlank() || normalized.length() > 200) {
			throw new AuthDomainException("设备标识格式无效。");
		}
		return normalized;
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}
}
