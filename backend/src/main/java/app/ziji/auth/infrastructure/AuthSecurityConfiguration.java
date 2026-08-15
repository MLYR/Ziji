package app.ziji.auth.infrastructure;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import app.ziji.auth.application.AuthRateLimitStore;
import app.ziji.auth.application.ChallengeCodeHasher;
import app.ziji.auth.application.DeviceSessionApplicationService;
import app.ziji.auth.application.DeviceSessionStore;
import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeOutbox;
import app.ziji.auth.application.EmailChallengeStore;
import app.ziji.auth.application.EmailRegistrationApplicationService;
import app.ziji.auth.application.EnvelopeEncryptor;
import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordLoginApplicationService;
import app.ziji.auth.application.PasswordManagementApplicationService;
import app.ziji.auth.application.SourceAddressResolver;
import app.ziji.auth.application.VerificationCodeGenerator;
import app.ziji.auth.domain.SourceAddress;
import app.ziji.shared.application.IdempotencyAnonymousSubjectHasher;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredentialLookupPort;
import app.ziji.user.application.UserPasswordManagementPort;
import app.ziji.user.application.UserRegistrationPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 认证密码学组件只从外部配置取密钥，测试 profile 使用明确隔离的测试值。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthSecurityProperties.class)
class AuthSecurityConfiguration {

	@Bean
	SecureRandom authSecureRandom() {
		return new SecureRandom();
	}

	@Bean
	AuthHmacKeyRing authHmacKeyRing(AuthSecurityProperties properties) {
		AuthSecurityProperties.HmacProperties hmac = properties.getHmac();
		AuthHmacKey current = new AuthHmacKey(
			hmac.getCurrentKeyVersion(), decode(hmac.getCurrentKeyBase64(), "HMAC"));
		AuthHmacKey previous = null;
		String previousKeyVersion = hmac.getPreviousKeyVersion();
		String previousKeyBase64 = hmac.getPreviousKeyBase64();
		boolean previousVersionConfigured = configured(previousKeyVersion);
		boolean previousKeyConfigured = configured(previousKeyBase64);
		if (previousVersionConfigured != previousKeyConfigured) {
			throw new AuthInfrastructureException("上一版本 HMAC 的版本和密钥必须同时配置。");
		}
		if (previousVersionConfigured) {
			previous = new AuthHmacKey(
				parseVersion(previousKeyVersion, "上一版本 HMAC"),
				decode(previousKeyBase64, "上一版本 HMAC"));
		}
		return new AuthHmacKeyRing(current, previous, hmac.getPreviousKeyRetention());
	}

	@Bean
	IdempotencyHmacKeyRing idempotencyHmacKeyRing(AuthSecurityProperties properties) {
		AuthSecurityProperties.IdempotencyProperties idempotency = properties.getIdempotency();
		AuthHmacKey current = new AuthHmacKey(
			idempotency.getCurrentKeyVersion(), decode(idempotency.getCurrentKeyBase64(), "幂等 HMAC"));
		AuthHmacKey previous = null;
		String previousKeyVersion = idempotency.getPreviousKeyVersion();
		String previousKeyBase64 = idempotency.getPreviousKeyBase64();
		boolean previousVersionConfigured = configured(previousKeyVersion);
		boolean previousKeyConfigured = configured(previousKeyBase64);
		if (previousVersionConfigured != previousKeyConfigured) {
			throw new AuthInfrastructureException("上一版本幂等 HMAC 的版本和密钥必须同时配置。");
		}
		if (previousVersionConfigured) {
			previous = new AuthHmacKey(
				parseVersion(previousKeyVersion, "上一版本幂等 HMAC"),
				decode(previousKeyBase64, "上一版本幂等 HMAC"));
		}
		return new IdempotencyHmacKeyRing(current, previous, idempotency.getPreviousKeyRetention());
	}

	@Bean
	IdempotencyAnonymousSubjectHasher idempotencyAnonymousSubjectHasher(IdempotencyHmacKeyRing keyRing) {
		return new HmacIdempotencyAnonymousSubjectHasher(keyRing);
	}

	@Bean
	HmacSubjectHasher hmacSubjectHasher(AuthHmacKeyRing keyRing) {
		return new HmacSubjectHasher(keyRing);
	}

	@Bean
	HmacChallengeCodeHasher hmacChallengeCodeHasher(AuthHmacKeyRing keyRing) {
		return new HmacChallengeCodeHasher(keyRing);
	}

	@Bean
	EnvelopeKey envelopeKey(AuthSecurityProperties properties) {
		AuthSecurityProperties.EnvelopeProperties envelope = properties.getEnvelope();
		return new EnvelopeKey(envelope.getKekVersion(), decode(envelope.getKekBase64(), "outbox KEK"));
	}

	@Bean
	AesGcmEnvelopeEncryptor aesGcmEnvelopeEncryptor(EnvelopeKey key, SecureRandom random) {
		return new AesGcmEnvelopeEncryptor(key, random);
	}

	@Bean
	AccessTokenKeyRing accessTokenKeyRing(AuthSecurityProperties properties) {
		// RSA 私钥、公钥、kid 与轮换保留期在启动时一次性校验，错误配置必须 fail closed。
		return AccessTokenKeyRing.from(properties.getAccessToken());
	}

	@Bean
	SourceAddressResolver sourceAddressResolver(AuthSecurityProperties properties) {
		Set<SourceAddress> trusted = properties.getTrustedProxyAddresses().stream()
			.filter(address -> address != null && !address.isBlank())
			.map(address -> SourceAddress.parseLiteral(address.trim()))
			.collect(Collectors.toUnmodifiableSet());
		return new TrustedProxySourceAddressResolver(trusted);
	}

	@Bean
	EmailChallengeApplicationService emailChallengeApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeStore challengeStore,
		AuthRateLimitStore rateLimitStore,
		VerificationCodeGenerator codeGenerator,
		ChallengeCodeHasher codeHasher,
		EnvelopeEncryptor envelopeEncryptor,
		EmailChallengeOutbox outbox,
		Clock clock) {
		// Spring 装配留在 infrastructure，application 用例保持对框架无依赖。
		return new EmailChallengeApplicationService(
			transactionRunner, challengeStore, rateLimitStore, codeGenerator, codeHasher, envelopeEncryptor, outbox, clock);
	}

	@Bean
	EmailRegistrationApplicationService emailRegistrationApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeApplicationService challengeService,
		PasswordHasher passwordHasher,
		UserRegistrationPort userRegistrationPort,
		Clock clock) {
		// Spring 装配留在 infrastructure，application 用例保持对框架无依赖。
		return new EmailRegistrationApplicationService(
			transactionRunner, challengeService, passwordHasher, userRegistrationPort, clock);
	}

	@Bean
	PasswordLoginApplicationService passwordLoginApplicationService(
		TransactionRunner transactionRunner,
		AuthRateLimitStore rateLimitStore,
		UserCredentialLookupPort credentialLookupPort,
		PasswordHasher passwordHasher,
		DeviceSessionApplicationService deviceSessionService) {
		// Spring 装配留在 infrastructure，application 用例保持对框架无依赖。
		return new PasswordLoginApplicationService(
			transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher, deviceSessionService);
	}

	@Bean
	DeviceSessionApplicationService deviceSessionApplicationService(
		TransactionRunner transactionRunner,
		DeviceSessionStore sessionStore,
		app.ziji.auth.application.AccessTokenService accessTokenService,
		SecureRandom secureRandom,
		Clock clock) {
		// Spring 装配留在 infrastructure，application 用例保持对框架无依赖。
		return new DeviceSessionApplicationService(
			transactionRunner, sessionStore, accessTokenService, secureRandom, clock);
	}

	@Bean
	PasswordManagementApplicationService passwordManagementApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeApplicationService challengeService,
		PasswordHasher passwordHasher,
		UserPasswordManagementPort userPasswordPort,
		DeviceSessionApplicationService deviceSessionService,
		Clock clock) {
		// Spring 装配留在 infrastructure，密码事务顺序和安全语义保持在 application。
		return new PasswordManagementApplicationService(
			transactionRunner, challengeService, passwordHasher, userPasswordPort, deviceSessionService, clock);
	}

	private static boolean configured(String value) {
		return value != null && !value.isBlank();
	}

	private static int parseVersion(String value, String keyName) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			throw new AuthInfrastructureException(keyName + "密钥版本配置无效。", exception);
		}
	}

	private static byte[] decode(String value, String keyName) {
		if (value == null || value.isBlank()) {
			throw new AuthInfrastructureException(keyName + "密钥未配置。");
		}
		try {
			return Base64.getDecoder().decode(value);
		} catch (IllegalArgumentException exception) {
			throw new AuthInfrastructureException(keyName + "密钥配置无效。", exception);
		}
	}
}
