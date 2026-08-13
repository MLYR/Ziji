package app.ziji;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest
@ActiveProfiles("test")
class OpenApiRouteCoverageTests extends PostgresIntegrationTestSupport {

	private static final Set<String> HTTP_METHODS = Set.of(
		"get", "put", "post", "delete", "options", "head", "patch", "trace");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	// 必须选择业务 MVC HandlerMapping，避免 Actuator 的 controllerEndpointHandlerMapping 干扰。
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void everyContractOperationIsImplementedOrExplicitlyPending() throws IOException {
		List<OperationCoverageValidator.ContractOperation> contractOperations = readContractOperations();
		List<String> unimplementedOperationIds = readUnimplementedOperationIds();
		List<OperationCoverageValidator.ActualRoute> actualRoutes = readActualBusinessRoutes();

		// 当前无业务 Controller 时，96 个契约操作仍必须逐项出现在显式清单，不能靠空集合放行。
		new OperationCoverageValidator().validate(
			contractOperations, actualRoutes, unimplementedOperationIds);
	}

	private List<OperationCoverageValidator.ContractOperation> readContractOperations() throws IOException {
		Path contract = locateContract();
		Map<String, Object> document;
		try (InputStream input = Files.newInputStream(contract)) {
			document = new Yaml().load(input);
		}

		String serverBasePath = readServerBasePath(document);
		List<OperationCoverageValidator.ContractOperation> operationsById = new ArrayList<>();
		Map<String, Object> paths = objectMap(document.get("paths"), "OpenAPI paths");
		for (Map.Entry<String, Object> path : paths.entrySet()) {
			Map<String, Object> operations = objectMap(path.getValue(), "OpenAPI path " + path.getKey());
			for (Map.Entry<String, Object> operation : operations.entrySet()) {
				if (!HTTP_METHODS.contains(operation.getKey().toLowerCase(Locale.ROOT))) {
					continue;
				}
				Map<String, Object> definition = objectMap(operation.getValue(),
					operation.getKey() + " " + path.getKey());
				Object operationId = definition.get("operationId");
				if (!(operationId instanceof String id) || id.isBlank()) {
					throw new IllegalStateException("OpenAPI 操作缺少 operationId: "
						+ operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
				}
				operationsById.add(new OperationCoverageValidator.ContractOperation(
					id, operation.getKey().toUpperCase(Locale.ROOT), normalizePath(serverBasePath + path.getKey())));
			}
		}
		return operationsById;
	}

	private List<String> readUnimplementedOperationIds() throws IOException {
		try (InputStream input = getClass().getResourceAsStream("/contract/unimplemented-operation-ids.txt")) {
			if (input == null) {
				throw new IllegalStateException("未找到明确未实现 operationId 清单");
			}
			return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();
		}
	}

	private List<OperationCoverageValidator.ActualRoute> readActualBusinessRoutes() {
		List<OperationCoverageValidator.ActualRoute> routes = new ArrayList<>();
		handlerMapping.getHandlerMethods().entrySet().stream()
			.filter(entry -> isBusinessController(entry.getValue().getBeanType()))
			.forEach(entry -> {
				RequestMappingInfo mapping = entry.getKey();
				Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
				Set<String> patterns = mapping.getPatternValues();
				// 一个 operationId 只允许一个明确 HTTP method/path；组合映射必须拆成独立 handler。
				if (methods.size() != 1 || patterns.size() != 1) {
					throw new IllegalStateException("业务路由必须且只能声明一个 HTTP method 和 path: "
						+ describe(mapping, entry.getValue().getBeanType().getName()));
				}
				routes.add(new OperationCoverageValidator.ActualRoute(
					mapping.getName(), methods.iterator().next().name(),
					normalizePath(patterns.iterator().next()),
					describe(mapping, entry.getValue().getBeanType().getName())));
			});
		return routes;
	}

	private boolean isBusinessController(Class<?> beanType) {
		// 只审计项目业务 Controller，Actuator、错误处理器及框架端点均不属于 OpenAPI 业务契约。
		return beanType.getPackageName().startsWith("app.ziji")
			&& AnnotatedElementUtils.hasAnnotation(beanType, Controller.class);
	}

	private String describe(RequestMappingInfo mapping, String controller) {
		return mapping.getMethodsCondition().getMethods() + " " + mapping.getPatternValues() + " (" + controller + ")";
	}

	private String readServerBasePath(Map<String, Object> document) {
		Object serversValue = document.get("servers");
		if (!(serversValue instanceof List<?> servers) || servers.size() != 1) {
			throw new IllegalStateException("OpenAPI 必须声明唯一 servers base path");
		}
		Map<String, Object> server = objectMap(servers.getFirst(), "OpenAPI server");
		Object url = server.get("url");
		if (!(url instanceof String basePath) || !basePath.startsWith("/")
			|| basePath.contains("{") || basePath.contains("?") || basePath.contains("#")) {
			throw new IllegalStateException("OpenAPI server url 必须是无变量的相对 base path: " + url);
		}
		return normalizePath(basePath);
	}

	private String normalizePath(String path) {
		String normalized = path.replaceAll("/{2,}", "/");
		return normalized.length() > 1 && normalized.endsWith("/")
			? normalized.substring(0, normalized.length() - 1)
			: normalized;
	}

	private Path locateContract() throws IOException {
		for (Path candidate : List.of(Path.of("../openapi/ziji-v1.yaml"), Path.of("openapi/ziji-v1.yaml"))) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toRealPath();
			}
		}
		throw new IllegalStateException("未找到 openapi/ziji-v1.yaml；请从仓库根目录或 backend 目录运行测试");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> objectMap(Object value, String label) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalStateException(label + " 必须是对象");
		}
		return new LinkedHashMap<>((Map<String, Object>) map);
	}
}
