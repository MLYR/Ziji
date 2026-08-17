package app.ziji.ledger.interfaces;

import java.net.URI;

import app.ziji.ledger.application.LedgerPersistenceException;
import app.ziji.ledger.application.TransactionNotVisibleException;
import app.ziji.ledger.application.TransactionQueryValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ledger 读取错误统一映射；不可见资源与不存在资源共用 404，避免存在性泄漏。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TransactionApiExceptionHandler {

	@ExceptionHandler(TransactionQueryValidationException.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(TransactionNotVisibleException.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.NOT_FOUND, "资源不存在", "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(LedgerPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
	}

	private ProblemDetail problem(HttpStatus status, String detail, String code,
		HttpServletRequest request, HttpServletResponse response) {
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
