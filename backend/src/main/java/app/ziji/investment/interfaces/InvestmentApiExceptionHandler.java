package app.ziji.investment.interfaces;

import java.net.URI;

import app.ziji.investment.application.InvestmentBusinessRuleException;
import app.ziji.investment.application.InvestmentNotVisibleException;
import app.ziji.investment.application.InvestmentPermissionDeniedException;
import app.ziji.investment.application.InvestmentPersistenceException;
import app.ziji.investment.application.InvestmentRequestValidationException;
import app.ziji.investment.domain.InvestmentDomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 投资 API 只暴露稳定错误码；不可见资源与不存在资源共用 404。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class InvestmentApiExceptionHandler {

	@ExceptionHandler(InvestmentRequestValidationException.class)
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(InvestmentBusinessRuleException.class)
	ProblemDetail businessRule(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", request, response);
	}

	@ExceptionHandler(InvestmentDomainException.class)
	ProblemDetail domainRule(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", request, response);
	}

	@ExceptionHandler(InvestmentNotVisibleException.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(InvestmentPermissionDeniedException.class)
	ProblemDetail permissionDenied(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", request, response);
	}

	@ExceptionHandler(InvestmentApiProblemException.class)
	ResponseEntity<ProblemDetail> apiProblem(
		InvestmentApiProblemException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail detail = problem(exception.status(), exception.code(), request, response);
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(exception.status());
		if (exception.retryAfter()) {
			builder.header("Retry-After", "5");
		}
		return builder.body(detail);
	}

	@ExceptionHandler(InvestmentPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response);
	}

	private ProblemDetail problem(
		HttpStatus status,
		String code,
		HttpServletRequest request,
		HttpServletResponse response) {
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
