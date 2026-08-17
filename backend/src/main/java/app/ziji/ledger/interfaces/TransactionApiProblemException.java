package app.ziji.ledger.interfaces;

import app.ziji.shared.application.IdempotencyResponse;
import org.springframework.http.HttpStatus;

/** 写接口已判定的安全 Problem；只携带公开错误码和可选的有界版本冲突引用。 */
final class TransactionApiProblemException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final boolean retryAfter;
	private final IdempotencyResponse.VersionConflictReference versionConflict;

	TransactionApiProblemException(HttpStatus status, String code, boolean retryAfter) {
		this(status, code, retryAfter, null);
	}

	TransactionApiProblemException(IdempotencyResponse.VersionConflictReference versionConflict) {
		this(HttpStatus.CONFLICT, "VERSION_CONFLICT", false, versionConflict);
	}

	private TransactionApiProblemException(
		HttpStatus status,
		String code,
		boolean retryAfter,
		IdempotencyResponse.VersionConflictReference versionConflict) {
		super(code);
		this.status = status;
		this.code = code;
		this.retryAfter = retryAfter;
		this.versionConflict = versionConflict;
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}

	boolean retryAfter() {
		return retryAfter;
	}

	IdempotencyResponse.VersionConflictReference versionConflict() {
		return versionConflict;
	}
}
