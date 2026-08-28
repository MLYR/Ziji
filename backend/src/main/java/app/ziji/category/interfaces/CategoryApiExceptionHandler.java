package app.ziji.category.interfaces;

import java.net.URI;
import java.util.Map;

import app.ziji.category.application.CategoryNameConflictException;
import app.ziji.category.application.CategoryNotVisibleException;
import app.ziji.category.application.CategoryPermissionDeniedException;
import app.ziji.category.application.CategoryPersistenceException;
import app.ziji.category.application.CategoryValidationException;
import app.ziji.category.application.CategoryVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 分类 HTTP 边界稳定错误映射。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CategoryApiExceptionHandler {

	@ExceptionHandler(CategoryValidationException.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(CategoryNotVisibleException.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.NOT_FOUND, "资源不存在", "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(CategoryPermissionDeniedException.class)
	ProblemDetail permissionDenied(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.FORBIDDEN, "没有操作权限", "PERMISSION_DENIED", request, response);
	}

	@ExceptionHandler(CategoryNameConflictException.class)
	ProblemDetail nameConflict(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.CONFLICT, "分类名称已存在", "CATEGORY_NAME_ALREADY_EXISTS", request, response);
	}

	@ExceptionHandler(CategoryVersionConflictException.class)
	ProblemDetail versionConflict(
		CategoryVersionConflictException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail problem = base(HttpStatus.CONFLICT, "资源版本冲突", "VERSION_CONFLICT", request, response);
		problem.setProperty("versionConflict", Map.of(
			"currentVersion", exception.currentVersion(),
			"currentEtag", "\"" + exception.currentVersion() + "\"",
			"resourceLocation", request.getRequestURI()));
		return problem;
	}

	@ExceptionHandler(CategoryPersistenceException.class)
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
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		return problem;
	}
}
