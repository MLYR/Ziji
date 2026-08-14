package app.ziji.auth.infrastructure;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		// 用户资料只允许已认证主体访问，其余尚未实现路由继续 fail closed。
		http.authorizeHttpRequests(authorize -> authorize
			.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
			.requestMatchers("/api/v1/users/me").authenticated()
			.anyRequest().denyAll());
		// Web 使用 Cookie CSRF token；正式会话仍必须采用 HttpOnly refresh cookie。
		http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
		http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
			if ("/api/v1/users/me".equals(request.getRequestURI())) {
				writeAuthenticationProblem(request, response, objectMapper);
				return;
			}
			// 未实现路由继续返回 403，避免认证规则变化扩大其他路径的访问面。
			response.sendError(HttpStatus.FORBIDDEN.value());
		}));
		return http.build();
	}

	private void writeAuthenticationProblem(
		HttpServletRequest request,
		HttpServletResponse response,
		ObjectMapper objectMapper) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
			HttpStatus.UNAUTHORIZED, "需要认证");
		problem.setType(URI.create("https://ziji.app/problems/authentication-required"));
		problem.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", "AUTHENTICATION_REQUIRED");
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null ? "unknown" : requestId);
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType("application/problem+json");
		response.getWriter().write(objectMapper.writeValueAsString(problem));
	}
}
