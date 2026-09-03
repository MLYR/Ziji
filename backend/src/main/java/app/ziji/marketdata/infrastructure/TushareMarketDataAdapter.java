package app.ziji.marketdata.infrastructure;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.ziji.marketdata.application.internal.MarketDataSourcePort;
import app.ziji.marketdata.application.internal.SourceOutcome;
import app.ziji.marketdata.application.internal.SourcePrice;
import app.ziji.marketdata.application.internal.SourceResult;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Tushare Pro 盘后/净值适配器；请求只针对本地已有 mapping，不提供全市场拉取入口。 */
public final class TushareMarketDataAdapter implements MarketDataSourcePort {

	private static final String DAILY_FIELDS = "ts_code,trade_date,close";
	private static final String FUND_FIELDS = "ts_code,ann_date,nav_date,unit_nav";

	private final TushareTransport transport;
	private final ObjectMapper objectMapper;
	private final String endpoint;
	private final String token;
	private final Duration timeout;
	private final int maxRetries;
	private final TushareRateLimiter limiter;
	private final Clock clock;
	private final Sleeper sleeper;

	public TushareMarketDataAdapter(
		TushareTransport transport,
		ObjectMapper objectMapper,
		String endpoint,
		String token,
		Duration timeout,
		int maxRetries,
		TushareRateLimiter limiter,
		Clock clock) {
		this(transport, objectMapper, endpoint, token, timeout, maxRetries, limiter, clock,
			delay -> {
				try {
					Thread.sleep(delay.toMillis());
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			});
	}

	public TushareMarketDataAdapter(
		TushareTransport transport,
		ObjectMapper objectMapper,
		String endpoint,
		String token,
		Duration timeout,
		int maxRetries,
		TushareRateLimiter limiter,
		Clock clock,
		Sleeper sleeper) {
		this.transport = Objects.requireNonNull(transport, "Tushare 传输适配器不能为空。");
		this.objectMapper = Objects.requireNonNull(objectMapper, "Tushare JSON 适配器不能为空。");
		this.endpoint = Objects.requireNonNull(endpoint, "Tushare endpoint 不能为空。");
		this.token = token == null ? "" : token.trim();
		this.timeout = Objects.requireNonNull(timeout, "Tushare 超时不能为空。");
		if (this.timeout.isNegative() || this.timeout.isZero() || maxRetries < 0 || maxRetries > 5) {
			throw new IllegalArgumentException("Tushare 重试参数无效。");
		}
		this.maxRetries = maxRetries;
		this.limiter = Objects.requireNonNull(limiter, "Tushare 限流器不能为空。");
		this.clock = Objects.requireNonNull(clock, "Tushare 时钟不能为空。");
		this.sleeper = Objects.requireNonNull(sleeper, "Tushare 重试等待器不能为空。");
	}

	@Override
	public SourceResult fetchPrices(
		Instrument instrument,
		InstrumentSourceMapping mapping,
		LocalDate from,
		LocalDate to) {
		InstantHolder completed = new InstantHolder(clock.instant());
		if (instrument == null || mapping == null || mapping.source() != PriceSource.TUSHARE
			|| mapping.externalCode().isBlank()) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		LocalDate end = to == null ? LocalDate.now(clock) : to;
		LocalDate start = from == null ? end.minusDays(30) : from;
		if (start.isAfter(end)) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		String apiName = switch (instrument.instrumentType()) {
			case STOCK -> "daily";
			case ETF -> "fund_daily";
			case FUND -> "fund_nav";
			case OTHER -> null;
		};
		if (apiName == null) {
			return SourceResult.failure(SourceOutcome.ERROR, 0, completed.value);
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("ts_code", mapping.externalCode());
		params.put("start_date", compactDate(start));
		params.put("end_date", compactDate(end));
		String fields = apiName.equals("fund_nav") ? FUND_FIELDS : DAILY_FIELDS;
		CallResult call = call(apiName, params, fields);
		if (call.outcome != SourceOutcome.SUCCESS) {
			return SourceResult.failure(call.outcome, call.attempts, clock.instant());
		}
		List<SourcePrice> prices = parsePrices(call.body, apiName, instrument.currency());
		if (prices.isEmpty()) {
			return SourceResult.failure(SourceOutcome.NO_DATA, call.attempts, clock.instant());
		}
		return new SourceResult(SourceOutcome.SUCCESS, prices, call.attempts, clock.instant());
	}

	private CallResult call(String apiName, Map<String, Object> params, String fields) {
		if (token.isBlank()) {
			return new CallResult(SourceOutcome.NO_TOKEN, 0, null);
		}
		int maximumAttempts = maxRetries + 1;
		for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
			if (!limiter.tryAcquire()) {
				return new CallResult(SourceOutcome.RATE_LIMITED, attempt - 1, null);
			}
			try {
				String body = objectMapper.writeValueAsString(Map.of(
					"api_name", apiName,
					"token", token,
					"params", params,
					"fields", fields));
				TushareTransportResponse response = transport.post(endpoint, body, timeout);
				SourceOutcome outcome = responseOutcome(response);
				if (isRetryable(outcome) && attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt, null);
					}
					continue;
				}
				return new CallResult(outcome, attempt, outcome == SourceOutcome.SUCCESS ? response.body() : null);
			} catch (HttpTimeoutException exception) {
				if (attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt, null);
					}
					continue;
				}
				return new CallResult(SourceOutcome.TIMEOUT, attempt, null);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return new CallResult(SourceOutcome.TIMEOUT, attempt, null);
			} catch (java.io.IOException | RuntimeException exception) {
				if (attempt < maximumAttempts) {
					sleeper.sleep(backoff(attempt));
					if (Thread.currentThread().isInterrupted()) {
						return new CallResult(SourceOutcome.TIMEOUT, attempt, null);
					}
					continue;
				}
				return new CallResult(SourceOutcome.UNAVAILABLE, attempt, null);
			}
		}
		return new CallResult(SourceOutcome.ERROR, maximumAttempts, null);
	}

	private List<SourcePrice> parsePrices(String body, String apiName, String currency) {
		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode data = root.get("data");
			if (data == null || !data.isObject()) {
				return List.of();
			}
			JsonNode fieldsNode = data.get("fields");
			JsonNode items = data.get("items");
			if (fieldsNode == null || !fieldsNode.isArray() || items == null || !items.isArray()) {
				return List.of();
			}
			Map<String, Integer> indexes = new LinkedHashMap<>();
			for (int index = 0; index < fieldsNode.size(); index++) {
				String field = fieldsNode.get(index).textValue();
				if (field != null) {
					indexes.put(field, index);
				}
			}
			List<SourcePrice> result = new ArrayList<>();
			for (JsonNode item : items) {
				String rawDate = value(item, indexes, apiName.equals("fund_nav") ? "nav_date" : "trade_date");
				String rawPrice = value(item, indexes, apiName.equals("fund_nav") ? "unit_nav" : "close");
				if (rawDate == null || rawPrice == null) {
					continue;
				}
				try {
					LocalDate date = LocalDate.parse(rawDate,
						java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
					BigDecimal price = new BigDecimal(rawPrice);
					if (price.signum() <= 0 || price.scale() > 12 || price.precision() > 28) {
						continue;
					}
					result.add(new SourcePrice(
						apiName.equals("fund_nav") ? PriceType.UNIT_NAV : PriceType.CLOSE,
						date,
						price,
						currency,
						date.atStartOfDay(ZoneOffset.UTC).toInstant(),
						hexSha256(body)));
				} catch (RuntimeException exception) {
					// 单行供应商数据异常不阻断同一响应内的其他有效交易日。
				}
			}
			return List.copyOf(result);
		} catch (RuntimeException exception) {
			return List.of();
		}
	}

	private static String value(JsonNode item, Map<String, Integer> indexes, String field) {
		JsonNode node;
		Integer index = indexes.get(field);
		if (item.isArray()) {
			node = index == null || index >= item.size() ? null : item.get(index);
		} else {
			node = item.get(field);
		}
		// Tushare 同一字段可能以字符串或 JSON number 返回，统一转文本后再做日期/金额解析。
		return node == null || node.isNull() || (!node.isTextual() && !node.isNumber()) ? null : node.asText();
	}

	private SourceOutcome responseOutcome(TushareTransportResponse response) {
		if (response == null) {
			return SourceOutcome.UNAVAILABLE;
		}
		if (response.httpStatus() == 401 || response.httpStatus() == 403) {
			return SourceOutcome.UNAUTHORIZED;
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
		try {
			JsonNode code = objectMapper.readTree(response.body()).get("code");
			if (code == null || !code.isNumber()) {
				return SourceOutcome.ERROR;
			}
			int value = code.intValue();
			if (value == 0) {
				return SourceOutcome.SUCCESS;
			}
				if (value == 2002) {
					return SourceOutcome.UNAUTHORIZED;
				}
				return SourceOutcome.ERROR;
		} catch (RuntimeException exception) {
			return SourceOutcome.ERROR;
		}
	}

	private static boolean isRetryable(SourceOutcome outcome) {
		return outcome == SourceOutcome.TIMEOUT || outcome == SourceOutcome.RATE_LIMITED
			|| outcome == SourceOutcome.UNAVAILABLE;
	}

	private static Duration backoff(int attempt) {
		long multiplier = 1L << Math.min(attempt - 1, 3);
		return Duration.ofMillis(Math.min(1_000L, 100L * multiplier));
	}

	private static String compactDate(LocalDate date) {
		return date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
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
	}

	private static final class InstantHolder {
		private final java.time.Instant value;

		private InstantHolder(java.time.Instant value) {
			this.value = value;
		}
	}
}
