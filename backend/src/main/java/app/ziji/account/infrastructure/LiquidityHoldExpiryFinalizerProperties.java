package app.ziji.account.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** LiquidityHold 自动过期任务配置；调度默认值只负责执行频率，不改变领域到期边界。 */
@Validated
@ConfigurationProperties(prefix = "ziji.liquidity-hold.expiry-finalizer")
public class LiquidityHoldExpiryFinalizerProperties {

	private boolean enabled = true;
	private Duration initialDelay = Duration.ofMinutes(1);
	private Duration fixedDelay = Duration.ofMinutes(1);
	private Duration staleRunAfter = Duration.ofMinutes(15);
	private Duration lockTimeout = Duration.ofSeconds(5);

	@Min(1)
	@Max(1_000)
	private int batchSize = 100;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getInitialDelay() {
		return initialDelay;
	}

	public void setInitialDelay(Duration initialDelay) {
		this.initialDelay = initialDelay;
	}

	public Duration getFixedDelay() {
		return fixedDelay;
	}

	public void setFixedDelay(Duration fixedDelay) {
		this.fixedDelay = fixedDelay;
	}

	public Duration getStaleRunAfter() {
		return staleRunAfter;
	}

	public void setStaleRunAfter(Duration staleRunAfter) {
		this.staleRunAfter = staleRunAfter;
	}

	public Duration getLockTimeout() {
		return lockTimeout;
	}

	public void setLockTimeout(Duration lockTimeout) {
		this.lockTimeout = lockTimeout;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public void validate() {
		if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()
			|| fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()
			|| staleRunAfter == null || staleRunAfter.isZero() || staleRunAfter.isNegative()
			|| lockTimeout == null || lockTimeout.isZero() || lockTimeout.isNegative()
			|| lockTimeout.compareTo(Duration.ofMinutes(1)) > 0 || lockTimeout.toMillis() < 1) {
			throw new IllegalStateException("流动性占用过期调度间隔必须为正数。");
		}
		if (batchSize < 1 || batchSize > 1_000) {
			throw new IllegalStateException("流动性占用过期批次大小超出允许范围。");
		}
	}
}
