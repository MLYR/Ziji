package app.ziji.auth.application;

import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 验证码 outbox 信封加密端口；KEK 只由 infrastructure 外部配置提供。 */
public interface EnvelopeEncryptor {

	EncryptedCodeEnvelope encrypt(UUID challengeId, EmailChallengePurpose purpose, String code);
}
