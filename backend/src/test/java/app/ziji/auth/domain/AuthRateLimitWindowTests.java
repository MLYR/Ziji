package app.ziji.auth.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定窗口 epoch 对齐和 Retry-After 最小值测试。 */
class AuthRateLimitWindowTests {

	@Test
	void exactEpochBoundaryStartsANewWindow() {
		AuthRateLimitWindow window = AuthRateLimitWindow.EMAIL_60S;
		Instant before = Instant.ofEpochSecond(59, 999_000_000);
		Instant boundary = Instant.ofEpochSecond(60);

		assertEquals(Instant.EPOCH, window.windowStartedAt(before));
		assertEquals(boundary, window.windowStartedAt(boundary));
		assertEquals(1, window.retryAfterSeconds(before));
	}

	@Test
	void retryAfterNeverReturnsZeroAtWindowEnd() {
		AuthRateLimitWindow window = AuthRateLimitWindow.EMAIL_60S;

		assertEquals(1, window.retryAfterSeconds(Instant.ofEpochSecond(59, 999_999_999)));
	}
}
