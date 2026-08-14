package app.ziji.auth.infrastructure;

import app.ziji.auth.application.PasswordHasher;
import app.ziji.auth.application.PasswordHashingException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** 使用 Spring Security 的 Argon2id 默认参数编码密码；盐由编码器每次安全随机生成。 */
@Component
public final class Argon2idPasswordHasher implements PasswordHasher {

	private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	@Override
	public String hash(String password) {
		if (password == null) {
			throw new PasswordHashingException(new IllegalArgumentException("密码不能为空。"));
		}
		try {
			String encoded = encoder.encode(password);
			if (encoded == null || !encoded.startsWith("$argon2id$")) {
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
	public boolean matches(String password, String encodedHash) {
		if (password == null || encodedHash == null || !encodedHash.startsWith("$argon2id$")) {
			return false;
		}
		try {
			return encoder.matches(password, encodedHash);
		} catch (RuntimeException exception) {
			throw new PasswordHashingException(exception);
		}
	}
}
