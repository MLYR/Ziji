package app.ziji.auth.interfaces;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.auth.application.EmailAlreadyRegisteredException;
import app.ziji.auth.application.EmailChallengeApplicationService;
import app.ziji.auth.application.EmailChallengeIssueCommand;
import app.ziji.auth.application.EmailChallengeIssueResult;
import app.ziji.auth.application.EmailRegistrationApplicationService;
import app.ziji.auth.application.EmailRegistrationResult;
import app.ziji.auth.application.LoginRateLimitedException;
import app.ziji.auth.application.PasswordLoginApplicationService;
import app.ziji.auth.application.PasswordLoginCommand;
import app.ziji.auth.application.PasswordManagementApplicationService;
import app.ziji.auth.application.RefreshTokenRejectedException;
import app.ziji.auth.application.RotateRefreshTokenCommand;
import app.ziji.auth.application.SessionTokenResult;
import app.ziji.auth.application.SourceAddressResolver;
import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.EmailChallengePurpose;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;
import app.ziji.user.application.RegisteredUserProfile;
import app.ziji.user.application.UserRegistrationReplayPort;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 注册、验证码、登录、刷新和重置的 HTTP 编排；业务规则始终委托 application service。 */
@RestController
@RequestMapping("/api/v1")
public class PublicAuthController {

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
	private static final String REGISTER_OPERATION = "registerUser";
	private static final String RESET_OPERATION = "resetPassword";
	private static final String REGISTER_RESOURCE = "/api/v1/auth/register";
	private static final String RESET_RESOURCE = "/api/v1/auth/password-reset";
	private static final String USER_LOCATION = "/api/v1/users/me";

	private final EmailChallengeApplicationService challengeService;
	private final EmailRegistrationApplicationService registrationService;
	private final PasswordLoginApplicationService passwordLoginService;
	private final app.ziji.auth.application.DeviceSessionApplicationService deviceSessionService;
	private final PasswordManagementApplicationService passwordManagementService;
	private final SourceAddressResolver sourceAddressResolver;
	private final UnifiedIdempotencyService idempotencyService;
	private final UserRegistrationReplayPort registrationReplayPort;
	private final WebSessionCookieService webSessionCookies;
	private final Clock clock;

	public PublicAuthController(
		EmailChallengeApplicationService challengeService,
		EmailRegistrationApplicationService registrationService,
		PasswordLoginApplicationService passwordLoginService,
		app.ziji.auth.application.DeviceSessionApplicationService deviceSessionService,
		PasswordManagementApplicationService passwordManagementService,
		SourceAddressResolver sourceAddressResolver,
		UnifiedIdempotencyService idempotencyService,
		UserRegistrationReplayPort registrationReplayPort,
		WebSessionCookieService webSessionCookies,
		Clock clock) {
		this.challengeService = challengeService;
		this.registrationService = registrationService;
		this.passwordLoginService = passwordLoginService;
		this.deviceSessionService = deviceSessionService;
		this.passwordManagementService = passwordManagementService;
		this.sourceAddressResolver = sourceAddressResolver;
		this.idempotencyService = idempotencyService;
		this.registrationReplayPort = registrationReplayPort;
		this.webSessionCookies = webSessionCookies;
		this.clock = clock;
	}

	@PostMapping(
		path = "/auth/registration-challenges",
		name = "createRegistrationChallenge",
		consumes = JSON,
		produces = JSON)
	public ResponseEntity<?> createRegistrationChallenge(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		AuthHttpRequests.EmailChallengeInput input = AuthHttpRequests.emailChallenge(body);
		EmailChallengeIssueResult issued = challengeService.issue(new EmailChallengeIssueCommand(
			EmailChallengePurpose.REGISTER, input.email(), input.deviceId(), sourceAddress(request)));
		if (!issued.accepted()) {
			return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", request, response, issued.retryAfterSeconds());
		}
		return ResponseEntity.accepted().body(new ChallengeEnvelope(
			new ChallengeData(issued.expiresInSeconds()), new ResponseMeta(requestId(response))));
	}

	@PostMapping(path = "/auth/register", name = "registerUser", consumes = JSON, produces = JSON)
	public ResponseEntity<?> registerUser(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		AuthHttpRequests.RegistrationInput input = AuthHttpRequests.registration(body);
		String key = idempotencyKey(request);
		String requestHash = IdempotencyRequestHasher.hash(
			"POST", JSON, REGISTER_RESOURCE, input.hashPayload(), null);
		IdempotencyExecution<RegisteredUserProfile> execution = idempotencyService.executeAnonymous(
			input.email(), 1, REGISTER_OPERATION, key, requestHash, () -> registerWork(input));
		return registrationResponse(execution, request, response);
	}

	@PostMapping(path = "/auth/web/sessions", name = "createWebSession", consumes = JSON, produces = JSON)
	public ResponseEntity<WebSessionEnvelope> createWebSession(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		// 凭据错误响应也不能被中间缓存复用到后续认证请求。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		AuthHttpRequests.LoginInput input = AuthHttpRequests.login(body);
		SessionTokenResult session = passwordLoginService.loginAndCreateSession(
			new PasswordLoginCommand(input.email(), input.password(), sourceAddress(request), clock.instant()),
			input.deviceName(), input.deviceId());
		webSessionCookies.issue(request, response, session);
		return ResponseEntity.status(HttpStatus.CREATED).body(webEnvelope(session, response));
	}

	@PostMapping(path = "/auth/web/sessions/refresh", name = "refreshWebSession", produces = JSON)
	public ResponseEntity<?> refreshWebSession(HttpServletRequest request, HttpServletResponse response) {
		// 刷新端点即使拒绝旧凭据也不得被共享缓存保存。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		String refreshToken = singleCookie(request, "ziji_refresh");
		if (refreshToken == null) {
			webSessionCookies.clear(response);
			return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request, response, null);
		}
		try {
			SessionTokenResult session = deviceSessionService.rotate(new RotateRefreshTokenCommand(refreshToken));
			webSessionCookies.issue(request, response, session);
			return ResponseEntity.ok(webEnvelope(session, response));
		} catch (RefreshTokenRejectedException exception) {
			try {
				if (exception.reason() == RefreshTokenRejectedException.Reason.CONSUMED) {
					// CONSUMED 只触发安全处置，HTTP 仍统一为未认证，避免暴露重用检测信号。
					deviceSessionService.handleConsumedRefreshTokenReuse(new RotateRefreshTokenCommand(refreshToken));
				}
			} finally {
				webSessionCookies.clear(response);
			}
			return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request, response, null);
		}
	}

	@PostMapping(path = "/auth/mobile/sessions", name = "createMobileSession", consumes = JSON, produces = JSON)
	public ResponseEntity<MobileSessionEnvelope> createMobileSession(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		// Mobile 凭据响应与失败语义同样禁止缓存，避免复用过期认证结果。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		AuthHttpRequests.LoginInput input = AuthHttpRequests.login(body);
		SessionTokenResult session = passwordLoginService.loginAndCreateSession(
			new PasswordLoginCommand(input.email(), input.password(), sourceAddress(request), clock.instant()),
			input.deviceName(), input.deviceId());
		return ResponseEntity.status(HttpStatus.CREATED).body(mobileEnvelope(session, response));
	}

	@PostMapping(path = "/auth/mobile/sessions/refresh", name = "refreshMobileSession", consumes = JSON, produces = JSON)
	public ResponseEntity<?> refreshMobileSession(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		// Mobile 刷新同样包含高敏感响应或认证失败，统一禁止缓存。
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		String refreshToken = AuthHttpRequests.mobileRefreshToken(body);
		try {
			SessionTokenResult session = deviceSessionService.rotate(new RotateRefreshTokenCommand(refreshToken));
			return ResponseEntity.ok(mobileEnvelope(session, response));
		} catch (RefreshTokenRejectedException exception) {
			if (exception.reason() == RefreshTokenRejectedException.Reason.CONSUMED) {
				deviceSessionService.handleConsumedRefreshTokenReuse(new RotateRefreshTokenCommand(refreshToken));
			}
			return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request, response, null);
		}
	}

	@PostMapping(
		path = "/auth/password-reset-challenges",
		name = "createPasswordResetChallenge",
		consumes = JSON,
		produces = JSON)
	public ResponseEntity<?> createPasswordResetChallenge(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		AuthHttpRequests.EmailChallengeInput input = AuthHttpRequests.emailChallenge(body);
		EmailChallengeIssueResult issued = challengeService.issue(new EmailChallengeIssueCommand(
			EmailChallengePurpose.RESET_PASSWORD, input.email(), input.deviceId(), sourceAddress(request)));
		if (!issued.accepted()) {
			return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", request, response, issued.retryAfterSeconds());
		}
		return ResponseEntity.accepted().body(new ChallengeEnvelope(
			new ChallengeData(issued.expiresInSeconds()), new ResponseMeta(requestId(response))));
	}

	@PostMapping(path = "/auth/password-reset", name = "resetPassword", consumes = JSON)
	public ResponseEntity<?> resetPassword(
		@RequestBody JsonNode body,
		HttpServletRequest request,
		HttpServletResponse response) {
		AuthHttpRequests.ResetInput input = AuthHttpRequests.passwordReset(body);
		String key = idempotencyKey(request);
		String requestHash = IdempotencyRequestHasher.hash(
			"POST", JSON, RESET_RESOURCE, input.hashPayload(), null);
		IdempotencyExecution<Void> execution = idempotencyService.executeAnonymous(
			input.email(), 1, RESET_OPERATION, key, requestHash, () -> resetWork(input));
		return resetResponse(execution, request, response);
	}

	private IdempotencyWorkResult<RegisteredUserProfile> registerWork(AuthHttpRequests.RegistrationInput input) {
		EmailRegistrationResult registered;
		try {
			registered = registrationService.register(input.command());
		} catch (app.ziji.auth.application.RegistrationValidationException exception) {
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinal(400, "VALIDATION_ERROR"));
		} catch (EmailAlreadyRegisteredException exception) {
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinal(409, "DUPLICATE_RESOURCE"));
		} catch (RuntimeException exception) {
			// 注册用例的 savepoint 已回滚全部业务事实，此时才允许提交可重试 5xx 终态。
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedRetryable(500, "INTERNAL_ERROR"));
		}
		// 首次响应直接使用注册用例的安全结果，避免成功写入后因额外查询失败而误提交可重试终态。
		RegisteredUserProfile profile = new RegisteredUserProfile(
			registered.userId(), registered.email(), registered.nickname(), registered.timezone(),
			registered.baseCurrency(), registered.locale(), "STANDARD", "ACTIVE", 1);
		return IdempotencyWorkResult.completed(profile, successFor(profile));
	}

	private IdempotencyWorkResult<Void> resetWork(AuthHttpRequests.ResetInput input) {
		try {
			passwordManagementService.resetPassword(input.command());
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.succeededEmpty(204));
		} catch (app.ziji.auth.application.PasswordManagementValidationException exception) {
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedFinal(400, "VALIDATION_ERROR"));
		} catch (RuntimeException exception) {
			// 重置用例的 savepoint 已回滚挑战、密码和会话事实，可安全记录固定退避的 5xx。
			return IdempotencyWorkResult.completed(null, IdempotencyResponse.failedRetryable(500, "INTERNAL_ERROR"));
		}
	}

	private ResponseEntity<?> registrationResponse(
		IdempotencyExecution<RegisteredUserProfile> execution,
		HttpServletRequest request,
		HttpServletResponse response) {
		return switch (execution.status()) {
			case EXECUTED -> execution.response() != null
				&& execution.response().status() == IdempotencyResponse.Status.SUCCEEDED
					? created(execution.value(), execution.response(), response)
					: responseForIdempotency(execution.response(), request, response);
			case REPLAYED -> replayedRegistration(execution.response(), request, response);
			case KEY_REUSED -> problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", request, response, null);
			case REQUEST_IN_PROGRESS -> problem(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", request, response,
				IdempotencyExecution.RETRY_AFTER_SECONDS);
			case SAFE_REPLAY_UNAVAILABLE -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, null);
		};
	}

	private ResponseEntity<?> resetResponse(
		IdempotencyExecution<Void> execution,
		HttpServletRequest request,
		HttpServletResponse response) {
		return switch (execution.status()) {
			case EXECUTED -> responseForIdempotency(execution.response(), request, response);
			case REPLAYED -> responseForIdempotency(execution.response(), request, response);
			case KEY_REUSED -> problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", request, response, null);
			case REQUEST_IN_PROGRESS -> problem(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", request, response,
				IdempotencyExecution.RETRY_AFTER_SECONDS);
			case SAFE_REPLAY_UNAVAILABLE -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, null);
		};
	}

	private ResponseEntity<?> replayedRegistration(
		IdempotencyResponse idempotencyResponse,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (idempotencyResponse == null || idempotencyResponse.status() != IdempotencyResponse.Status.SUCCEEDED
			|| !(idempotencyResponse.reference() instanceof IdempotencyResponse.ResourceReference reference)
			|| idempotencyResponse.resourceId() == null) {
			return responseForIdempotency(idempotencyResponse, request, response);
		}
		Optional<RegisteredUserProfile> profile = registrationReplayPort.findRegisteredUserForReplay(idempotencyResponse.resourceId());
		if (profile.isEmpty() || reference.resourceVersion() == null
			|| profile.get().version() != reference.resourceVersion()) {
			// 记录的安全资源引用无法精确重建时 fail closed，绝不重新执行注册。
			return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, null);
		}
		return created(profile.get(), idempotencyResponse, response);
	}

	private ResponseEntity<?> responseForIdempotency(
		IdempotencyResponse idempotencyResponse,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (idempotencyResponse == null) {
			return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, null);
		}
		if (idempotencyResponse.status() == IdempotencyResponse.Status.SUCCEEDED
			&& idempotencyResponse.reference() instanceof IdempotencyResponse.EmptyReference) {
			return ResponseEntity.status(idempotencyResponse.responseStatus()).build();
		}
		if (idempotencyResponse.reference() instanceof IdempotencyResponse.ProblemReference problem) {
			return problem(HttpStatus.valueOf(idempotencyResponse.responseStatus()), problem.errorCode(), request, response,
				problem.retryable() ? IdempotencyExecution.RETRY_AFTER_SECONDS : null);
		}
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, response, null);
	}

	private ResponseEntity<RegistrationEnvelope> created(
		RegisteredUserProfile profile,
		IdempotencyResponse idempotencyResponse,
		HttpServletResponse response) {
		if (profile == null || idempotencyResponse == null
			|| !(idempotencyResponse.reference() instanceof IdempotencyResponse.ResourceReference reference)) {
			throw new IllegalStateException("注册幂等成功结果无效。");
		}
		return ResponseEntity.status(idempotencyResponse.responseStatus())
			.header(HttpHeaders.LOCATION, reference.location() == null ? USER_LOCATION : reference.location())
			.eTag(reference.etag() == null ? etag(profile.version()) : reference.etag())
			.body(registrationEnvelope(profile, response));
	}

	private IdempotencyResponse successFor(RegisteredUserProfile profile) {
		return IdempotencyResponse.succeededResource(
			201, "USER", profile.id(), new IdempotencyResponse.ResourceReference(
				USER_LOCATION, etag(profile.version()), (long) profile.version()));
	}

	private app.ziji.auth.domain.SourceAddress sourceAddress(HttpServletRequest request) {
		try {
			return sourceAddressResolver.resolve(
				InetAddress.getByName(request.getRemoteAddr()), request.getHeader("Forwarded"),
				request.getHeader("X-Forwarded-For"));
		} catch (UnknownHostException | AuthDomainException exception) {
			throw new AuthHttpValidationException();
		}
	}

	private static String idempotencyKey(HttpServletRequest request) {
		Enumeration<String> values = request.getHeaders("Idempotency-Key");
		if (values == null || !values.hasMoreElements()) {
			throw new AuthHttpValidationException();
		}
		String key = values.nextElement();
		if (values.hasMoreElements() || key == null || key.length() < 16 || key.length() > 100) {
			throw new AuthHttpValidationException();
		}
		for (int index = 0; index < key.length(); index++) {
			if (Character.isISOControl(key.charAt(index))) {
				throw new AuthHttpValidationException();
			}
		}
		return key;
	}

	private static String singleCookie(HttpServletRequest request, String name) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		String value = null;
		for (Cookie cookie : cookies) {
			if (name.equals(cookie.getName())) {
				if (value != null || cookie.getValue() == null || cookie.getValue().isBlank()) {
					return null;
				}
				value = cookie.getValue();
			}
		}
		return value;
	}

	private WebSessionEnvelope webEnvelope(SessionTokenResult result, HttpServletResponse response) {
		return new WebSessionEnvelope(
			new WebSessionData(sessionView(result), result.accessToken(), expiresIn(result)), new ResponseMeta(requestId(response)));
	}

	private MobileSessionEnvelope mobileEnvelope(SessionTokenResult result, HttpServletResponse response) {
		return new MobileSessionEnvelope(
			new MobileSessionData(sessionView(result), new TokenPair(
				result.accessToken(), result.refreshToken(), expiresIn(result))), new ResponseMeta(requestId(response)));
	}

	private static SessionView sessionView(SessionTokenResult result) {
		return new SessionView(result.sessionId(), result.deviceName(), result.deviceId(), result.issuedAt(), result.lastSeenAt(), "ACTIVE");
	}

	private int expiresIn(SessionTokenResult result) {
		return Math.max(1, (int) Duration.between(clock.instant(), result.accessTokenExpiresAt()).toSeconds());
	}

	private static RegistrationEnvelope registrationEnvelope(RegisteredUserProfile profile, HttpServletResponse response) {
		return new RegistrationEnvelope(new RegistrationUserView(
			profile.id(), profile.email(), profile.nickname(), profile.timezone(), profile.baseCurrency(), profile.locale(),
			profile.amountFormat(), profile.status(), profile.version()), new ResponseMeta(requestId(response)));
	}

	private static ResponseEntity<ProblemDetail> problem(
		HttpStatus status,
		String code,
		HttpServletRequest request,
		HttpServletResponse response,
		Integer retryAfter) {
		if (retryAfter != null) {
			response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfter));
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail(code));
		problem.setType(URI.create("https://ziji.app/problems/" + code.toLowerCase().replace('_', '-')));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("requestId", requestId(response));
		return ResponseEntity.status(status).body(problem);
	}

	private static String detail(String code) {
		return switch (code) {
			case "RATE_LIMITED" -> "请求过于频繁";
			case "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_REQUEST_IN_PROGRESS" -> "请求冲突";
			case "DUPLICATE_RESOURCE" -> "资源已存在";
			case "VALIDATION_ERROR" -> "请求校验失败";
			case "AUTHENTICATION_REQUIRED" -> "需要认证";
			default -> "服务器处理请求失败";
		};
	}

	private static String etag(int version) {
		return "\"" + version + "\"";
	}

	private static String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	public record ChallengeEnvelope(ChallengeData data, ResponseMeta meta) {
	}

	public record ChallengeData(int expiresIn) {
	}

	public record RegistrationEnvelope(RegistrationUserView data, ResponseMeta meta) {
	}

	public record RegistrationUserView(
		UUID id,
		String email,
		String nickname,
		String timezone,
		String baseCurrency,
		String locale,
		String amountFormat,
		String status,
		int version) {
	}

	public record WebSessionEnvelope(WebSessionData data, ResponseMeta meta) {
	}

	public record WebSessionData(SessionView session, String accessToken, int expiresIn) {
	}

	public record MobileSessionEnvelope(MobileSessionData data, ResponseMeta meta) {
	}

	public record MobileSessionData(SessionView session, TokenPair tokens) {
	}

	public record SessionView(
		UUID id,
		String deviceName,
		String deviceId,
		java.time.Instant createdAt,
		java.time.Instant lastSeenAt,
		String status) {
	}

	public record TokenPair(String accessToken, String refreshToken, int expiresIn) {
	}

	public record ResponseMeta(String requestId) {
	}
}
