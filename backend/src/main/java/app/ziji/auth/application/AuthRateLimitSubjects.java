package app.ziji.auth.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.SourceAddress;

/** 限流主体材料；设备缺失时组合来源 IP 和域标记，不把原值交给数据库。 */
public final class AuthRateLimitSubjects {

	private static final String MISSING_DEVICE = "MISSING_DEVICE";

	private final String normalizedEmail;
	private final String deviceId;
	private final SourceAddress sourceAddress;

	private AuthRateLimitSubjects(String normalizedEmail, String deviceId, SourceAddress sourceAddress) {
		this.normalizedEmail = normalizedEmail;
		this.deviceId = deviceId;
		this.sourceAddress = sourceAddress;
	}

	public static AuthRateLimitSubjects of(
		String normalizedEmail,
		String deviceId,
		SourceAddress sourceAddress) {
		if (normalizedEmail == null || normalizedEmail.isBlank() || sourceAddress == null) {
			throw new AuthDomainException("限流主体不能为空。");
		}
		String normalizedDeviceId = deviceId;
		if (deviceId != null) {
			normalizedDeviceId = Normalizer.normalize(deviceId, Normalizer.Form.NFKC);
			if (normalizedDeviceId.isBlank() || normalizedDeviceId.length() > 200) {
				throw new AuthDomainException("设备标识格式无效。");
			}
		}
		return new AuthRateLimitSubjects(normalizedEmail, normalizedDeviceId, sourceAddress);
	}

	public String normalizedEmail() {
		return normalizedEmail;
	}

	public SourceAddress sourceAddress() {
		return sourceAddress;
	}

	public byte[] emailBytes() {
		return normalizedEmail.getBytes(StandardCharsets.UTF_8);
	}

	public byte[] ipBytes() {
		return sourceAddress.bytes();
	}

	public byte[] deviceBytes() {
		if (deviceId != null) {
			return deviceId.getBytes(StandardCharsets.UTF_8);
		}
		byte[] marker = MISSING_DEVICE.getBytes(StandardCharsets.UTF_8);
		byte[] address = sourceAddress.bytes();
		return ByteBuffer.allocate(Integer.BYTES + address.length + Integer.BYTES + marker.length)
			.putInt(address.length)
			.put(address)
			.putInt(marker.length)
			.put(marker)
			.array();
	}
}
