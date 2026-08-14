package app.ziji.auth.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 刷新凭据持久化摘要；固定版本和长度前缀域分离，不保存原始 Token。 */
public final class RefreshTokenHash {

	public static final String PREFIX = "v1:";
	private static final String DOMAIN = "ziji-session-refresh-token-hash-v1";

	private final String value;

	private RefreshTokenHash(String value) {
		this.value = value;
	}

	public static RefreshTokenHash from(RefreshToken token) {
		if (token == null) {
			throw new AuthDomainException("刷新凭据不能为空。");
		}
		try {
			byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
			byte[] raw = token.value().getBytes(StandardCharsets.UTF_8);
			ByteBuffer input = ByteBuffer.allocate(Integer.BYTES + domain.length + Integer.BYTES + raw.length)
				.putInt(domain.length).put(domain).putInt(raw.length).put(raw);
			String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.array()));
			return new RefreshTokenHash(PREFIX + digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 不可用。", exception);
		}
	}

	public static RefreshTokenHash restore(String value) {
		if (value == null || !value.matches("v1:[0-9a-f]{64}")) {
			throw new AuthDomainException("刷新凭据摘要格式无效。");
		}
		return new RefreshTokenHash(value);
	}

	public String value() {
		return value;
	}
}
