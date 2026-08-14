package app.ziji.auth.infrastructure;

/** 外部注入的 AES-256 KEK；不提供原始密钥字符串或可变数组。 */
public final class EnvelopeKey {

	private final int version;
	private final byte[] secret;

	public EnvelopeKey(int version, byte[] secret) {
		if (version <= 0 || secret == null || secret.length != 32) {
			throw new AuthInfrastructureException("outbox 信封密钥配置无效。");
		}
		this.version = version;
		this.secret = secret.clone();
	}

	public int version() {
		return version;
	}

	public byte[] secretCopy() {
		return secret.clone();
	}
}
