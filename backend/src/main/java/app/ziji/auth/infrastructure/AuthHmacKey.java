package app.ziji.auth.infrastructure;

import java.util.Arrays;

/** 外部注入的 HMAC 密钥；任何 accessor 都返回副本，避免可变字节数组外泄。 */
public final class AuthHmacKey {

	private final int version;
	private final byte[] secret;

	public AuthHmacKey(int version, byte[] secret) {
		if (version <= 0 || secret == null || secret.length < 32) {
			throw new AuthInfrastructureException("认证密钥配置无效。");
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

	boolean sameVersion(AuthHmacKey other) {
		return other != null && version == other.version;
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof AuthHmacKey key
			&& version == key.version
			&& Arrays.equals(secret, key.secret);
	}

	@Override
	public int hashCode() {
		return 31 * version + Arrays.hashCode(secret);
	}
}
