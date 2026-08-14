package app.ziji.auth.application;

/** 验证码生成端口，具体实现必须使用密码学安全随机源。 */
@FunctionalInterface
public interface VerificationCodeGenerator {

	String generate();
}
