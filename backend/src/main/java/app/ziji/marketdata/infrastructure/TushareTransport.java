package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.time.Duration;

/** Tushare HTTP 传输 seam；实现不得记录 body，body 中包含服务端 token。 */
public interface TushareTransport {

	TushareTransportResponse post(String endpoint, String body, Duration timeout)
		throws IOException, InterruptedException;
}
