package app.ziji.auth.application;

import app.ziji.auth.domain.EmailChallengePurpose;

/** 验证用例输入；验证码明文只在调用栈短暂存在，不提供可记录的字符串表示。 */
public final class EmailChallengeVerificationCommand {

	private final EmailChallengePurpose purpose;
	private final String email;
	private final String verificationCode;

	public EmailChallengeVerificationCommand(
		EmailChallengePurpose purpose,
		String email,
		String verificationCode) {
		this.purpose = purpose;
		this.email = email;
		this.verificationCode = verificationCode;
	}

	public EmailChallengePurpose purpose() {
		return purpose;
	}

	public String email() {
		return email;
	}

	public String verificationCode() {
		return verificationCode;
	}
}
