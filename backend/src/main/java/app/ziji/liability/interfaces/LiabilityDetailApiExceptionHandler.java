package app.ziji.liability.interfaces;

import java.net.URI;
import java.util.Map;

import app.ziji.liability.application.LiabilityDetailApplicationException;
import app.ziji.liability.domain.LiabilityDetailException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 负债详情稳定 Problem 映射；所有失败分支不写成功 ETag。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LiabilityDetailApiExceptionHandler {

	@ExceptionHandler(LiabilityDetailException.Validation.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(LiabilityDetailException.BusinessRule.class)
	ProblemDetail businessRule(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.UNPROCESSABLE_ENTITY, "请求违反业务规则", "BUSINESS_RULE_VIOLATION", request, response);
	}

	@ExceptionHandler(LiabilityDetailApplicationException.NotFound.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.NOT_FOUND, "资源不存在", "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(LiabilityDetailApplicationException.PermissionDenied.class)
	ProblemDetail permission(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.FORBIDDEN, "没有操作权限", "PERMISSION_DENIED", request, response);
	}

	@ExceptionHandler(LiabilityDetailApplicationException.VersionConflict.class)
	ProblemDetail versionConflict(
		LiabilityDetailApplicationException.VersionConflict exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail problem = base(HttpStatus.CONFLICT, "资源版本冲突", "VERSION_CONFLICT", request, response);
		problem.setProperty("versionConflict", Map.of(
			"currentVersion", exception.current().version(),
			"currentEtag", exception.current().etag(),
			"resourceLocation", request.getRequestURI()));
		return problem;
	}

	@ExceptionHandler(LiabilityDetailApplicationException.IdempotencyKeyReused.class)
	ProblemDetail keyReused(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.CONFLICT, "幂等键已复用", "IDEMPOTENCY_KEY_REUSED", request, response);
	}

	@ExceptionHandler(LiabilityDetailApplicationException.IdempotencyInProgress.class)
	ProblemDetail inProgress(HttpServletRequest request, HttpServletResponse response) {
		response.setHeader("Retry-After", "5");
		return base(HttpStatus.CONFLICT, "请求仍在处理中", "IDEMPOTENCY_REQUEST_IN_PROGRESS", request, response);
	}

	@ExceptionHandler({
		LiabilityDetailApplicationException.SafeReplayUnavailable.class,
		LiabilityDetailApplicationException.Persistence.class})
	ProblemDetail internal(HttpServletRequest request, HttpServletResponse response) {
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
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		return problem;
	}
}
