package app.ziji.marketdata.interfaces;

import java.net.URI;

import app.ziji.marketdata.application.MarketDataConflictException;
import app.ziji.marketdata.application.MarketDataNotFoundException;
import app.ziji.marketdata.application.MarketDataPersistenceException;
import app.ziji.marketdata.application.MarketDataRetryableException;
import app.ziji.marketdata.application.MarketDataValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 市场数据错误只暴露稳定 code，不回显数据库或供应商原始消息。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MarketDataApiExceptionHandler {

	@ExceptionHandler(MarketDataValidationException.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(MarketDataNotFoundException.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(MarketDataConflictException.class)
	ProblemDetail conflict(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.CONFLICT, "CONFLICT", request, response);
	}

	@ExceptionHandler(MarketDataRetryableException.class)
	ProblemDetail retryable(HttpServletRequest request, HttpServletResponse response) {
		ProblemDetail detail = problem(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", request, response);
		detail.setProperty("retryAfterSeconds", 5);
		return detail;
	}

	@ExceptionHandler(MarketDataPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response);
	}

	private ProblemDetail problem(HttpStatus status, String code, HttpServletRequest request, HttpServletResponse response) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
		problem.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		String requestId = response.getHeader("X-Request-ID");
		problem.setProperty("requestId", requestId == null || requestId.isBlank() ? "unknown" : requestId);
		return problem;
	}
}
