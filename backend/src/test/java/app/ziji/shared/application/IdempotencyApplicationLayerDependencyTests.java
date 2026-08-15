package app.ziji.shared.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** shared application 的统一幂等模型必须保持纯 Java，不得反向依赖 Spring、jOOQ、Servlet 或 auth infrastructure。 */
class IdempotencyApplicationLayerDependencyTests {

	@Test
	void idempotencyApplicationSourcesDoNotImportFrameworkOrAuthInfrastructureTypes() throws IOException {
		Path sourceRoot = Path.of("src/main/java/app/ziji/shared/application");
		try (Stream<Path> files = Files.walk(sourceRoot)) {
			for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.getFileName().toString().equals("package-info.java")).toList()) {
				String source = Files.readString(sourceFile);
				// 可复用服务只依赖公开 application 端口，框架和密码学细节留给 infrastructure。
				assertFalse(source.contains("org.springframework"), () -> sourceFile + " imports Spring");
				assertFalse(source.contains("org.jooq"), () -> sourceFile + " imports jOOQ");
				assertFalse(source.contains("jakarta.servlet"), () -> sourceFile + " imports Servlet");
				assertFalse(source.contains("app.ziji.auth.infrastructure"), () -> sourceFile + " imports auth infrastructure");
			}
		}
	}
}
