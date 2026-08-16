package app.ziji.sync.interfaces;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.ziji.sync.application.SyncChange;
import app.ziji.sync.application.SyncChangePage;
import app.ziji.sync.application.SyncChangeQueryService;
import app.ziji.sync.application.SyncQueryValidationException;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前认证用户的定向同步读取边界；不接受账户或用户筛选参数。 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncChangeController {

	private final SyncChangeQueryService queries;
	private final CurrentUserIdResolver currentUserIdResolver;

	public SyncChangeController(SyncChangeQueryService queries, CurrentUserIdResolver currentUserIdResolver) {
		this.queries = queries;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(path = "/changes", name = "listSyncChanges")
	public ResponseEntity<SyncChangeListEnvelope> listSyncChanges(
		@RequestParam(name = "cursor", required = false) String cursor,
		@RequestParam(name = "limit", required = false) String rawLimit,
		Principal principal,
		HttpServletResponse response) {
		UUID userId = currentUserIdResolver.resolve(principal);
		SyncChangePage page = queries.list(userId, parseLimit(rawLimit), cursor);
		return ResponseEntity.ok(new SyncChangeListEnvelope(
			page.changes().stream().map(SyncChangeController::view).toList(),
			new PageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	private Integer parseLimit(String rawLimit) {
		if (rawLimit == null) {
			return null;
		}
		if (!rawLimit.matches("[1-9][0-9]*")) {
			throw new SyncQueryValidationException();
		}
		try {
			return Integer.valueOf(rawLimit);
		} catch (NumberFormatException exception) {
			throw new SyncQueryValidationException();
		}
	}

	private static Map<String, Object> view(SyncChange change) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("sequence", change.sequence());
		view.put("entityType", change.entityType());
		view.put("entityId", change.entityId());
		view.put("entityVersion", change.entityVersion());
		view.put("changeType", change.changeType());
		view.put("payloadVersion", change.payloadVersion());
		// NULL payload 必须省略，避免把契约中的可选对象序列化成 JSON null。
		if (change.payload() != null) {
			view.put("payload", change.payload());
		}
		return view;
	}

	private String requestId(HttpServletResponse response) {
		String requestId = response.getHeader("X-Request-ID");
		return requestId == null || requestId.isBlank() ? "unknown" : requestId;
	}

	public record SyncChangeListEnvelope(List<Map<String, Object>> data, PageMeta meta) {
		public SyncChangeListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record PageMeta(String requestId, String nextCursor, boolean hasMore) {
	}
}
