package app.ziji;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BE-SYNC-002 契约基线：listSyncChanges 必须声明 400 VALIDATION_ERROR 失败语义。 */
class SyncChangesOpenApiContractTests {

	@Test
	void listSyncChangesDeclaresBadRequestForInvalidCursorAndLimit() throws IOException {
		Map<String, Object> document = readContract();
		Map<String, Object> paths = objectMap(document.get("paths"), "OpenAPI paths");
		Map<String, Object> operation = objectMap(
			objectMap(paths.get("/sync/changes"), "OpenAPI path /sync/changes").get("get"), "GET /sync/changes");

		assertEquals("listSyncChanges", operation.get("operationId"));
		// 400 与既有 200/401/403 一起锁住，防止失败语义再次从机器契约中丢失。
		assertEquals(Set.of("200", "400", "401", "403"),
			objectMap(operation.get("responses"), "listSyncChanges responses").keySet());
		assertEquals("#/components/responses/SyncChangeListOk", responseRef(operation, "200"));
		assertEquals("#/components/responses/BadRequest", responseRef(operation, "400"));
		assertEquals("#/components/responses/Unauthenticated", responseRef(operation, "401"));
		assertEquals("#/components/responses/Forbidden", responseRef(operation, "403"));
		assertEquals(
			List.of("#/components/parameters/Cursor", "#/components/parameters/Limit"),
			((List<?>) operation.get("parameters")).stream()
				.map(parameter -> objectMap(parameter, "listSyncChanges parameter").get("$ref"))
				.toList());
	}

	private static String responseRef(Map<String, Object> operation, String status) {
		Map<String, Object> responses = objectMap(operation.get("responses"), "listSyncChanges responses");
		return String.valueOf(objectMap(responses.get(status), "listSyncChanges " + status + " response").get("$ref"));
	}

	private static Map<String, Object> readContract() throws IOException {
		Path contract = locateContract();
		try (InputStream input = Files.newInputStream(contract)) {
			return new Yaml().load(input);
		}
	}

	private static Path locateContract() throws IOException {
		for (Path candidate : List.of(Path.of("../openapi/ziji-v1.yaml"), Path.of("openapi/ziji-v1.yaml"))) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toRealPath();
			}
		}
		throw new IllegalStateException("未找到 openapi/ziji-v1.yaml；请从仓库根目录或 backend 目录运行测试");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> objectMap(Object value, String label) {
		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalStateException(label + " 必须是对象");
		}
		return new LinkedHashMap<>((Map<String, Object>) map);
	}
}
