package app.ziji.auth.interfaces;

import java.net.URI;

import app.ziji.auth.application.DeviceSessionQueryValidationException;
import app.ziji.auth.application.EmailAlreadyRegisteredException;
import app.ziji.auth.application.InvalidCredentialsException;
import app.ziji.auth.application.LoginRateLimitedException;
import app.ziji.auth.application.PasswordLoginValidationException;
import app.ziji.auth.application.PasswordManagementValidationException;
import app.ziji.auth.application.RegistrationValidationException;
import app.ziji.auth.application.SessionTokenValidationException;
import app.ziji.auth.domain.AuthDomainException;
import app.ziji.shared.application.IdempotencyValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Auth HTTP 边界的稳定错误映射；异常文本从不成为对外 detail。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthApiExceptionHandler {

	@ExceptionHandler({
		AuthHttpValidationException.class,
		PasswordLoginValidationException.class,
		PasswordManagementValidationException.class,
		RegistrationValidationException.class,
		SessionTokenValidationException.class,
		DeviceSessionQueryValidationException.class,
		IdempotencyValidationException.class,
		AuthDomainException.class
	})
	ProblemDetail validationFailure(RuntimeException exception, HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail invalidCredentials(
		InvalidCredentialsException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		// 登录或当前密码校验失败都包含认证结论，禁止缓存统一 401。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		return problem(HttpStatus.UNAUTHORIZED, "邮箱或密码无效", "INVALID_CREDENTIALS", request, response);
	}

	@ExceptionHandler(AuthHttpAuthenticationException.class)
	ProblemDetail authenticationRequired(
		AuthHttpAuthenticationException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		return problem(HttpStatus.UNAUTHORIZED, "需要认证", "AUTHENTICATION_REQUIRED", request, response);
	}

	@ExceptionHandler(LoginRateLimitedException.class)
	ProblemDetail rateLimited(
		LoginRateLimitedException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		response.setHeader("Retry-After", Integer.toString(exception.retryAfterSeconds()));
		return problem(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁", "RATE_LIMITED", request, response);
	}

	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	ProblemDetail duplicateUser(
		EmailAlreadyRegisteredException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		return problem(HttpStatus.CONFLICT, "资源已存在", "DUPLICATE_RESOURCE", request, response);
	}

	private ProblemDetail problem(
		HttpStatus status,
		String detail,
		String code,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		return problem;
	}
}
