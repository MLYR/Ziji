package app.ziji.account.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** account 的领域和应用层必须保持纯 Java，Spring、jOOQ 与 Servlet 仅可出现在 infrastructure。 */
class AccountApplicationLayerDependencyTests {

	@Test
	void domainAndApplicationDoNotImportFrameworkTypes() throws IOException {
		for (Path sourceRoot : List.of(
			Path.of("src/main/java/app/ziji/account/domain"),
			Path.of("src/main/java/app/ziji/account/application"),
			Path.of("src/main/java/app/ziji/account/interfaces"))) {
			try (Stream<Path> files = Files.walk(sourceRoot)) {
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
}
