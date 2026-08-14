package app.ziji.auth.application;

import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.auth.domain.SourceAddress;

/** 签发用例输入；不提供 toString，避免误记录邮箱和设备信号。 */
public final class EmailChallengeIssueCommand {

	private final EmailChallengePurpose purpose;
	private final String email;
	private final String deviceId;
	private final SourceAddress sourceAddress;

	public EmailChallengeIssueCommand(
		EmailChallengePurpose purpose,
		String email,
		String deviceId,
		SourceAddress sourceAddress) {
		this.purpose = purpose;
		this.email = email;
		this.deviceId = deviceId;
		this.sourceAddress = sourceAddress;
	}

	public EmailChallengePurpose purpose() {
		return purpose;
	}

	public String email() {
		return email;
	}

	public String deviceId() {
		return deviceId;
	}

	public SourceAddress sourceAddress() {
		return sourceAddress;
	}
}
