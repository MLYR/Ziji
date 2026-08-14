package app.ziji.auth.infrastructure;

import java.util.Base64;

import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordHashingException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** 使用 Spring Security 的 Argon2id 默认参数编码密码；盐由编码器每次安全随机生成。 */
@Component
public final class Argon2idPasswordHasher implements PasswordHasher {

	private static final int SUPPORTED_HASH_VERSION = 1;
	private static final String ALGORITHM = "argon2id";
	private static final String ARGON2_VERSION = "v=19";
	private static final String PARAMETERS = "m=16384,t=2,p=1";
	private static final int SALT_BYTES = 16;
	private static final int HASH_BYTES = 32;

	private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	@Override
	public String hash(String password) {
		if (password == null) {
			throw new PasswordHashingException(new IllegalArgumentException("密码不能为空。"));
		}
		try {
			String encoded = encoder.encode(password);
			if (!supports(SUPPORTED_HASH_VERSION, encoded)) {
				throw new PasswordHashingException(new IllegalStateException("Argon2id 编码失败。"));
			}
			return encoded;
		} catch (PasswordHashingException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new PasswordHashingException(exception);
		}
	}

	@Override
	public boolean supports(int hashVersion, String encodedHash) {
		if (hashVersion != SUPPORTED_HASH_VERSION || encodedHash == null) {
			return false;
		}
		// 严格解析完整 PHC 格式，先拒绝损坏值，避免将其交给编码器产生快速失败或日志差异。
		String[] fields = encodedHash.split("\\$", -1);
		return fields.length == 6
			&& fields[0].isEmpty()
			&& ALGORITHM.equals(fields[1])
			&& ARGON2_VERSION.equals(fields[2])
			&& PARAMETERS.equals(fields[3])
			&& hasDecodedLength(fields[4], SALT_BYTES)
			&& hasDecodedLength(fields[5], HASH_BYTES);
	}

	private static boolean hasDecodedLength(String value, int expectedLength) {
		if (value == null || value.isEmpty() || value.indexOf('=') >= 0) {
			return false;
		}
		try {
			return Base64.getDecoder().decode(value).length == expectedLength;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	public boolean matches(String password, String encodedHash) {
		if (password == null || !supports(SUPPORTED_HASH_VERSION, encodedHash)) {
			return false;
		}
		try {
			return encoder.matches(password, encodedHash);
		} catch (RuntimeException exception) {
			throw new PasswordHashingException(exception);
		}
	}
}
