package app.ziji.auth.application;

import java.util.UUID;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 邮件消费者使用的验证码信封解密端口；解密失败不得暴露密文或密钥细节。 */
public interface EnvelopeDecryptor {

	String decrypt(UUID challengeId, EmailChallengePurpose purpose, EncryptedCodeEnvelope envelope);
}
