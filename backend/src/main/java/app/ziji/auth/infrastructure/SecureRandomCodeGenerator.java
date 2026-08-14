package app.ziji.auth.infrastructure;

import java.security.SecureRandom;

import app.ziji.auth.application.VerificationCodeGenerator;
import org.springframework.stereotype.Component;

/** 生成包含前导零的六位数字验证码；不使用时间、Random 或可预测序列。 */
@Component
public final class SecureRandomCodeGenerator implements VerificationCodeGenerator {

	private static final int CODE_BOUND = 1_000_000;

	private final SecureRandom random;

	public SecureRandomCodeGenerator(SecureRandom random) {
		this.random = random;
	}

	@Override
	public String generate() {
		return "%06d".formatted(random.nextInt(CODE_BOUND));
	}
}
