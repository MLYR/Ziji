package app.ziji.ledger.interfaces;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import app.ziji.ledger.application.TransactionPage;
import app.ziji.ledger.application.TransactionQuery;
import app.ziji.ledger.application.TransactionQueryReadPort.TransactionSnapshot;
import app.ziji.ledger.application.TransactionQueryService;
import app.ziji.ledger.application.TransactionQueryValidationException;
import app.ziji.ledger.domain.LedgerEntry;
import app.ziji.ledger.domain.TransactionType;
import app.ziji.user.application.CurrentUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 交易读取 HTTP 边界；所有查询参数先转换为类型化 Ledger application query。 */
@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

	private final TransactionQueryService useCase;
	private final CurrentUserIdResolver currentUserIdResolver;

	public TransactionController(TransactionQueryService useCase, CurrentUserIdResolver currentUserIdResolver) {
		this.useCase = useCase;
		this.currentUserIdResolver = currentUserIdResolver;
	}

	@GetMapping(name = "listTransactions")
	public ResponseEntity<TransactionListEnvelope> listTransactions(
		@RequestParam(name = "accountId", required = false) String rawAccountId,
		@RequestParam(name = "type", required = false) String rawType,
		@RequestParam(name = "dateFrom", required = false) String rawDateFrom,
		@RequestParam(name = "dateTo", required = false) String rawDateTo,
		@RequestParam(name = "categoryId", required = false) String rawCategoryId,
		@RequestParam(name = "limit", required = false) String rawLimit,
		@RequestParam(name = "cursor", required = false) String cursor,
		Principal principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		assertSingle(request, "accountId", "type", "dateFrom", "dateTo", "categoryId", "limit", "cursor");
		TransactionPage page = useCase.list(
			currentUserIdResolver.resolve(principal),
			new TransactionQuery(parseUuid(rawAccountId), parseType(rawType), parseDate(rawDateFrom), parseDate(rawDateTo), parseUuid(rawCategoryId)),
			parseLimit(rawLimit), cursor);
		return ResponseEntity.ok(new TransactionListEnvelope(
			page.transactions().stream().map(TransactionController::view).toList(),
			new TransactionPageMeta(requestId(response), page.nextCursor(), page.hasMore())));
	}

	@GetMapping(path = "/{transactionId}", name = "getTransaction")
	public ResponseEntity<TransactionEnvelope> getTransaction(
		@PathVariable String transactionId,
		Principal principal,
		HttpServletResponse response) {
		TransactionSnapshot snapshot = useCase.get(currentUserIdResolver.resolve(principal), parseUuid(transactionId));
		return ResponseEntity.ok().eTag(etag(snapshot.entityVersion()))
			.body(new TransactionEnvelope(view(snapshot), new ResponseMeta(requestId(response))));
	}

	private void assertSingle(HttpServletRequest request, String... names) {
		for (String name : names) {
			String[] values = request.getParameterValues(name);
			if (values != null && values.length != 1) {
				throw invalid();
			}
		}
	}

	private Integer parseLimit(String value) {
		if (value == null) {
			return null;
		}
		if (!value.matches("[1-9][0-9]*")) {
			throw invalid();
		}
		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private UUID parseUuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private TransactionType parseType(String value) {
		if (value == null) {
			return null;
		}
		try {
			return TransactionType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private LocalDate parseDate(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			throw invalid();
		}
	}

	private TransactionQueryValidationException invalid() {
		return new TransactionQueryValidationException();
	}

	/** 读写两个 HTTP operation 共用同一 OpenAPI Transaction 映射，避免响应字段漂移。 */
	static TransactionView view(TransactionSnapshot snapshot) {
		var transaction = snapshot.transaction();
		return new TransactionView(
			transaction.transactionId(), transaction.type().name(), transaction.status().name(), transaction.businessAt(),
			transaction.businessDate(), transaction.timezone().getId(), transaction.source().name(),
			transaction.rootTransactionId(), transaction.previousVersionId(), transaction.reversalOfId(),
			transaction.versionNo(), snapshot.entityVersion(), transaction.entries().stream().map(TransactionController::entry).toList());
	}

	private static LedgerEntryView entry(LedgerEntry entry) {
		return new LedgerEntryView(entry.entryId(), entry.ledgerAccountId(), entry.sequenceNo(),
			entry.direction() == app.ziji.ledger.domain.LedgerDirection.DEBIT ? "D" : "C",
			// 数据库 NUMERIC 保留计算精度；API 按币种入账精度输出，避免响应出现 schema 不允许的尾随小数。
			entry.amount().amount().setScale(entry.currency().minorUnits()).toPlainString(),
			entry.currency().name(), entry.businessDate());
	}

	private String etag(int version) {
		return "\"" + version + "\"";
	}

	private String requestId(HttpServletResponse response) {
		String value = response.getHeader("X-Request-ID");
		return value == null || value.isBlank() ? "unknown" : value;
	}

	public record TransactionListEnvelope(List<TransactionView> data, TransactionPageMeta meta) {
		public TransactionListEnvelope {
			data = List.copyOf(data);
		}
	}

	public record TransactionEnvelope(TransactionView data, ResponseMeta meta) {}

	public record TransactionPageMeta(String requestId, String nextCursor, boolean hasMore) {}

	public record ResponseMeta(String requestId) {}

	public record TransactionView(
		UUID id,
		String type,
		String status,
		java.time.Instant businessAt,
		LocalDate businessDate,
		String timezone,
		String source,
		UUID rootTransactionId,
		UUID previousVersionId,
		UUID reversalOfId,
		int versionNo,
		int version,
		List<LedgerEntryView> entries) {
		public TransactionView {
			entries = List.copyOf(entries);
		}
	}

	public record LedgerEntryView(UUID id, UUID ledgerAccountId, int sequenceNo, String direction,
		String amount, String currency, LocalDate businessDate) {}
}
