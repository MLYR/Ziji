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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 邮箱密码凭据认证用例；只负责凭据校验、登录限流和统一失败语义，不创建任何会话、Token、Cookie 或审计事实。
 *
 * <p>固定处理顺序：先做请求格式校验（不进入限流和 Argon2），再在 REQUIRED 事务内消费四个登录限流桶；
 * 限流拒绝时事务先提交计数，再由应用层抛出带最长 Retry-After 的异常。通过限流后才查询凭据，
 * 并对每次请求只执行一次 Argon2id 校验（存在且支持的账号校验存储 Hash，其余校验 dummy Hash）以防止时序枚举。
 */
@Service
public final class PasswordLoginApplicationService {

	/** 凭据认证允许的账号状态；LOCKED 与 CLOSED 即使密码正确也拒绝，但仍会执行一次密码校验。 */
	private static final Set<UserCredentialStatus> AUTHENTICATABLE_STATUSES =
		Set.of(UserCredentialStatus.ACTIVE, UserCredentialStatus.CLOSING);

	private final TransactionRunner transactionRunner;
	private final AuthRateLimitStore rateLimitStore;
	private final UserCredentialLookupPort credentialLookupPort;
	private final PasswordHasher passwordHasher;
	/** 进程生命周期内只生成一次的 dummy Argon2id 编码，用于不存在或不支持账号的等时校验。 */
	private final String loginTimingDummyHash;

	@Autowired
	public PasswordLoginApplicationService(
		TransactionRunner transactionRunner,
		AuthRateLimitStore rateLimitStore,
		UserCredentialLookupPort credentialLookupPort,
		PasswordHasher passwordHasher) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.rateLimitStore = require(rateLimitStore, "登录限流存储");
		this.credentialLookupPort = require(credentialLookupPort, "凭据查询端口");
		this.passwordHasher = require(passwordHasher, "密码 Hash 服务");
		this.loginTimingDummyHash = generateLoginTimingDummyHash(passwordHasher);
	}

	public PasswordLoginResult login(PasswordLoginCommand command) {
		EmailAddress email = validate(command);
		String normalizedEmail = email.value();

		// 登录限流必须在 REQUIRED 事务内完成；拒绝时事务正常返回以提交全部桶计数，再由应用层抛出。
		RateLimitDecision decision = transactionRunner.required(() ->
			rateLimitStore.consumeLogin(normalizedEmail, command.sourceAddress(), command.now()));
		if (!decision.allowed()) {
			throw new LoginRateLimitedException(decision.retryAfterSeconds());
		}

		Optional<UserCredential> credential = credentialLookupPort.findByNormalizedEmail(normalizedEmail);
		boolean userExists = credential.isPresent();

		// 选择本次唯一的校验目标：存在且支持的账号校验存储 Hash，其余（不存在、版本不支持、格式损坏）校验 dummy Hash。
		String targetHash = (userExists && passwordHasher.supports(
			credential.get().passwordHashVersion(), credential.get().passwordHash()))
			? credential.get().passwordHash()
			: loginTimingDummyHash;
		boolean passwordMatches = matchesOnce(command.password(), targetHash);

		// 已存在用户无论状态都执行了一次密码校验，这里再合并状态资格与密码结果。
		boolean authenticatable = userExists && AUTHENTICATABLE_STATUSES.contains(credential.get().status());
		if (!authenticatable || !passwordMatches) {
			throw new InvalidCredentialsException();
		}

		return new PasswordLoginResult(credential.get().userId(), credential.get().status());
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
