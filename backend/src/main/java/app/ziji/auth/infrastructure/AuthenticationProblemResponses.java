package app.ziji.auth.infrastructure;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** Spring Security 过滤器和入口统一输出最小 Problem Details，不回显未归一化请求头。 */
final class AuthenticationProblemResponses {

	private AuthenticationProblemResponses() {
	}

	static void authenticationRequired(
		HttpServletRequest request,
		HttpServletResponse response,
		ObjectMapper objectMapper) throws IOException {
		write(request, response, objectMapper, HttpStatus.UNAUTHORIZED, "需要认证", "AUTHENTICATION_REQUIRED");
	}

	static void permissionDenied(
		HttpServletRequest request,
		HttpServletResponse response,
		ObjectMapper objectMapper) throws IOException {
		write(request, response, objectMapper, HttpStatus.FORBIDDEN, "无权执行此操作", "PERMISSION_DENIED");
	}

	private static void write(
		HttpServletRequest request,
		HttpServletResponse response,
		ObjectMapper objectMapper,
		HttpStatus status,
		String detail,
		String code) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		response.setStatus(status.value());
		// Security Filter 在 Controller 前拒绝的认证/CSRF 响应也禁止被浏览器或中间缓存保存。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setContentType("application/problem+json");
		objectMapper.writeValue(response.getWriter(), problem);
	}
}
