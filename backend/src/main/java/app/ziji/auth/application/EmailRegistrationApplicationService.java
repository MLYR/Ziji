package app.ziji.auth.application;

import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserEmailAlreadyExistsException;
import app.ziji.user.application.UserRegistrationCommand;
import app.ziji.user.application.UserRegistrationPort;

/** 邮箱注册用例；验证码消费与 users 写入必须加入同一个最外层 REQUIRED 事务。 */
public final class EmailRegistrationApplicationService {

	private static final Set<String> BASE_CURRENCIES = Set.of("CNY", "USD", "HKD", "JPY", "EUR");

	private final TransactionRunner transactionRunner;
	private final EmailChallengeApplicationService challengeService;
	private final PasswordHasher passwordHasher;
	private final UserRegistrationPort userRegistrationPort;
	private final Clock clock;
	private final Supplier<UUID> uuidGenerator;

	public EmailRegistrationApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeApplicationService challengeService,
		PasswordHasher passwordHasher,
		UserRegistrationPort userRegistrationPort,
		Clock clock) {
		this(transactionRunner, challengeService, passwordHasher, userRegistrationPort, clock, UUID::randomUUID);
	}

	public EmailRegistrationApplicationService(
		TransactionRunner transactionRunner,
		EmailChallengeApplicationService challengeService,
		PasswordHasher passwordHasher,
		UserRegistrationPort userRegistrationPort,
		Clock clock,
		Supplier<UUID> uuidGenerator) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.challengeService = require(challengeService, "验证码服务");
		this.passwordHasher = require(passwordHasher, "密码 Hash 服务");
		this.userRegistrationPort = require(userRegistrationPort, "用户注册端口");
		this.clock = require(clock, "时钟");
		this.uuidGenerator = require(uuidGenerator, "UUID 生成器");
	}

	public EmailRegistrationResult register(EmailRegistrationCommand command) {
		RegistrationDetails details = validate(command);

		Optional<EmailRegistrationResult> result = transactionRunner.required(() -> {
			// 验证码服务的 REQUIRED 调用加入当前最外层事务；用户写入失败会一并回滚消费。
			if (challengeService.verify(new EmailChallengeVerificationCommand(
				EmailChallengePurpose.REGISTER, details.displayEmail(), command.verificationCode()))
				!= EmailChallengeVerificationResult.VALID) {
				return Optional.empty();
			}
			try {
				// 仅已消费的有效挑战才触发昂贵 Hash；Hash 或写入失败都由外层事务回滚消费。
				String passwordHash = passwordHasher.hash(command.password());
				UUID userId = uuidGenerator.get();
				Instant now = clock.instant();
				userRegistrationPort.register(new UserRegistrationCommand(
					userId, details.displayEmail(), details.emailNormalized(), passwordHash,
					details.nickname(), details.timezone(), details.baseCurrency(), details.locale(), now));
				return Optional.of(new EmailRegistrationResult(
					userId, details.displayEmail(), details.nickname(), details.timezone().getId(),
					details.baseCurrency(), details.locale()));
			} catch (UserEmailAlreadyExistsException exception) {
				throw new EmailAlreadyRegisteredException();
			}
		});

		return result.orElseThrow(RegistrationValidationException::new);
	}

	private static RegistrationDetails validate(EmailRegistrationCommand command) {
		if (command == null) {
			throw new RegistrationValidationException();
		}
		try {
			EmailAddress normalizedEmail = EmailAddress.normalize(command.email());
			String displayEmail = Normalizer.normalize(command.email().trim(), Normalizer.Form.NFKC);
			if (command.verificationCode() == null || !command.verificationCode().matches("[0-9]{6}")) {
				throw new RegistrationValidationException();
			}
			if (command.password() == null || command.password().length() < 10 || command.password().length() > 128) {
				throw new RegistrationValidationException();
			}
			String nickname = text(command.nickname(), 1, 100);
			ZoneId timezone = ZoneId.of(command.timezone());
			if (!BASE_CURRENCIES.contains(command.baseCurrency())) {
				throw new RegistrationValidationException();
			}
			String locale = text(command.locale(), 2, 16);
			return new RegistrationDetails(displayEmail, normalizedEmail.value(), nickname, timezone,
				command.baseCurrency(), locale);
		} catch (AuthDomainException | DateTimeException | NullPointerException exception) {
			throw new RegistrationValidationException();
		}
	}

	private static String text(String value, int minLength, int maxLength) {
		if (value == null || value.isBlank() || value.length() < minLength || value.length() > maxLength) {
			throw new RegistrationValidationException();
		}
		return value;
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}

	private record RegistrationDetails(
		String displayEmail,
		String emailNormalized,
		String nickname,
		ZoneId timezone,
		String baseCurrency,
		String locale) {
	}
}
