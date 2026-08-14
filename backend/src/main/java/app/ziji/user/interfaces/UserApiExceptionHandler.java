package app.ziji.user.interfaces;

import java.net.URI;
import java.util.Map;

import app.ziji.user.application.UserAuthenticationException;
import app.ziji.user.application.UserPersistenceException;
import app.ziji.user.application.UserValidationException;
import app.ziji.user.application.UserVersionConflictException;
import app.ziji.user.domain.UserDomainException;
import app.ziji.user.domain.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 用户资料错误映射；冲突只返回有界版本信息，不嵌入当前资源或敏感字段。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class UserApiExceptionHandler {

	@ExceptionHandler(UserValidationException.class)
	ProblemDetail validation(
		UserValidationException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(UserDomainException.class)
	ProblemDetail domainValidation(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(UserAuthenticationException.class)
	ProblemDetail authentication(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.UNAUTHORIZED, "需要认证", "AUTHENTICATION_REQUIRED", request, response);
	}

	@ExceptionHandler(UserVersionConflictException.class)
	ProblemDetail versionConflict(
		UserVersionConflictException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		UserProfile current = exception.current();
		ProblemDetail problem = base(
			HttpStatus.CONFLICT, "资源版本冲突", "VERSION_CONFLICT", request, response);
		problem.setProperty("versionConflict", Map.of(
			"currentVersion", current.version(),
			"currentEtag", current.etag(),
			"resourceLocation", "/api/v1/users/me"));
		return problem;
	}

	@ExceptionHandler(UserPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
	}

	private ProblemDetail base(
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
		// 只读取过滤器写入响应的归一化 ID，避免回显未经校验的请求头。
		String requestId = response.getHeader("X-Request-ID");
		if (requestId == null || requestId.isBlank()) {
			requestId = "unknown";
		}
		problem.setProperty("requestId", requestId);
		return problem;
	}
}
