package app.ziji.sync.interfaces;

import java.net.URI;

import app.ziji.sync.application.SyncQueryPersistenceException;
import app.ziji.sync.application.SyncQueryValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 同步查询只暴露稳定错误码，不回显游标、SQL、recipient 或持久化 payload。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SyncApiExceptionHandler {

	@ExceptionHandler(SyncQueryValidationException.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(SyncQueryPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
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
