package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK HTTP GET 实现；不记录请求或响应 body，同花顺公开端点无凭据。 */
public final class JavaHttpThsTransport implements ThsTransport {

	private final HttpClient client;

	public JavaHttpThsTransport() {
		this(HttpClient.newBuilder().build());
	}

	public JavaHttpThsTransport(HttpClient client) {
		this.client = java.util.Objects.requireNonNull(client, "同花顺 HTTP 客户端不能为空。");
	}

	@Override
	public ThsTransportResponse get(String url, Duration timeout)
		throws IOException, InterruptedException {
		if (url == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("同花顺 HTTP 请求参数无效。");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(timeout)
			.header("User-Agent", "Mozilla/5.0 (Ziji; personal finance)")
			.header("Accept", "*/*")
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new ThsTransportResponse(response.statusCode(), response.body());
	}
}
