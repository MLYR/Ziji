package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK HTTP 实现；不记录请求 body、响应 body 或认证信息。 */
public final class JavaHttpTushareTransport implements TushareTransport {

	private final HttpClient client;

	public JavaHttpTushareTransport() {
		this(HttpClient.newBuilder().build());
	}

	public JavaHttpTushareTransport(HttpClient client) {
		this.client = java.util.Objects.requireNonNull(client, "Tushare HTTP 客户端不能为空。");
	}

	@Override
	public TushareTransportResponse post(String endpoint, String body, Duration timeout)
		throws IOException, InterruptedException {
		if (endpoint == null || body == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("Tushare HTTP 请求参数无效。");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new TushareTransportResponse(response.statusCode(), response.body());
	}
}
