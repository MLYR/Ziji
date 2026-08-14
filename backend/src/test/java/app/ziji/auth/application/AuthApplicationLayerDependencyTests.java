package app.ziji.auth.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** auth 的领域和应用层必须保持纯 Java，Spring、jOOQ 与 Servlet 仅可出现在 infrastructure/interfaces。 */
class AuthApplicationLayerDependencyTests {

	@Test
	void domainAndApplicationDoNotImportFrameworkTypes() throws IOException {
		for (Path sourceRoot : List.of(
			Path.of("src/main/java/app/ziji/auth/domain"),
			Path.of("src/main/java/app/ziji/auth/application"))) {
			try (Stream<Path> files = Files.walk(sourceRoot)) {
				for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					String source = Files.readString(sourceFile);
					// 框架依赖会破坏领域/application 的可测试性与模块边界，应由 infrastructure 负责装配。
					assertFalse(source.contains("org.springframework"), () -> sourceFile + " imports Spring");
					assertFalse(source.contains("org.jooq"), () -> sourceFile + " imports jOOQ");
					assertFalse(source.contains("jakarta.servlet"), () -> sourceFile + " imports Servlet");
				}
			}
		}
	}
}
