package app.ziji.marketdata.infrastructure;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.ziji.marketdata.application.internal.MarketDataQuotaPort;
import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.RemoteInstrument;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 同花顺公开数据源适配器（CHG-MD-001）：股票/ETF 盘后日线与公募基金历史单位净值。
 * 端点无 key、无官方授权和 SLA，只请求本地已有 THS 映射的产品，不提供全市场拉取；
 * 响应为 JS 变量包裹的 JSON/数组，只在适配层解析并转换为内部价格。
 */
public final class ThsMarketDataAdapter implements MarketDataSourcePort {

	private static final String KLINE_TEMPLATE = "http://d.10jqka.com.cn/v6/line/hs_%s/01/last1800.js";
	private static final String FUND_NAV_TEMPLATE = "http://fund.10jqka.com.cn/%s/json/jsondwjz.json";
	private final ThsTransport transport;
	private final ObjectMapper objectMapper;
	private final Duration timeout;
	private final int maxRetries;
	private final ThsRateLimiter limiter;
	private final MarketDataQuotaPort quota;
	private final Clock clock;
	private final Sleeper sleeper;

	public ThsMarketDataAdapter(
		ThsTransport transport,
		ObjectMapper objectMapper,
		Duration timeout,
		int maxRetries,
		ThsRateLimiter limiter,
		MarketDataQuotaPort quota,
		Clock clock) {
		this(transport, objectMapper, timeout, maxRetries, limiter, quota, clock,
			delay -> {
				try {
					Thread.sleep(delay.toMillis());
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
	}

	public ThsMarketDataAdapter(
		ThsTransport transport,
		ObjectMapper objectMapper,
		Duration timeout,
		int maxRetries,
		ThsRateLimiter limiter,
		MarketDataQuotaPort quota,
		Clock clock,
		Sleeper sleeper) {
		this.transport = Objects.requireNonNull(transport, "同花顺传输适配器不能为空。");
		this.objectMapper = Objects.requireNonNull(objectMapper, "同花顺 JSON 适配器不能为空。");
		this.timeout = Objects.requireNonNull(timeout, "同花顺超时不能为空。");
		if (this.timeout.isNegative() || this.timeout.isZero() || maxRetries < 0 || maxRetries > 5) {
			throw new IllegalArgumentException("同花顺重试参数无效。");
		}
		this.maxRetries = maxRetries;
		this.limiter = Objects.requireNonNull(limiter, "同花顺限流器不能为空。");
		this.quota = Objects.requireNonNull(quota, "同花顺每日配额门不能为空。");
		this.clock = Objects.requireNonNull(clock, "同花顺时钟不能为空。");
		this.sleeper = Objects.requireNonNull(sleeper, "同花顺重试等待器不能为空。");
	}

	@Override
	public SourceResult fetchPrices(
		Instrument instrument,
		InstrumentSourceMapping mapping,
		LocalDate from,
		LocalDate to) {
		InstantHolder completed = new InstantHolder(clock.instant());
		if (instrument == null || mapping == null || mapping.source() != PriceSource.THS
			|| mapping.externalCode().isBlank()) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		LocalDate end = to == null ? LocalDate.now(clock) : to;
		LocalDate start = from == null ? end.minusDays(30) : from;
		if (start.isAfter(end)) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		PriceType priceType = switch (instrument.instrumentType()) {
			case STOCK, ETF -> PriceType.CLOSE;
			case FUND -> PriceType.UNIT_NAV;
			case OTHER -> null;
		};
		if (priceType == null) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		String url = priceType == PriceType.CLOSE
			? KLINE_TEMPLATE.formatted(mapping.externalCode())
			: FUND_NAV_TEMPLATE.formatted(mapping.externalCode());
		CallResult call = call(url);
		if (call.outcome != SourceOutcome.SUCCESS) {
			return SourceResult.failure(call.outcome, call.attempts, clock.instant());
		}
		List<SourcePrice> prices = parsePrices(call.body, priceType, start, end, instrument.currency());
		if (prices.isEmpty()) {
			return SourceResult.failure(SourceOutcome.NO_DATA, call.attempts, clock.instant());
		}
		return new SourceResult(SourceOutcome.SUCCESS, prices, call.attempts, clock.instant());
	}

	@Override
	public List<RemoteInstrument> searchBasics(String query) {
		// 同花顺没有可用的公开产品搜索接口；未命中时由客户端提示手工创建产品或输入代码。
		return List.of();
	}

	private CallResult call(String url) {
		int maximumAttempts = maxRetries + 1;
		for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
			if (!limiter.tryAcquire()) {
				return new CallResult(SourceOutcome.RATE_LIMITED, attempt - 1);
			}
			if (!quota.reserve(LocalDate.now(clock))) {
				return new CallResult(SourceOutcome.RATE_LIMITED, attempt - 1);
			}
			try {
				ThsTransportResponse response = transport.get(url, timeout);
				SourceOutcome outcome = responseOutcome(response);
				if (isRetryable(outcome) && attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt);
					}
					continue;
				}
				return new CallResult(outcome, attempt, outcome == SourceOutcome.SUCCESS ? response.body() : null);
			} catch (HttpTimeoutException exception) {
				if (attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt);
					}
					continue;
				}
				return new CallResult(SourceOutcome.TIMEOUT, attempt);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return new CallResult(SourceOutcome.TIMEOUT, attempt);
			} catch (java.io.IOException | RuntimeException exception) {
				if (attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt);
					}
					continue;
				}
				return new CallResult(SourceOutcome.UNAVAILABLE, attempt);
			}
		}
		return new CallResult(SourceOutcome.ERROR, maximumAttempts);
	}

	private List<SourcePrice> parsePrices(String body, PriceType priceType, LocalDate start, LocalDate end, String currency) {
		try {
			if (priceType == PriceType.CLOSE) {
				return parseKline(body, start, end, currency);
			}
			return parseFundNav(body, start, end, currency);
		} catch (RuntimeException exception) {
			// 响应格式变化不视为可重试故障；返回空由调用方按 NO_DATA 处理。
			return List.of();
		}
	}

	/** K 线响应为 quotebridge_v6_line_*({...}) 包裹的 JSON；data 是分号分隔的行：日期,开,高,低,收,量,额,… */
	private List<SourcePrice> parseKline(String body, LocalDate start, LocalDate end, String currency) {
		String json = stripWrapper(body, "(", ")");
		JsonNode root = objectMapper.readTree(json);
		JsonNode data = root == null ? null : root.get("data");
		if (data == null || !data.isTextual()) {
			return List.of();
		}
		List<SourcePrice> result = new ArrayList<>();
		for (String line : data.asText().split(";")) {
			String[] columns = line.split(",", -1);
			if (columns.length < 5) {
				continue;
			}
			LocalDate date = parseDate(columns[0]);
			BigDecimal close = parseDecimal(columns[4]);
			if (date == null || close == null || date.isBefore(start) || date.isAfter(end)) {
				continue;
			}
			result.add(new SourcePrice(PriceType.CLOSE, date, close, currency, null, hexSha256(body)));
		}
		return List.copyOf(result);
	}

	/** 基金净值响应为 var dwjz_{code}=[[日期,"单位净值"],…]；只取单位净值列。 */
	private List<SourcePrice> parseFundNav(String body, LocalDate start, LocalDate end, String currency) {
		String json = stripWrapper(body, "=", null);
		JsonNode root = objectMapper.readTree(json);
		if (root == null || !root.isArray()) {
			return List.of();
		}
		List<SourcePrice> result = new ArrayList<>();
		for (JsonNode item : root) {
			if (item == null || !item.isArray() || item.size() < 2) {
				continue;
			}
			LocalDate date = parseDate(item.get(0).asText(""));
			BigDecimal nav = parseDecimal(item.get(1).asText(""));
			if (date == null || nav == null || date.isBefore(start) || date.isAfter(end)) {
				continue;
			}
			result.add(new SourcePrice(PriceType.UNIT_NAV, date, nav, currency, null, hexSha256(body)));
		}
		return List.copyOf(result);
	}

	private static String stripWrapper(String body, String from, String to) {
		if (body == null) {
			throw new IllegalArgumentException("同花顺响应体不能为空。");
		}
		int start = body.indexOf(from);
		if (start < 0) {
			throw new IllegalArgumentException("同花顺响应缺少包裹前缀。");
		}
		int contentStart = start + from.length();
		int contentEnd = to == null ? body.length() : body.lastIndexOf(to);
		if (contentEnd <= contentStart) {
			throw new IllegalArgumentException("同花顺响应包裹不完整。");
		}
		return body.substring(contentStart, contentEnd);
	}

	private static LocalDate parseDate(String value) {
		try {
			return LocalDate.parse(value.trim(),
				java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static BigDecimal parseDecimal(String value) {
		try {
			BigDecimal parsed = new BigDecimal(value.trim());
			return parsed.signum() <= 0 || parsed.scale() > 12 || parsed.precision() > 28 ? null : parsed;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private SourceOutcome responseOutcome(ThsTransportResponse response) {
		if (response == null) {
			return SourceOutcome.UNAVAILABLE;
		}
		if (response.httpStatus() == 408) {
			return SourceOutcome.TIMEOUT;
		}
		if (response.httpStatus() == 429) {
			return SourceOutcome.RATE_LIMITED;
		}
		if (response.httpStatus() >= 500) {
			return SourceOutcome.UNAVAILABLE;
		}
		if (response.httpStatus() < 200 || response.httpStatus() >= 300) {
			return SourceOutcome.ERROR;
		}
		if (response.body().isBlank()) {
			return SourceOutcome.ERROR;
		}
		return SourceOutcome.SUCCESS;
	}

	private static boolean isRetryable(SourceOutcome outcome) {
		return outcome == SourceOutcome.TIMEOUT || outcome == SourceOutcome.RATE_LIMITED
			|| outcome == SourceOutcome.UNAVAILABLE;
	}

	private static Duration backoff(int attempt) {
		long multiplier = 1L << Math.min(attempt - 1, 3);
		return Duration.ofMillis(Math.min(1_000L, 100L * multiplier));
	}

	private static String hexSha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(64);
			for (byte item : digest) {
				result.append(String.format("%02x", item));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 不可用。", exception);
		}
	}

	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration delay);
	}

	private record CallResult(SourceOutcome outcome, int attempts, String body) {

		private CallResult(SourceOutcome outcome, int attempts) {
			this(outcome, attempts, null);
		}
	}

	private static final class InstantHolder {
		private final java.time.Instant value;

		private InstantHolder(java.time.Instant value) {
			this.value = value;
		}
	}
}
