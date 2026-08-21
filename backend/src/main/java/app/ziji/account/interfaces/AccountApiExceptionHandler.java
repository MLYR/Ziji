package app.ziji.account.interfaces;

import java.net.URI;
import java.util.Map;

import app.ziji.account.application.AccountNotVisibleException;
import app.ziji.account.application.AccountArchiveException;
import app.ziji.account.application.AccountPermissionDeniedException;
import app.ziji.account.application.AccountPersistenceException;
import app.ziji.account.application.AccountCreationException;
import app.ziji.account.application.AccountQueryValidationException;
import app.ziji.account.application.AccountVersionConflictException;
import app.ziji.account.application.LiquidityHoldException;
import app.ziji.account.domain.AccountDomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 账户 HTTP 边界的稳定错误映射；冲突只返回有界版本信息，不嵌入当前资源。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AccountApiExceptionHandler {

	@ExceptionHandler({
		AccountCreationException.class, AccountQueryValidationException.class, AccountDomainException.class,
		LiquidityHoldException.Validation.class, AccountArchiveException.Validation.class})
	ProblemDetail validation(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.BAD_REQUEST, "请求校验失败", "VALIDATION_ERROR", request, response);
	}

	@ExceptionHandler(AccountNotVisibleException.class)
	ProblemDetail notFound(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.NOT_FOUND, "资源不存在", "RESOURCE_NOT_FOUND", request, response);
	}

	@ExceptionHandler(AccountPermissionDeniedException.class)
	ProblemDetail permissionDenied(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.FORBIDDEN, "没有操作权限", "PERMISSION_DENIED", request, response);
	}

	@ExceptionHandler(AccountVersionConflictException.class)
	ProblemDetail versionConflict(
		AccountVersionConflictException exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail problem = base(
			HttpStatus.CONFLICT, "资源版本冲突", "VERSION_CONFLICT", request, response);
		problem.setProperty("versionConflict", Map.of(
			"currentVersion", exception.current().version(),
			"currentEtag", exception.current().etag(),
			"resourceLocation", request.getRequestURI()));
		return problem;
	}

	@ExceptionHandler(AccountPersistenceException.class)
	ProblemDetail persistence(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
	}

	@ExceptionHandler(AccountArchiveException.AlreadyArchived.class)
	ProblemDetail alreadyArchived(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.CONFLICT, "账户已经归档", "ACCOUNT_ALREADY_ARCHIVED", request, response);
	}

	@ExceptionHandler(AccountArchiveException.NonZeroBalanceConfirmationRequired.class)
	ProblemDetail nonZeroBalanceConfirmationRequired(HttpServletRequest request, HttpServletResponse response) {
		return base(
			HttpStatus.UNPROCESSABLE_ENTITY,
			"非零余额归档需要显式确认",
			"NON_ZERO_BALANCE_CONFIRMATION_REQUIRED",
			request,
			response);
	}

	@ExceptionHandler({
		AccountArchiveException.SafeReplayUnavailable.class,
		AccountArchiveException.Persistence.class})
	ProblemDetail archiveInternalError(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
	}

	@ExceptionHandler(LiquidityHoldException.BusinessRule.class)
	ProblemDetail businessRule(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.UNPROCESSABLE_ENTITY, "请求违反业务规则", "BUSINESS_RULE_VIOLATION", request, response);
	}

	@ExceptionHandler(LiquidityHoldException.VersionConflict.class)
	ProblemDetail liquidityHoldVersionConflict(
		LiquidityHoldException.VersionConflict exception,
		HttpServletRequest request,
		HttpServletResponse response) {
		ProblemDetail problem = base(HttpStatus.CONFLICT, "资源版本冲突", "VERSION_CONFLICT", request, response);
		problem.setProperty("versionConflict", Map.of(
			"currentVersion", exception.current().version(),
			"currentEtag", exception.current().etag(),
			"resourceLocation", "/api/v1/accounts/" + exception.current().accountId() + "/liquidity-holds"));
		return problem;
	}

	@ExceptionHandler(LiquidityHoldException.SafeReplayUnavailable.class)
	ProblemDetail liquidityHoldSafeReplayUnavailable(HttpServletRequest request, HttpServletResponse response) {
		return base(HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败", "INTERNAL_ERROR", request, response);
	}

	@ExceptionHandler(LiquidityHoldException.Persistence.class)
	ProblemDetail liquidityHoldPersistence(HttpServletRequest request, HttpServletResponse response) {
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
