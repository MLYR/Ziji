package app.ziji.liability.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 独立负债详情；version=0 只代表无持久行的稳定读取投影。 */
public final class LiabilityDetail {

	private final UUID accountId;
	private final LiabilityDetailValues values;
	private final Instant updatedAt;
	private final int version;

	private LiabilityDetail(UUID accountId, LiabilityDetailValues values, Instant updatedAt, int version) {
		if (accountId == null || values == null || version < 0
			|| version == 0 && updatedAt != null || version > 0 && updatedAt == null) {
			throw new LiabilityDetailException.Validation();
		}
		this.accountId = accountId;
		this.values = values;
		this.updatedAt = updatedAt;
		this.version = version;
	}

	public static LiabilityDetail empty(UUID accountId) {
		return new LiabilityDetail(accountId,
			new LiabilityDetailValues(null, null, null, null, null, null), null, 0);
	}

	public static LiabilityDetail create(UUID accountId, LiabilityDetailValues values, Instant updatedAt) {
		return new LiabilityDetail(accountId, values, updatedAt, 1);
	}

	public static LiabilityDetail restore(
		UUID accountId, LiabilityDetailValues values, Instant updatedAt, int version) {
		if (version < 1) {
			throw new LiabilityDetailException.Validation();
		}
		return new LiabilityDetail(accountId, values, updatedAt, version);
	}

	public LiabilityDetail replace(LiabilityDetailValues replacement, Instant now) {
		requirePersistent(now);
		return new LiabilityDetail(accountId, replacement, now, version + 1);
	}

	public LiabilityDetail patch(LiabilityDetailPatch patch, Instant now) {
		if (patch == null) {
			throw new LiabilityDetailException.Validation();
		}
		return replace(patch.applyTo(values), now);
	}

	public UUID accountId() {
		return accountId;
	}

	public BigDecimal interestRate() {
		return values.interestRate();
	}

	public LocalDate loanDate() {
		return values.loanDate();
	}

	public LocalDate dueDate() {
		return values.dueDate();
	}

	public Integer billingDay() {
		return values.billingDay();
	}

	public Integer repaymentDay() {
		return values.repaymentDay();
	}

	public BigDecimal currentAmountDue() {
		return values.currentAmountDue();
	}

	public LiabilityDetailValues values() {
		return values;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public int version() {
		return version;
	}

	public String etag() {
		return "\"" + version + "\"";
	}

	private void requirePersistent(Instant now) {
		if (version < 1 || now == null) {
			throw new LiabilityDetailException.Validation();
		}
	}
}
