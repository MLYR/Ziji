package app.ziji.auth.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.DeviceId;
import app.ziji.auth.domain.DeviceName;
import app.ziji.auth.domain.DeviceSession;
import app.ziji.auth.domain.RefreshToken;
import app.ziji.auth.domain.RefreshTokenHash;
import app.ziji.auth.domain.SessionRevocationReason;
import app.ziji.auth.domain.StoredRefreshToken;
import app.ziji.shared.application.TransactionRunner;

/**
 * 已认证用户的稳定设备会话、正常刷新轮换和传输无关的撤销用例；不包含 HTTP、Cookie、CSRF 或 Mobile 编排。
 */
public final class DeviceSessionApplicationService {

	private final TransactionRunner transactionRunner;
	private final DeviceSessionStore sessionStore;
	private final AccessTokenService accessTokenService;
	private final SecureRandom secureRandom;
	private final Clock clock;
	private final Supplier<UUID> uuidGenerator;

	public DeviceSessionApplicationService(
		TransactionRunner transactionRunner,
		DeviceSessionStore sessionStore,
		AccessTokenService accessTokenService,
		SecureRandom secureRandom,
		Clock clock) {
		this(transactionRunner, sessionStore, accessTokenService, secureRandom, clock, UUID::randomUUID);
	}

	public DeviceSessionApplicationService(
		TransactionRunner transactionRunner,
		DeviceSessionStore sessionStore,
		AccessTokenService accessTokenService,
		SecureRandom secureRandom,
		Clock clock,
		Supplier<UUID> uuidGenerator) {
		this.transactionRunner = require(transactionRunner, "事务入口");
		this.sessionStore = require(sessionStore, "会话存储");
		this.accessTokenService = require(accessTokenService, "Access Token 服务");
		this.secureRandom = require(secureRandom, "安全随机源");
		this.clock = require(clock, "时钟");
		this.uuidGenerator = require(uuidGenerator, "UUID 生成器");
	}

	public SessionTokenResult createForAuthenticatedUser(CreateDeviceSessionCommand command) {
		SessionInput input = validateCreate(command);
		Instant now = clock.instant();

		return transactionRunner.required(() -> {
			// 同一非空 deviceId 必须先锁定并撤销旧活动会话及当前 Token，避免跨实例并发保留两个活动会话。
			if (input.deviceId() != null) {
				sessionStore.revokeActiveSessionForReplacement(input.userId(), input.deviceId().value(), now);
			}
			DeviceSession session = DeviceSession.create(nextUuid(), input.userId(), input.deviceId(), input.deviceName(), now);
			RefreshToken refreshToken = RefreshToken.generate(secureRandom);
			StoredRefreshToken storedToken = StoredRefreshToken.issue(
				nextUuid(), session.id(), RefreshTokenHash.from(refreshToken), now, session.expiresAt());

			// 会话、唯一的当前刷新凭据与 Access Token 签发同处最外层事务；任一步失败都回滚旧会话替换。
			sessionStore.insertSession(session);
			sessionStore.insertRefreshToken(storedToken);
			IssuedAccessToken accessToken = accessTokenService.issue(
				session.userId(), session.id(), now, session.expiresAt());
			return result(session, accessToken, refreshToken);
		});
	}

	public SessionTokenResult rotate(RotateRefreshTokenCommand command) {
		RefreshToken currentToken = validateRefreshToken(command);
		RefreshTokenHash currentHash = RefreshTokenHash.from(currentToken);
		Instant now = clock.instant();

		return transactionRunner.required(() -> {
			// 按摘要锁定 Token 和所属会话；同一原始 Token 的并发请求会串行，只有第一个可正常消费。
			RefreshTokenSessionState state = sessionStore.findRefreshTokenForUpdate(currentHash.value())
				.orElseThrow(() -> new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.INVALID));
			requireCurrent(state, now);

			DeviceSession session = state.session();
			RefreshToken nextToken = RefreshToken.generate(secureRandom);
			StoredRefreshToken nextStoredToken = StoredRefreshToken.issue(
				nextUuid(), session.id(), RefreshTokenHash.from(nextToken), now, session.expiresAt());
			Instant nextLastSeenAt = now.isAfter(session.lastSeenAt()) ? now : session.lastSeenAt();

			// V011 延迟 replacement 约束允许此固定顺序原子提交，任一返回 false 均由异常触发整体回滚。
			if (!sessionStore.consumeRefreshToken(state.refreshToken().id(), now)) {
				throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.CONSUMED);
			}
			sessionStore.insertRefreshToken(nextStoredToken);
			if (!sessionStore.linkReplacement(state.refreshToken().id(), nextStoredToken.id())) {
				throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.INVALID);
			}
			if (!sessionStore.updateLastSeen(session.id(), nextLastSeenAt)) {
				throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.SESSION_REVOKED);
			}
			IssuedAccessToken accessToken = accessTokenService.issue(
				session.userId(), session.id(), now, session.expiresAt());
			return result(session, nextLastSeenAt, accessToken, nextToken);
		});
	}

	/**
	 * 仅在正常轮换返回 CONSUMED 信号后调用：重新解析并计算同一域分离摘要，再在行锁内复核已消费状态。
	 */
	public RefreshTokenReuseResult handleConsumedRefreshTokenReuse(RotateRefreshTokenCommand command) {
		RefreshToken refreshToken = parseRefreshTokenForReuse(command);
		Instant now = clock.instant();

		return transactionRunner.required(() -> {
			if (refreshToken == null) {
				return RefreshTokenReuseResult.notReused();
			}
			String tokenHash = RefreshTokenHash.from(refreshToken).value();
			RefreshTokenSessionState state = sessionStore.findRefreshTokenForUpdate(tokenHash).orElse(null);
			if (!isConsumedReuseCandidate(state)) {
				return RefreshTokenReuseResult.notReused();
			}
			if (state.session().revokedAt() != null) {
				return RefreshTokenReuseResult.alreadyRevoked();
			}
			// 已锁定会话后才撤销其所有未消费、未撤销 Token，避免与正常轮换留下可用凭据。
			if (!sessionStore.revokeSession(
				state.session().id(), now, effectiveReason(state.session(), SessionRevocationReason.REFRESH_TOKEN_REUSE))) {
				return RefreshTokenReuseResult.alreadyRevoked();
			}
			sessionStore.revokeCurrentRefreshTokens(state.session().id(), now);
			return RefreshTokenReuseResult.revoked();
		});
	}

	/** 撤销当前已认证用户的当前设备会话；重复调用不会恢复任何安全状态。 */
	public SessionRevocationResult revokeCurrentDevice(UUID authenticatedUserId, UUID sessionId) {
		return revokeOneDevice(authenticatedUserId, sessionId, SessionRevocationReason.CURRENT_DEVICE);
	}

	/** 撤销当前已认证用户指定的可见设备；他人或不存在的会话统一返回安全结果。 */
	public SessionRevocationResult revokeSelectedDevice(UUID authenticatedUserId, UUID sessionId) {
		return revokeOneDevice(authenticatedUserId, sessionId, SessionRevocationReason.SELECTED_DEVICE);
	}

	/** 按数据库锁定顺序撤销当前用户的所有未撤销会话及其当前 Refresh Token。 */
	public SessionRevocationResult revokeAllDevices(UUID authenticatedUserId) {
		Instant now = clock.instant();
		return transactionRunner.required(() -> {
			if (authenticatedUserId == null) {
				return SessionRevocationResult.alreadyRevoked();
			}
			int revokedCount = 0;
			for (DeviceSession session : sessionStore.findActiveSessionsForUserForUpdate(authenticatedUserId)) {
				if (sessionStore.revokeSession(
					session.id(), now, effectiveReason(session, SessionRevocationReason.ALL_DEVICES))) {
					sessionStore.revokeCurrentRefreshTokens(session.id(), now);
					revokedCount++;
				}
			}
			return revokedCount == 0 ? SessionRevocationResult.alreadyRevoked() : SessionRevocationResult.revoked(revokedCount);
		});
	}

	private static void requireCurrent(RefreshTokenSessionState state, Instant now) {
		if (!state.session().isCurrentSecurityBaseline()) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.INVALID);
		}
		if (state.refreshToken().consumedAt() != null) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.CONSUMED);
		}
		if (state.refreshToken().revokedAt() != null) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.REVOKED);
		}
		if (state.session().revokedAt() != null) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.SESSION_REVOKED);
		}
		if (!now.isBefore(state.refreshToken().expiresAt())) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.EXPIRED);
		}
		if (!now.isBefore(state.session().expiresAt())) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.SESSION_EXPIRED);
		}
	}

	private SessionRevocationResult revokeOneDevice(
		UUID authenticatedUserId,
		UUID sessionId,
		SessionRevocationReason requestedReason) {
		Instant now = clock.instant();
		return transactionRunner.required(() -> {
			if (authenticatedUserId == null || sessionId == null) {
				return SessionRevocationResult.notFound();
			}
			DeviceSession session = sessionStore.findSessionForUserForUpdate(authenticatedUserId, sessionId).orElse(null);
			if (session == null) {
				return SessionRevocationResult.notFound();
			}
			if (session.revokedAt() != null) {
				return SessionRevocationResult.alreadyRevoked();
			}
			// 历史 NULL 基线不伪造现代操作原因，统一以 SECURITY_ADMIN 完成不可逆安全处置。
			if (!sessionStore.revokeSession(session.id(), now, effectiveReason(session, requestedReason))) {
				return SessionRevocationResult.alreadyRevoked();
			}
			sessionStore.revokeCurrentRefreshTokens(session.id(), now);
			return SessionRevocationResult.revoked(1);
		});
	}

	private static boolean isConsumedReuseCandidate(RefreshTokenSessionState state) {
		return state != null
			&& state.refreshToken().consumedAt() != null
			&& state.refreshToken().revokedAt() == null;
	}

	private static SessionRevocationReason effectiveReason(
		DeviceSession session,
		SessionRevocationReason requestedReason) {
		return session.isCurrentSecurityBaseline() ? requestedReason : SessionRevocationReason.SECURITY_ADMIN;
	}

	private static SessionInput validateCreate(CreateDeviceSessionCommand command) {
		if (command == null || command.userId() == null) {
			throw new SessionTokenValidationException();
		}
		try {
			return new SessionInput(command.userId(), DeviceName.of(command.deviceName()), DeviceId.ofNullable(command.deviceId()));
		} catch (AuthDomainException exception) {
			throw new SessionTokenValidationException();
		}
	}

	private static RefreshToken validateRefreshToken(RotateRefreshTokenCommand command) {
		try {
			if (command == null) {
				throw new AuthDomainException("刷新凭据不能为空。");
			}
			return RefreshToken.fromClient(command.refreshToken());
		} catch (AuthDomainException exception) {
			throw new RefreshTokenRejectedException(RefreshTokenRejectedException.Reason.INVALID);
		}
	}

	private static RefreshToken parseRefreshTokenForReuse(RotateRefreshTokenCommand command) {
		try {
			return command == null ? null : RefreshToken.fromClient(command.refreshToken());
		} catch (AuthDomainException exception) {
			return null;
		}
	}

	private static SessionTokenResult result(
		DeviceSession session,
		IssuedAccessToken accessToken,
		RefreshToken refreshToken) {
		return result(session, session.lastSeenAt(), accessToken, refreshToken);
	}

	private static SessionTokenResult result(
		DeviceSession session,
		Instant lastSeenAt,
		IssuedAccessToken accessToken,
		RefreshToken refreshToken) {
		return new SessionTokenResult(
			session.id(), session.deviceName(), session.deviceId(), session.issuedAt(), session.expiresAt(),
			lastSeenAt, accessToken.value(), accessToken.expiresAt(), refreshToken.value());
	}

	private UUID nextUuid() {
		UUID value = uuidGenerator.get();
		if (value == null) {
			throw new IllegalStateException("UUID 生成失败。");
		}
		return value;
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}

	private record SessionInput(UUID userId, DeviceName deviceName, DeviceId deviceId) {
	}
}
