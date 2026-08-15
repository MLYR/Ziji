package app.ziji.auth.infrastructure;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import app.ziji.auth.application.AccessTokenService;
import app.ziji.auth.application.DeviceSessionQueryService;
import app.ziji.auth.application.VerifiedAccessToken;
import app.ziji.auth.interfaces.AuthenticatedSessionPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bearer Access Token 过滤器：JWT 验签后再只读确认对应 V011 设备会话仍可用。 */
final class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String AUTHORIZATION = "Authorization";

	private final AccessTokenService accessTokenService;
	private final DeviceSessionQueryService sessionQueryService;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	AccessTokenAuthenticationFilter(
		AccessTokenService accessTokenService,
		DeviceSessionQueryService sessionQueryService,
		Clock clock,
		ObjectMapper objectMapper) {
		this.accessTokenService = accessTokenService;
		this.sessionQueryService = sessionQueryService;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator/health");
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {
		String encodedToken = singleBearerToken(request);
		if (encodedToken == null) {
			filterChain.doFilter(request, response);
			return;
		}
		if (encodedToken.isEmpty()) {
			AuthenticationProblemResponses.authenticationRequired(request, response, objectMapper);
			return;
		}
		VerifiedAccessToken verified;
		try {
			verified = accessTokenService.verify(encodedToken, clock.instant());
			if (!sessionQueryService.hasCurrentSession(verified.userId(), verified.sessionId())) {
				AuthenticationProblemResponses.authenticationRequired(request, response, objectMapper);
				return;
			}
		} catch (RuntimeException exception) {
			// 无论签名、时间、kid 或会话只读校验失败，都不暴露原因或编码 Token。
			AuthenticationProblemResponses.authenticationRequired(request, response, objectMapper);
			return;
		}
		AuthenticatedSessionPrincipal principal = new AuthenticatedSessionPrincipal(verified.userId(), verified.sessionId());
		UsernamePasswordAuthenticationToken authentication =
			new UsernamePasswordAuthenticationToken(principal, null, List.of());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		try {
			// 已认证后的业务异常必须继续交由 MVC 异常边界处理，不能伪装为凭据失效。
			filterChain.doFilter(request, response);
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static String singleBearerToken(HttpServletRequest request) {
		Enumeration<String> headers = request.getHeaders(AUTHORIZATION);
		if (headers == null || !headers.hasMoreElements()) {
			return null;
		}
		List<String> values = new ArrayList<>();
		while (headers.hasMoreElements()) {
			values.add(headers.nextElement());
		}
		if (values.size() != 1) {
			return "";
		}
		String value = values.getFirst();
		if (value == null || !value.startsWith("Bearer ") || value.length() <= "Bearer ".length()) {
			return "";
		}
		String token = value.substring("Bearer ".length());
		for (int index = 0; index < token.length(); index++) {
			if (Character.isWhitespace(token.charAt(index))) {
				return "";
			}
		}
		return token;
	}
}
