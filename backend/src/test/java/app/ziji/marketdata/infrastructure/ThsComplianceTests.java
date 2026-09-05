package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-TS-001（CHG-MD-001 后重定向）：同花顺公开端点无凭据，
 * 合规基线为「无任何供应商凭据配置、无日志泄漏、无远程搜索越权请求」。
 */
class ThsComplianceTests {

	@Test
	void noBundledPropertiesFileMayCarryAnyMarketDataCredential() throws IOException {
		List<String> files = List.of(
			"application.properties", "application-local.properties", "application-deployed.properties",
			"application-staging.properties", "application-production.properties");
		for (String file : files) {
			try (InputStream stream = getClass().getClassLoader().getResourceAsStream(file)) {
				if (stream == null) {
					continue;
				}
				String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
				assertFalse(content.contains("ziji.tushare.token="),
					"配置包不得残留 Tushare Token 配置：" + file);
				assertFalse(content.contains("ziji.ths.token") || content.contains("ziji.ths.api-key")
						|| content.contains("ziji.ths.secret"),
					"行情数据源无凭据，配置包不得包含同花顺密钥：" + file);
			}
		}
	}

	@Test
	void supplierBoundarySourcesNeverLogOrPrintSupplierMaterial() throws IOException {
		// 源码级护栏：供应商边界文件不得输出日志或标准输出，避免响应体进入日志。
		List<Path> files = List.of(
			sourcePath("ThsMarketDataAdapter.java"),
			sourcePath("JavaHttpThsTransport.java"),
			sourcePath("ThsRateLimiter.java"),
			sourcePath("MarketDataSchedulingConfiguration.java"));
		for (Path file : files) {
			assertTrue(Files.isRegularFile(file), "供应商边界源文件必须存在：" + file);
			try (Stream<String> lines = Files.lines(file)) {
				List<String> offending = lines
					.filter(line -> line.contains("System.out") || line.contains("System.err")
						|| line.contains("printStackTrace"))
					.toList();
				assertTrue(offending.isEmpty(),
					"供应商边界不得输出标准输出：" + file + " 命中 " + offending);
			}
		}
	}

	@Test
	void schedulerOnlyLogsControlledSummaryFields() {
		String source = readSource("MarketDataSchedulingConfiguration.java");
		assertTrue(source.contains("exception.getClass().getName()"), "调度失败只记录异常类型。");
		List<String> leakLines = source.lines()
			.filter(line -> line.contains("LOG.") && (line.contains("token") || line.contains("body") || line.contains("response")))
			.toList();
		assertTrue(leakLines.isEmpty(), "调度日志语句不得引用 token、body 或 response：" + leakLines);
	}

	@Test
	void adapterNeverRequestsRemoteSearchAndKeepsNoCredentialState() {
		// 同花顺无公开搜索接口；适配器 searchBasics 必须恒返回空且不发起请求（由 ThsMarketDataAdapterTests 覆盖）。
		String source = readSource("ThsMarketDataAdapter.java");
		assertTrue(source.contains("List.of()") && source.contains("searchBasics"),
			"searchBasics 必须为无请求空实现。");
		assertFalse(source.contains("token") || source.contains("api_key") || source.contains("secret"),
			"适配器不得持有任何凭据。");
	}

	private static Path sourcePath(String file) {
		Path userDir = Path.of(System.getProperty("user.dir"));
		Path direct = userDir.resolve("src/main/java/app/ziji/marketdata/infrastructure/" + file);
		Path fromRepoRoot = userDir.resolve("backend/src/main/java/app/ziji/marketdata/infrastructure/" + file);
		return Files.isRegularFile(direct) ? direct : fromRepoRoot;
	}

	private static String readSource(String file) {
		try {
			return Files.readString(sourcePath(file));
		} catch (IOException exception) {
			throw new AssertionError("源码读取失败：" + file, exception);
		}
	}
}
