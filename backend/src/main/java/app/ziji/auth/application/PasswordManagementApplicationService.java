package app.ziji.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialStatus;
import app.ziji.user.application.UserPasswordManagementPort;

/**
 * 密码重置与已认证改密用例；不包含 HTTP、Cookie、CSRF、Mobile 编排或会话签发。
 */
public final class PasswordManagementApplicationService {

	private static final Set<UserCredentialStatus> CHANGEABLE_STATUSES =
		Set.of(UserCredentialStatus.ACTIVE, UserCredentialStatus.CLOSING);

	private final TransactionRunner transactionRunner;
	private final EmailChallengeApplicationService challengeService;
	private final PasswordHasher passwordHasher;
	private final UserPasswordManagementPort userPasswordPort;
	private final DeviceSessionApplicationService deviceSessionService;
	private final Clock clock;

	public PasswordManagementApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeApplicationService challengeService,
		PasswordHasher passwordHasher,
		UserPasswordManagementPort userPasswordPort,
		DeviceSessionApplicationService deviceSessionService,
		Clock clock) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.challengeService = require(challengeService, "验证码服务");
		this.passwordHasher = require(passwordHasher, "密码 Hash 服务");
		this.userPasswordPort = require(userPasswordPort, "用户密码端口");
		this.deviceSessionService = require(deviceSessionService, "设备会话服务");
		this.clock = require(clock, "时钟");
	}

	public void resetPassword(PasswordResetCommand command) {
		ResetDetails details = validateReset(command);
		boolean valid = transactionRunner.required(() -> {
			// 无效挑战正常返回，让错误次数/过期状态提交后再抛统一校验异常。
			if (challengeService.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.RESET_PASSWORD, details.emailNormalized(), details.verificationCode()))
				!= EmailChallengeVerificationResult.VALID) {
				return false;
			}

			// 只有已消费的有效 RESET_PASSWORD 挑战才允许恰好生成一次新 Hash。
			String passwordHash = passwordHasher.hash(details.newPassword());
			// reset 与登录统一先锁 users，再锁 user_sessions 和 session_refresh_tokens；未知邮箱仍保持安全成功。
			Optional<UserCredential> credential = userPasswordPort.findByNormalizedEmailForUpdate(details.emailNormalized());
			if (credential.isPresent()) {
				UUID userId = credential.get().userId();
				userPasswordPort.updatePasswordForUser(userId, passwordHash, clock.instant());
				deviceSessionService.revokeAllDevicesForPasswordReset(userId);
			}
			return true;
		});
		if (!valid) {
			throw new PasswordManagementValidationException();
		}
	}

	public void changePassword(AuthenticatedPasswordChangeCommand command) {
		ChangeDetails details = validateChange(command);
		transactionRunner.required(() -> {
			UserCredential credential = userPasswordPort.findByUserIdForUpdate(details.userId())
				.orElseThrow(InvalidCredentialsException::new);
			if (!CHANGEABLE_STATUSES.contains(credential.status())
				|| !passwordHasher.supports(credential.passwordHashVersion(), credential.passwordHash())) {
				throw new InvalidCredentialsException();
			}

			boolean matches;
			try {
				// 当前凭据只允许一次 matches；损坏 Hash 已在 supports 阶段拒绝，不进入 Argon2。
				matches = passwordHasher.matches(details.currentPassword(), credential.passwordHash());
			} catch (PasswordHashingException exception) {
				throw new InvalidCredentialsException();
			}
			if (!matches) {
				throw new InvalidCredentialsException();
			}

			// 当前密码正确后才生成新 Hash；改密不撤销任何设备会话。
			String passwordHash = passwordHasher.hash(details.newPassword());
			userPasswordPort.updatePasswordForUser(details.userId(), passwordHash, clock.instant());
		});
	}

	private static ResetDetails validateReset(PasswordResetCommand command) {
		if (command == null || command.verificationCode() == null
			|| !command.verificationCode().matches("[0-9]{6}")
			|| command.newPassword() == null
			|| command.newPassword().length() < 10 || command.newPassword().length() > 128) {
			throw new PasswordManagementValidationException();
		}
		try {
			return new ResetDetails(
				EmailAddress.normalize(command.email()).value(), command.verificationCode(), command.newPassword());
		} catch (AuthDomainException exception) {
			throw new PasswordManagementValidationException();
		}
	}

	private static ChangeDetails validateChange(AuthenticatedPasswordChangeCommand command) {
		if (command == null || command.userId() == null
			|| command.currentPassword() == null
			|| command.currentPassword().length() < 1 || command.currentPassword().length() > 128
			|| command.newPassword() == null
			|| command.newPassword().length() < 10 || command.newPassword().length() > 128) {
			throw new PasswordManagementValidationException();
		}
		return new ChangeDetails(command.userId(), command.currentPassword(), command.newPassword());
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}

	private record ResetDetails(String emailNormalized, String verificationCode, String newPassword) {
	}

	private record ChangeDetails(UUID userId, String currentPassword, String newPassword) {
	}
}
