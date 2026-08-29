package app.ziji.auth.interfaces;

import java.time.Clock;
import java.time.Duration;

import app.ziji.auth.application.SessionTokenResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/** Web 会话 Cookie 传输边界；刷新凭据只进入 HttpOnly Cookie，从不进入 JSON 或日志。 */
@Component
public class WebSessionCookieService {

	private static final String REFRESH_COOKIE = "ziji_refresh";
	private static final String CSRF_COOKIE = "ziji_csrf";
	private static final String REFRESH_PATH = "/api/v1";
	private static final String CSRF_PATH = "/";

	private final CookieCsrfTokenRepository csrfTokenRepository;
	private final Clock clock;

	public WebSessionCookieService(CookieCsrfTokenRepository csrfTokenRepository, Clock clock) {
		this.csrfTokenRepository = csrfTokenRepository;
		this.clock = clock;
	}

	public void issue(
		HttpServletRequest request,
		HttpServletResponse response,
		SessionTokenResult session) {
		Duration maxAge = remainingSessionLifetime(session);
		// 不设 Domain 使两个 Cookie 固定为 host-only；各自路径和 SameSite 与清除 Cookie 完全一致。
		response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), maxAge).toString());
		CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
		response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie(csrfToken.getToken(), maxAge).toString());
	}

	public void clear(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
		response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString());
	}

	private Duration remainingSessionLifetime(SessionTokenResult session) {
		Duration remaining = Duration.between(clock.instant(), session.expiresAt());
		return remaining.isNegative() ? Duration.ZERO : remaining;
	}

	private static ResponseCookie refreshCookie(String value, Duration maxAge) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
			.path(REFRESH_PATH)
			.secure(true)
			.httpOnly(true)
			.sameSite("Strict")
			.maxAge(maxAge)
			.build();
	}

	private static ResponseCookie csrfCookie(String value, Duration maxAge) {
		return ResponseCookie.from(CSRF_COOKIE, value)
			.path(CSRF_PATH)
			.secure(true)
			.httpOnly(false)
			.sameSite("Strict")
			.maxAge(maxAge)
			.build();
	}
}
