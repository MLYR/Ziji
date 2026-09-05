package app.ziji.marketdata.infrastructure;

import java.io.IOException;
import java.time.Duration;

/** 同花顺公开数据源 HTTP 传输 seam；实现不得记录 body 或把响应内容写入日志。 */
public interface ThsTransport {

	ThsTransportResponse get(String url, Duration timeout) throws IOException, InterruptedException;
}
