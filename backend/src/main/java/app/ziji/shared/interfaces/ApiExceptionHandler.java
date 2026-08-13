package app.ziji.shared.interfaces;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
		// 字段错误使用稳定 code，客户端无需解析本地化消息来识别错误类型。
		List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
			.map(error -> Map.of(
				"field", error.getField(),
				"code", error.getCode() == null ? "INVALID" : error.getCode(),
				"message", error.getDefaultMessage() == null ? "字段值无效" : error.getDefaultMessage()))
			.toList();
		ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_FAILED", request);
		problem.setProperty("fieldErrors", errors);
		return problem;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
		// 不向客户端泄漏异常、SQL 或内部类型；详细堆栈仍由服务端日志记录。
		LOGGER.error("Unhandled request failure", exception);
		return base(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request);
	}

	private ProblemDetail base(HttpStatus status, String detail, String code, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
		return problem;
	}
}
