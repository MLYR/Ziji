package app.ziji.auth.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailAddress;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.UserCredential;
import app.ziji.user.application.UserCredentialLookupPort;
import app.ziji.user.application.UserCredentialStatus;

/**
 * 邮箱密码凭据认证用例；负责凭据校验、登录限流和统一失败语义，并为登录编排提供事务内会话创建入口。
 *
 * <p>固定处理顺序：先做请求格式校验（不进入限流和 Argon2），再在 REQUIRED 事务内消费四个登录限流桶；
 * 限流拒绝时事务先提交计数，再由应用层抛出带最长 Retry-After 的异常。通过限流后才查询凭据，
 * 并对每次请求只执行一次 Argon2id 校验（存在且支持的账号校验存储 Hash，其余校验 dummy Hash）以防止时序枚举。
 */
public final class PasswordLoginApplicationService {

	/** 凭据认证允许的账号状态；LOCKED 与 CLOSED 即使密码正确也拒绝，但仍会执行一次密码校验。 */
	private static final Set<UserCredentialStatus> AUTHENTICATABLE_STATUSES =
		Set.of(UserCredentialStatus.ACTIVE, UserCredentialStatus.CLOSING);

	private final TransactionRunner transactionRunner;
	private final AuthRateLimitStore rateLimitStore;
	private final UserCredentialLookupPort credentialLookupPort;
	private final PasswordHasher passwordHasher;
	private final DeviceSessionApplicationService deviceSessionService;
	/** 进程生命周期内只生成一次的 dummy Argon2id 编码，用于不存在或不支持账号的等时校验。 */
	private final String loginTimingDummyHash;

	public PasswordLoginApplicationService(
		TransactionRunner transactionRunner,
		AuthRateLimitStore rateLimitStore,
		UserCredentialLookupPort credentialLookupPort,
		PasswordHasher passwordHasher) {
		this(transactionRunner, rateLimitStore, credentialLookupPort, passwordHasher, null);
	}

	public PasswordLoginApplicationService(
		TransactionRunner transactionRunner,
		AuthRateLimitStore rateLimitStore,
		UserCredentialLookupPort credentialLookupPort,
		PasswordHasher passwordHasher,
		DeviceSessionApplicationService deviceSessionService) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.rateLimitStore = require(rateLimitStore, "登录限流存储");
		this.credentialLookupPort = require(credentialLookupPort, "凭据查询端口");
		this.passwordHasher = require(passwordHasher, "密码 Hash 服务");
		this.deviceSessionService = deviceSessionService;
		this.loginTimingDummyHash = generateLoginTimingDummyHash(passwordHasher);
	}

	public PasswordLoginResult login(PasswordLoginCommand command) {
		EmailAddress email = validate(command);
		String normalizedEmail = email.value();

		consumeLoginRateLimit(command, normalizedEmail);

		// 通过限流后，users 行锁、凭据校验和认证结果提交处于同一最外层事务。
		return transactionRunner.required(() -> authenticateWithinTransaction(command, normalizedEmail));
	}

	/**
	 * 登录成功后在同一最外层事务创建设备会话；调用方不得先调用 login() 再另起会话事务。
	 */
	public SessionTokenResult loginAndCreateSession(
		PasswordLoginCommand command,
		String deviceName,
		String deviceId) {
		EmailAddress email = validate(command);
		String normalizedEmail = email.value();
		consumeLoginRateLimit(command, normalizedEmail);
		if (deviceSessionService == null) {
			throw new AuthDomainException("登录会话服务未配置。");
		}

		return transactionRunner.required(() -> {
			PasswordLoginResult authenticated = authenticateWithinTransaction(command, normalizedEmail);
			return deviceSessionService.createForAuthenticatedUserWithinCurrentTransaction(
				new CreateDeviceSessionCommand(authenticated.userId(), deviceName, deviceId));
		});
	}

	private PasswordLoginResult authenticateWithinTransaction(
		PasswordLoginCommand command,
		String normalizedEmail) {
		Optional<UserCredential> credential = credentialLookupPort.findByNormalizedEmailForUpdate(normalizedEmail);
		boolean userExists = credential.isPresent();

		// 每次请求只选择一个校验目标：未知、损坏或不支持 Hash 的账号统一使用生命周期 dummy。
		String targetHash = (userExists && passwordHasher.supports(
			credential.get().passwordHashVersion(), credential.get().passwordHash()))
			? credential.get().passwordHash()
			: loginTimingDummyHash;
		boolean passwordMatches = matchesOnce(command.password(), targetHash);

		// ACTIVE/CLOSING 之外的状态与密码错误共享同一失败结果。
		boolean authenticatable = userExists && AUTHENTICATABLE_STATUSES.contains(credential.get().status());
		if (!authenticatable || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return new PasswordLoginResult(credential.get().userId(), credential.get().status());
	}

	private void consumeLoginRateLimit(PasswordLoginCommand command, String normalizedEmail) {
		// 限流事务独立提交；后续认证或会话失败不能回滚请求计数。
		RateLimitDecision decision = transactionRunner.required(() ->
			rateLimitStore.consumeLogin(normalizedEmail, command.sourceAddress(), command.now()));
		if (!decision.allowed()) {
			throw new LoginRateLimitedException(decision.retryAfterSeconds());
		}
	}

	private static EmailAddress validate(PasswordLoginCommand command) {
		if (command == null
			|| command.password() == null || command.password().length() < 1 || command.password().length() > 128
			|| command.sourceAddress() == null || command.now() == null) {
			throw new PasswordLoginValidationException();
		}
		try {
			return EmailAddress.normalize(command.email());
		} catch (AuthDomainException exception) {
			throw new PasswordLoginValidationException();
		}
	}

	/**
	 * 对本次请求只执行一次 Argon2id 校验；格式损坏值已在 supports 阶段回退 dummy，运行时失败不泄漏原因也不二次校验。
	 */
	private boolean matchesOnce(String password, String encodedHash) {
		try {
			return passwordHasher.matches(password, encodedHash);
		} catch (PasswordHashingException exception) {
			return false;
		}
	}

	/**
	 * 使用与生产相同的 Argon2id 参数生成一次 dummy 编码；种子为安全随机字节，不涉及任何用户输入，也不记录日志。
	 */
	private static String generateLoginTimingDummyHash(PasswordHasher hasher) {
		byte[] nonce = new byte[32];
		new SecureRandom().nextBytes(nonce);
		return hasher.hash(Base64.getEncoder().encodeToString(nonce));
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}
}
