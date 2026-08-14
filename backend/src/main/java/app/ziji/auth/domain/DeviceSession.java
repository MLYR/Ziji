package app.ziji.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 稳定设备会话的安全生命周期；刷新轮换只能延续此 sessionId，不能延长绝对期限。 */
public final class DeviceSession {

	public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);

	private final UUID id;
	private final UUID userId;
	private final DeviceId deviceId;
	private final DeviceName deviceName;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final Instant revokedAt;
	private final String revokeReason;
	private final Instant lastSeenAt;

	private DeviceSession(
		UUID id,
		UUID userId,
		DeviceId deviceId,
		DeviceName deviceName,
		Instant issuedAt,
		Instant expiresAt,
		Instant revokedAt,
		String revokeReason,
		Instant lastSeenAt) {
		this.id = require(id, "会话 ID");
		this.userId = require(userId, "用户 ID");
		this.deviceId = deviceId;
		this.deviceName = require(deviceName, "设备名称");
		this.issuedAt = require(issuedAt, "会话签发时间");
		this.expiresAt = require(expiresAt, "会话到期时间");
		this.revokedAt = revokedAt;
		this.revokeReason = revokeReason;
		this.lastSeenAt = require(lastSeenAt, "会话最后活动时间");
	}

	public static DeviceSession create(
		UUID id,
		UUID userId,
		DeviceId deviceId,
		DeviceName deviceName,
		Instant issuedAt) {
		Instant expiresAt = require(issuedAt, "会话签发时间").plus(ABSOLUTE_LIFETIME);
		return new DeviceSession(id, userId, deviceId, deviceName, issuedAt, expiresAt, null, null, issuedAt);
	}

	/** 从持久化事实恢复；V011 前历史行保留其原有生命周期，不能在应用层伪造为新基线。 */
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
		return new DeviceSession(id, userId, deviceId, deviceName, issuedAt, expiresAt, revokedAt, revokeReason, lastSeenAt);
	}

	public UUID id() {
		return id;
	}

	public UUID userId() {
		return userId;
	}

	public String deviceId() {
		return deviceId == null ? null : deviceId.value();
	}

	public String deviceName() {
		return deviceName.value();
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
