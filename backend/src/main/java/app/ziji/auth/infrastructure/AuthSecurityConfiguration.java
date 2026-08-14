package app.ziji.auth.infrastructure;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import app.ziji.auth.application.SourceAddressResolver;
import app.ziji.auth.domain.SourceAddress;
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
	SourceAddressResolver sourceAddressResolver(AuthSecurityProperties properties) {
		Set<SourceAddress> trusted = properties.getTrustedProxyAddresses().stream()
			.filter(address -> address != null && !address.isBlank())
			.map(address -> SourceAddress.parseLiteral(address.trim()))
			.collect(Collectors.toUnmodifiableSet());
		return new TrustedProxySourceAddressResolver(trusted);
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
