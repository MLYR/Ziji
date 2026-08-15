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
import org.springframework.http.converter.HttpMessageNotReadableException;

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
		ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request);
		problem.setProperty("fieldErrors", errors);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadableRequest(HttpMessageNotReadableException exception, HttpServletRequest request) {
		// JSON 格式或类型错误在控制器前短路，不能误映射为内部异常或回显原始载荷。
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request);
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
		// 不记录异常消息或堆栈，避免驱动异常把密码、Token、摘要或 SQL 参数写入日志。
		LOGGER.error("Unhandled request failure type={}", exception.getClass().getSimpleName());
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
