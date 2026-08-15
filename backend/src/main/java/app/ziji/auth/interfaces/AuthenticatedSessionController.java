package app.ziji.auth.interfaces;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import app.ziji.auth.application.DeviceSessionPage;
import app.ziji.auth.application.DeviceSessionQueryService;
import app.ziji.auth.application.DeviceSessionSummary;
import app.ziji.auth.application.PasswordManagementApplicationService;
import app.ziji.auth.application.SessionRevocationResult;
import app.ziji.auth.application.DeviceSessionApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 当前 Bearer 会话的设备读取/撤销和改密 HTTP 编排；当前 sessionId 只来自安全链。 */
@RestController
@RequestMapping("/api/v1")
public class AuthenticatedSessionController {

	private final DeviceSessionApplicationService deviceSessionService;
	private final DeviceSessionQueryService sessionQueryService;
	private final PasswordManagementApplicationService passwordManagementService;
	private final WebSessionCookieService webSessionCookies;

	public AuthenticatedSessionController(
		DeviceSessionApplicationService deviceSessionService,
		DeviceSessionQueryService sessionQueryService,
		PasswordManagementApplicationService passwordManagementService,
		WebSessionCookieService webSessionCookies) {
		this.deviceSessionService = deviceSessionService;
		this.sessionQueryService = sessionQueryService;
		this.passwordManagementService = passwordManagementService;
		this.webSessionCookies = webSessionCookies;
	}

	@DeleteMapping(path = "/auth/sessions/current", name = "revokeCurrentSession")
	public ResponseEntity<Void> revokeCurrentSession(Authentication authentication, HttpServletResponse response) {
		AuthenticatedSessionPrincipal principal = principal(authentication);
		deviceSessionService.revokeCurrentDevice(principal.userId(), principal.sessionId());
		// 当前设备无论首次还是重复撤销都清理本机 Web Cookie，不能恢复安全状态。
		webSessionCookies.clear(response);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(path = "/users/me/password-change", name = "changePassword", consumes = "application/json")
	public ResponseEntity<Void> changePassword(@RequestBody JsonNode body, Authentication authentication) {
		AuthenticatedSessionPrincipal principal = principal(authentication);
		passwordManagementService.changePassword(AuthHttpRequests.passwordChange(body, principal.userId()));
		return ResponseEntity.noContent().build();
	}

	@GetMapping(path = "/users/me/sessions", name = "listUserSessions", produces = "application/json")
	public ResponseEntity<SessionListEnvelope> listUserSessions(
		@RequestParam(name = "limit", required = false) String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Authentication authentication,
		HttpServletResponse response) {
		AuthenticatedSessionPrincipal principal = principal(authentication);
		DeviceSessionPage page = sessionQueryService.listUserSessions(principal.userId(), parseLimit(rawLimit), cursor);
		List<SessionView> sessions = page.sessions().stream().map(AuthenticatedSessionController::view).toList();
		return ResponseEntity.ok(new SessionListEnvelope(
			sessions, new PageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@DeleteMapping(path = "/users/me/sessions", name = "revokeAllUserSessions")
	public ResponseEntity<Void> revokeAllUserSessions(Authentication authentication, HttpServletResponse response) {
		AuthenticatedSessionPrincipal principal = principal(authentication);
		deviceSessionService.revokeAllDevices(principal.userId());
		webSessionCookies.clear(response);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping(path = "/users/me/sessions/{sessionId}", name = "revokeUserSession")
	public ResponseEntity<?> revokeUserSession(
		@PathVariable String sessionId,
		Authentication authentication,
		HttpServletRequest request,
		HttpServletResponse response) {
		AuthenticatedSessionPrincipal principal = principal(authentication);
		UUID selectedSessionId = parseSessionId(sessionId);
		SessionRevocationResult result = deviceSessionService.revokeSelectedDevice(principal.userId(), selectedSessionId);
		if (result.status() == SessionRevocationResult.Status.NOT_FOUND) {
			return notFound(request, response);
		}
		if (selectedSessionId.equals(principal.sessionId())) {
			webSessionCookies.clear(response);
		}
		return ResponseEntity.noContent().build();
	}

	private static AuthenticatedSessionPrincipal principal(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedSessionPrincipal principal)
			|| !authentication.isAuthenticated()) {
			throw new AuthHttpAuthenticationException();
		}
		return principal;
	}

	private static Integer parseLimit(String rawLimit) {
		if (rawLimit == null) {
			return null;
		}
		if (!rawLimit.matches("[1-9][0-9]*")) {
			throw new AuthHttpValidationException();
		}
		try {
			return Integer.valueOf(rawLimit);
		} catch (NumberFormatException exception) {
			throw new AuthHttpValidationException();
		}
	}

	private static UUID parseSessionId(String rawSessionId) {
		try {
			return UUID.fromString(rawSessionId);
		} catch (IllegalArgumentException exception) {
			throw new AuthHttpValidationException();
		}
	}

	private static SessionView view(DeviceSessionSummary summary) {
		return new SessionView(
			summary.sessionId(), summary.deviceName(), summary.deviceId(), summary.createdAt(), summary.lastSeenAt(),
			summary.status().name());
	}

	private static ResponseEntity<ProblemDetail> notFound(HttpServletRequest request, HttpServletResponse response) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "资源不存在");
		problem.setType(URI.create("https://ziji.app/problems/resource-not-found"));
		problem.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", "RESOURCE_NOT_FOUND");
		problem.setProperty("requestId", requestId(response));
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
	}

	private static String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	public record SessionListEnvelope(List<SessionView> data, PageMeta meta) {
		public SessionListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record SessionView(
		UUID id,
		String deviceName,
		String deviceId,
		java.time.Instant createdAt,
		java.time.Instant lastSeenAt,
		String status) {
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}
}
