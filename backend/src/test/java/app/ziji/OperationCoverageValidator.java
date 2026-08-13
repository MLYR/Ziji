package app.ziji;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class OperationCoverageValidator {

	record ContractOperation(String operationId, String method, String path) {
	}

	record ActualRoute(String operationId, String method, String path, String description) {
	}

	void validate(List<ContractOperation> contractOperations, List<ActualRoute> actualRoutes,
		List<String> unimplementedOperationIds) {
		List<String> errors = new ArrayList<>();
		List<String> contractOperationIds = contractOperations.stream()
			.map(ContractOperation::operationId)
			.toList();
		Set<String> contract = new LinkedHashSet<>(contractOperationIds);
		Map<String, ContractOperation> contractById = contractOperations.stream()
			.collect(Collectors.toMap(ContractOperation::operationId, Function.identity(), (left, right) -> left));
		Set<String> unimplemented = new LinkedHashSet<>(unimplementedOperationIds);

		addDuplicates("OpenAPI operationId 重复", contractOperationIds, errors);
		addDuplicates("未实现清单 operationId 重复", unimplementedOperationIds, errors);

		List<ActualRoute> unnamedRoutes = actualRoutes.stream()
			.filter(route -> route.operationId() == null || route.operationId().isBlank())
			.toList();
		if (!unnamedRoutes.isEmpty()) {
			errors.add("业务路由缺少 @RequestMapping name(operationId): "
				+ unnamedRoutes.stream().map(ActualRoute::description).sorted().toList());
		}

		List<String> actualOperationIds = actualRoutes.stream()
			.map(ActualRoute::operationId)
			.filter(operationId -> operationId != null && !operationId.isBlank())
			.toList();
		addDuplicates("实际业务路由 operationId 重复", actualOperationIds, errors);
		Set<String> actual = new LinkedHashSet<>(actualOperationIds);

		Set<String> unknownActual = difference(actual, contract);
		if (!unknownActual.isEmpty()) {
			errors.add("实际业务路由包含契约外 operationId: " + unknownActual);
		}
		Set<String> unknownUnimplemented = difference(unimplemented, contract);
		if (!unknownUnimplemented.isEmpty()) {
			errors.add("未实现清单包含契约外 operationId: " + unknownUnimplemented);
		}

		actualRoutes.stream()
			.filter(route -> route.operationId() != null && contractById.containsKey(route.operationId()))
			.forEach(route -> {
				ContractOperation expected = contractById.get(route.operationId());
				if (!expected.method().equals(route.method()) || !expected.path().equals(route.path())) {
					errors.add("业务路由与 OpenAPI 不一致: " + route.operationId()
						+ " 期望 " + expected.method() + " " + expected.path()
						+ "，实际 " + route.method() + " " + route.path()
						+ " (" + route.description() + ")");
				}
			});

		Set<String> overlap = new LinkedHashSet<>(actual);
		overlap.retainAll(unimplemented);
		if (!overlap.isEmpty()) {
			errors.add("operationId 同时标记为已实现和未实现: " + overlap);
		}

		Set<String> tracked = new HashSet<>(actual);
		tracked.addAll(unimplemented);
		Set<String> missing = difference(contract, tracked);
		if (!missing.isEmpty()) {
			errors.add("OpenAPI operationId 未追踪: " + missing);
		}

		if (!errors.isEmpty()) {
			throw new IllegalStateException(String.join(System.lineSeparator(), errors));
		}
	}

	private void addDuplicates(String label, List<String> values, List<String> errors) {
		List<String> duplicates = values.stream()
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
			.entrySet().stream()
			.filter(entry -> entry.getValue() > 1)
			.map(java.util.Map.Entry::getKey)
			.sorted()
			.toList();
		if (!duplicates.isEmpty()) {
			errors.add(label + ": " + duplicates);
		}
	}

	private Set<String> difference(Set<String> left, Set<String> right) {
		Set<String> result = new LinkedHashSet<>(left);
		result.removeAll(right);
		return result;
	}
}
