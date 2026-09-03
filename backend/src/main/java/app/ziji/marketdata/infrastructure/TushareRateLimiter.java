package app.ziji.marketdata.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

/** 进程内请求闸门；数据库/供应商配额仍需由部署侧共同约束。 */
public final class TushareRateLimiter {

	private final Clock clock;
	private final Duration minimumInterval;
	private final int dailyLimit;
	private LocalDate countDate;
	private int count;
	private java.time.Instant lastRequestAt;

	public TushareRateLimiter(Clock clock, Duration minimumInterval, int dailyLimit) {
		this.clock = java.util.Objects.requireNonNull(clock, "Tushare 限流时钟不能为空。");
		if (minimumInterval == null || minimumInterval.isNegative() || dailyLimit < 1) {
			throw new IllegalArgumentException("Tushare 限流参数无效。");
		}
		this.minimumInterval = minimumInterval;
		this.dailyLimit = dailyLimit;
	}

	public synchronized boolean tryAcquire() {
		java.time.Instant now = clock.instant();
		LocalDate today = LocalDate.now(clock);
		if (!today.equals(countDate)) {
			countDate = today;
			count = 0;
		}
		if (count >= dailyLimit || lastRequestAt != null
			&& now.isBefore(lastRequestAt.plus(minimumInterval))) {
			return false;
		}
		count++;
		lastRequestAt = now;
		return true;
	}
}
