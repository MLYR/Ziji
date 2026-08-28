package app.ziji.auth.infrastructure;

import java.util.Set;
import java.time.Clock;
import java.util.Enumeration;
import java.util.function.Supplier;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.DeviceSessionQueryService;
import app.ziji.auth.interfaces.WebSessionCookieService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName("ziji_csrf");
		repository.setHeaderName("X-CSRF-Token");
		repository.setCookiePath("/api/v1");
		repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
		return repository;
	}

	@Bean
	AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(
		AccessTokenService accessTokenService,
		DeviceSessionQueryService sessionQueryService,
		Clock clock,
		ObjectMapper objectMapper) {
		return new AccessTokenAuthenticationFilter(accessTokenService, sessionQueryService, clock, objectMapper);
	}

	@Bean
	SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		ObjectMapper objectMapper,
		AccessTokenAuthenticationFilter accessTokenAuthenticationFilter,
		CookieCsrfTokenRepository csrfTokenRepository,
		WebSessionCookieService webSessionCookies) throws Exception {
		// 已实现 operation 精确开放或认证；其余路径继续 fail closed。
		http.authorizeHttpRequests(authorize -> authorize
			.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
			.requestMatchers(HttpMethod.POST,
				"/api/v1/auth/registration-challenges", "/api/v1/auth/register",
				"/api/v1/auth/web/sessions", "/api/v1/auth/web/sessions/refresh",
				"/api/v1/auth/mobile/sessions", "/api/v1/auth/mobile/sessions/refresh",
				"/api/v1/auth/password-reset-challenges", "/api/v1/auth/password-reset").permitAll()
			.requestMatchers(HttpMethod.GET, "/api/v1/users/me", "/api/v1/users/me/sessions").authenticated()
			.requestMatchers(HttpMethod.GET,
				"/api/v1/accounts", "/api/v1/accounts/*", "/api/v1/accounts/*/liability-details",
				"/api/v1/accounts/*/balance", "/api/v1/accounts/*/liquidity-holds",
				"/api/v1/sync/changes", "/api/v1/transactions", "/api/v1/transactions/*",
				"/api/v1/dashboard", "/api/v1/statistics/assets", "/api/v1/statistics/cash-flow",
				"/api/v1/statistics/accounts").authenticated()
			.requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
			.requestMatchers(HttpMethod.PATCH,
				"/api/v1/accounts/*", "/api/v1/accounts/*/liability-details").authenticated()
			.requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/liability-details").authenticated()
			.requestMatchers(HttpMethod.POST, "/api/v1/accounts").authenticated()
			.requestMatchers(HttpMethod.POST, "/api/v1/users/me/password-change").authenticated()
			.requestMatchers(HttpMethod.POST,
				"/api/v1/accounts/*/liquidity-holds",
				"/api/v1/accounts/*/liquidity-holds/*/revisions",
				"/api/v1/accounts/*/liquidity-holds/*/release",
				"/api/v1/accounts/*/archive",
				"/api/v1/transactions",
				"/api/v1/transactions/*/revisions",
				"/api/v1/transactions/*/reversal",
				"/api/v1/accounts/*/balance-adjustments",
				"/api/v1/sync/operations").authenticated()
			.requestMatchers(HttpMethod.DELETE,
				"/api/v1/auth/sessions/current", "/api/v1/users/me/sessions", "/api/v1/users/me/sessions/*").authenticated()
			.anyRequest().denyAll());
		// 只有携带 Web refresh Cookie 的不安全请求走 CSRF；Mobile Bearer 请求不会被错误拦截。
		CsrfTokenRequestHandler csrfRequestHandler = new StrictCsrfTokenRequestHandler();
		http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
			.csrfTokenRequestHandler(csrfRequestHandler)
			.requireCsrfProtectionMatcher(request -> unsafeWithRefreshCookie(request)));
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		http.addFilterBefore(accessTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
		http.exceptionHandling(exceptions -> exceptions
			.authenticationEntryPoint((request, response, exception) -> {
				if (isImplementedAuthenticatedOperation(request)) {
					AuthenticationProblemResponses.authenticationRequired(request, response, objectMapper);
					return;
				}
				// 未实现或未知路由保持原有 403 deny-all，不能因认证功能扩大路由面。
				response.sendError(403);
			})
			.accessDeniedHandler((request, response, exception) -> {
				if (exception instanceof CsrfException) {
					// 仅 CSRF 拒绝清理成对 Cookie，防止缺失 ziji_csrf 时旧 HttpOnly refresh 凭据永久阻塞重新认证。
					webSessionCookies.clear(response);
				}
				AuthenticationProblemResponses.permissionDenied(request, response, objectMapper);
			}));
		return http.build();
	}

	private static boolean unsafeWithRefreshCookie(jakarta.servlet.http.HttpServletRequest request) {
		if (Set.of("GET", "HEAD", "TRACE", "OPTIONS").contains(request.getMethod())) {
			return false;
		}
		jakarta.servlet.http.Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return false;
		}
		for (jakarta.servlet.http.Cookie cookie : cookies) {
			if ("ziji_refresh".equals(cookie.getName())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isImplementedAuthenticatedOperation(HttpServletRequest request) {
		String method = request.getMethod();
		String path = request.getRequestURI();
		if ("GET".equals(method)) {
			return "/api/v1/users/me".equals(path) || "/api/v1/users/me/sessions".equals(path)
				|| "/api/v1/accounts".equals(path)
				|| "/api/v1/sync/changes".equals(path)
				|| "/api/v1/transactions".equals(path)
				|| "/api/v1/dashboard".equals(path)
				|| "/api/v1/statistics/assets".equals(path)
				|| "/api/v1/statistics/cash-flow".equals(path)
				|| "/api/v1/statistics/accounts".equals(path)
				|| path.matches("/api/v1/transactions/[^/]+")
				|| path.matches("/api/v1/accounts/[^/]+")
				|| path.matches("/api/v1/accounts/[^/]+/balance")
				|| path.matches("/api/v1/accounts/[^/]+/liability-details")
				|| path.matches("/api/v1/accounts/[^/]+/liquidity-holds");
		}
		if ("PATCH".equals(method)) {
			return "/api/v1/users/me".equals(path) || path.matches("/api/v1/accounts/[^/]+")
				|| path.matches("/api/v1/accounts/[^/]+/liability-details");
		}
		if ("PUT".equals(method)) {
			return path.matches("/api/v1/accounts/[^/]+/liability-details");
		}
		if ("POST".equals(method)) {
			return "/api/v1/accounts".equals(path)
				|| "/api/v1/users/me/password-change".equals(path)
				|| "/api/v1/sync/operations".equals(path)
				|| "/api/v1/transactions".equals(path)
				|| path.matches("/api/v1/accounts/[^/]+/archive")
				|| path.matches("/api/v1/transactions/[^/]+/(revisions|reversal)")
				|| path.matches("/api/v1/accounts/[^/]+/liquidity-holds")
				|| path.matches("/api/v1/accounts/[^/]+/liquidity-holds/[^/]+/(revisions|release)")
				|| path.matches("/api/v1/accounts/[^/]+/balance-adjustments");
		}
		if (!"DELETE".equals(method)) {
			return false;
		}
		return "/api/v1/auth/sessions/current".equals(path)
			|| "/api/v1/users/me/sessions".equals(path)
			|| path.matches("/api/v1/users/me/sessions/[^/]+");
	}

	/** 仅接受唯一的 CSRF Cookie 与 Header，重复传输值不能由 Servlet 容器任选一个继续校验。 */
	private static final class StrictCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

		private final CsrfTokenRequestAttributeHandler delegate = new CsrfTokenRequestAttributeHandler();

		@Override
		public void handle(
			HttpServletRequest request,
			jakarta.servlet.http.HttpServletResponse response,
			Supplier<CsrfToken> csrfToken) {
			delegate.handle(request, response, csrfToken);
		}

		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			Enumeration<String> headers = request.getHeaders(csrfToken.getHeaderName());
			if (headers == null || !headers.hasMoreElements()) {
				return null;
			}
			String value = headers.nextElement();
			if (headers.hasMoreElements() || value == null || !hasSingleCsrfCookie(request)) {
				return null;
			}
			return value;
		}

		private static boolean hasSingleCsrfCookie(HttpServletRequest request) {
			Cookie[] cookies = request.getCookies();
			if (cookies == null) {
				return false;
			}
			boolean found = false;
			for (Cookie cookie : cookies) {
				if ("ziji_csrf".equals(cookie.getName())) {
					if (found || cookie.getValue() == null || cookie.getValue().isBlank()) {
						return false;
					}
					found = true;
				}
			}
			return found;
		}
	}
}
