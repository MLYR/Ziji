package app.ziji.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 稳定设备会话的安全生命周期；刷新轮换只能延续此 sessionId，不能延长绝对期限。 */
public final class DeviceSession {

	public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);

	private final UUID id;
	private final UUID userId;
	private final String deviceId;
	private final String deviceName;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final Instant revokedAt;
	private final String revokeReason;
	private final Instant lastSeenAt;
	private final Integer securityBaselineVersion;

	private DeviceSession(
		UUID id,
		UUID userId,
		String deviceId,
		String deviceName,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		String revokeReason,
		Instant lastSeenAt,
		Integer securityBaselineVersion) {
		this.id = require(id, "会话 ID");
		this.userId = require(userId, "用户 ID");
		this.deviceId = deviceId;
		this.deviceName = deviceName;
		this.issuedAt = require(issuedAt, "会话签发时间");
		this.expiresAt = require(expiresAt, "会话到期时间");
		this.revokedAt = revokedAt;
		this.revokeReason = revokeReason;
		this.lastSeenAt = require(lastSeenAt, "会话最后活动时间");
		this.securityBaselineVersion = securityBaselineVersion;
	}

	public static DeviceSession create(
		UUID id,
		UUID userId,
		DeviceId deviceId,
		DeviceName deviceName,
		Instant issuedAt) {
		Instant expiresAt = require(issuedAt, "会话签发时间").plus(ABSOLUTE_LIFETIME);
		// 新建会话已满足 V011；数据库触发器在 INSERT 时写入同一基线版本。
		return new DeviceSession(id, userId, deviceId == null ? null : deviceId.value(), require(deviceName, "设备名称").value(),
			issuedAt, expiresAt, null, null, issuedAt, 1);
	}

	/** 从当前 V011 持久化事实恢复。 */
	public static DeviceSession restore(
		UUID id,
		UUID userId,
		DeviceId deviceId,
		DeviceName deviceName,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		String revokeReason,
		Instant lastSeenAt) {
		return restore(id, userId, deviceId == null ? null : deviceId.value(),
			deviceName == null ? null : deviceName.value(), issuedAt, expiresAt, revokedAt, revokeReason, lastSeenAt, 1);
	}

	/**
	 * 从持久化事实恢复，历史行保留原始设备、期限和 NULL 基线，不把它们伪造成 V011 新会话。
	 */
	public static DeviceSession restore(
		UUID id,
		UUID userId,
		String deviceId,
		String deviceName,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		String revokeReason,
		Instant lastSeenAt,
		Integer securityBaselineVersion) {
		return new DeviceSession(id, userId, deviceId, deviceName, issuedAt, expiresAt, revokedAt, revokeReason,
			lastSeenAt, securityBaselineVersion);
	}

	public UUID id() {
		return id;
	}

	public UUID userId() {
		return userId;
	}

	public String deviceId() {
		return deviceId;
	}

	public String deviceName() {
		return deviceName;
	}

	public Instant issuedAt() {
		return issuedAt;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Instant revokedAt() {
		return revokedAt;
	}

	public String revokeReason() {
		return revokeReason;
	}

	public Instant lastSeenAt() {
		return lastSeenAt;
	}

	/** 历史 NULL 基线只能走不可逆安全处置，不能参与新 Token 轮换。 */
	public boolean isCurrentSecurityBaseline() {
		return Integer.valueOf(1).equals(securityBaselineVersion);
	}

	public Integer securityBaselineVersion() {
		return securityBaselineVersion;
	}

	public boolean isActiveAt(Instant now) {
		return revokedAt == null && now != null && now.isBefore(expiresAt);
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new AuthDomainException(name + "不能为空。");
		}
		return value;
	}
}
