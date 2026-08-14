package app.ziji.auth.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 稳定会话的设备边界、固定期限和刷新凭据格式领域测试。 */
class DeviceSessionTests {

	@Test
	void deviceNameUsesNfkcAndTrimWhileDeviceIdKeepsRawValue() {
		DeviceName name = DeviceName.of("  ＭａｃＢｏｏｋ　 ");
		DeviceId deviceId = DeviceId.ofNullable("  opaque-device-id  ");

		assertEquals("MacBook", name.value());
		assertEquals("  opaque-device-id  ", deviceId.value());
		assertNull(DeviceId.ofNullable(null));
	}

	@Test
	void deviceNameAndDeviceIdEnforceIndependentBoundaries() {
		assertThrows(AuthDomainException.class, () -> DeviceName.of(null));
		assertThrows(AuthDomainException.class, () -> DeviceName.of(" \t "));
		assertThrows(AuthDomainException.class, () -> DeviceName.of("a".repeat(101)));
		assertThrows(AuthDomainException.class, () -> DeviceId.ofNullable(""));
		assertThrows(AuthDomainException.class, () -> DeviceId.ofNullable("\u3000"));
		assertThrows(AuthDomainException.class, () -> DeviceId.ofNullable("a".repeat(201)));
		assertEquals(200, DeviceId.ofNullable("a".repeat(200)).value().length());
	}

	@Test
	void sessionUsesFixedThirtyDayAbsoluteExpiryAndInitialLastSeen() {
		Instant issuedAt = Instant.parse("2026-08-14T00:00:00Z");
		DeviceSession session = DeviceSession.create(
			UUID.randomUUID(), UUID.randomUUID(), null, DeviceName.of("iPhone"), issuedAt);

		assertEquals(issuedAt.plus(30, ChronoUnit.DAYS), session.expiresAt());
		assertEquals(issuedAt, session.lastSeenAt());
		assertTrue(session.isActiveAt(issuedAt));
		assertFalse(session.isActiveAt(session.expiresAt()));
	}

	@Test
	void refreshTokenUsesSecureRandomBase64UrlAndVersionedDomainSeparatedHash() {
		RefreshToken first = RefreshToken.generate(new SecureRandom());
		RefreshToken second = RefreshToken.generate(new SecureRandom());
		RefreshTokenHash firstHash = RefreshTokenHash.from(first);

		assertTrue(first.value().matches("rt1_[A-Za-z0-9_-]{43}"));
		assertTrue(RefreshToken.isWellFormed(first.value()));
		assertFalse(first.value().equals(second.value()));
		assertTrue(firstHash.value().matches("v1:[0-9a-f]{64}"));
		assertEquals(firstHash.value(), RefreshTokenHash.from(RefreshToken.fromClient(first.value())).value());
		assertFalse(first.toString().contains(first.value()));
		assertThrows(AuthDomainException.class, () -> RefreshToken.fromClient("rt1_not-a-32-byte-token"));
	}
}
