package app.ziji.audit.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** audit application 保持纯 Java；持久化实现只留在 infrastructure。 */
class AuditApplicationLayerDependencyTests {

	@Test
	void applicationDoesNotImportFrameworkTypes() throws IOException {
		try (Stream<Path> files = Files.walk(Path.of("src/main/java/app/ziji/audit/application"))) {
			for (Path sourceFile : files
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.getFileName().toString().equals("package-info.java"))
				.toList()) {
				String source = Files.readString(sourceFile);
				assertFalse(source.contains("org.springframework"), () -> sourceFile + " imports Spring");
				assertFalse(source.contains("org.jooq"), () -> sourceFile + " imports jOOQ");
				assertFalse(source.contains("jakarta.servlet"), () -> sourceFile + " imports Servlet");
			}
		}
	}
}
