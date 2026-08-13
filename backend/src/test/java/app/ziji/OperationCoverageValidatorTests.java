package app.ziji;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCoverageValidatorTests {

	private final OperationCoverageValidator validator = new OperationCoverageValidator();
	private static final OperationCoverageValidator.ContractOperation IMPLEMENTED =
		new OperationCoverageValidator.ContractOperation("implemented", "GET", "/api/v1/example");
	private static final OperationCoverageValidator.ContractOperation PENDING =
		new OperationCoverageValidator.ContractOperation("pending", "POST", "/api/v1/pending");

	@Test
	void acceptsExactlyPartitionedOperations() {
		assertDoesNotThrow(() -> validator.validate(
			List.of(IMPLEMENTED, PENDING),
			List.of(new OperationCoverageValidator.ActualRoute(
				"implemented", "GET", "/api/v1/example", "ExampleController#get")),
			List.of("pending")));
	}

	@Test
	void rejectsMissingContractOperation() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(List.of(IMPLEMENTED,
				new OperationCoverageValidator.ContractOperation("missing", "GET", "/api/v1/missing")),
				List.of(new OperationCoverageValidator.ActualRoute(
					"implemented", "GET", "/api/v1/example", "ExampleController#get")),
				List.of()));
		assertTrue(error.getMessage().contains("未追踪"));
	}

	@Test
	void rejectsDuplicateAndUnknownTrackingItems() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(
				List.of(new OperationCoverageValidator.ContractOperation("known", "GET", "/api/v1/known")),
				List.of(), List.of("unknown", "unknown")));
		assertTrue(error.getMessage().contains("重复"));
		assertTrue(error.getMessage().contains("契约外"));
	}

	@Test
	void rejectsBusinessRouteWithoutOperationId() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(List.of(PENDING),
				List.of(new OperationCoverageValidator.ActualRoute(
					null, "POST", "/api/v1/untracked", "UntrackedController#post")),
				List.of("pending")));
		assertTrue(error.getMessage().contains("缺少 @RequestMapping name"));
	}

	@Test
	void rejectsImplementedOperationStillListedAsPending() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(
				List.of(new OperationCoverageValidator.ContractOperation("operation", "GET", "/api/v1/example")),
				List.of(new OperationCoverageValidator.ActualRoute(
					"operation", "GET", "/api/v1/example", "ExampleController#get")),
				List.of("operation")));
		assertTrue(error.getMessage().contains("同时标记"));
	}

	@Test
	void rejectsCorrectOperationIdWithWrongMethod() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(List.of(IMPLEMENTED),
				List.of(new OperationCoverageValidator.ActualRoute(
					"implemented", "POST", "/api/v1/example", "ExampleController#post")),
				List.of()));
		assertTrue(error.getMessage().contains("期望 GET /api/v1/example"));
		assertTrue(error.getMessage().contains("实际 POST /api/v1/example"));
	}

	@Test
	void rejectsCorrectOperationIdAndMethodWithWrongPath() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(List.of(IMPLEMENTED),
				List.of(new OperationCoverageValidator.ActualRoute(
					"implemented", "GET", "/api/v1/wrong", "ExampleController#get")),
				List.of()));
		assertTrue(error.getMessage().contains("期望 GET /api/v1/example"));
		assertTrue(error.getMessage().contains("实际 GET /api/v1/wrong"));
	}

	@Test
	void rejectsOneOperationIdMappedToMultipleRoutes() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> validator.validate(List.of(IMPLEMENTED), List.of(
				new OperationCoverageValidator.ActualRoute(
					"implemented", "GET", "/api/v1/example", "ExampleController#get"),
				new OperationCoverageValidator.ActualRoute(
					"implemented", "GET", "/api/v1/example-alias", "ExampleController#getAlias")),
				List.of()));
		assertTrue(error.getMessage().contains("实际业务路由 operationId 重复"));
	}
}
