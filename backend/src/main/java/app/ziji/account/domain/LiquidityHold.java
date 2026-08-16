package app.ziji.account.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 可审计的流动性占用版本事实；它不生成 LedgerEntry，状态始终由事实和 asOf 推导。
 */
public final class LiquidityHold {

	private final UUID id;
	private final UUID accountId;
	private final UUID rootHoldId;
	private final UUID previousRevisionId;
	private final int revisionNo;
	private final LiquidityHoldType type;
	private final BigDecimal amount;
	private final AccountCurrency currency;
	private final Instant effectiveAt;
	private final Instant expiresAt;
	private final Instant releasedAt;
	private final LiquidityHoldSource source;
	private final String note;
	private final Instant endedAt;
	private final LiquidityHoldEndReason endReason;
	private final UUID createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;
	private final int version;

	private LiquidityHold(
		UUID id,
		UUID accountId,
		UUID rootHoldId,
		UUID previousRevisionId,
		int revisionNo,
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		Instant expiresAt,
		Instant releasedAt,
		LiquidityHoldSource source,
		String note,
		Instant endedAt,
		LiquidityHoldEndReason endReason,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		int version) {
		require(id, accountId, rootHoldId, type, amount, currency, effectiveAt, source, createdBy, createdAt, updatedAt);
		if (revisionNo < 1 || version < 1 || (expiresAt != null && !expiresAt.isAfter(effectiveAt))
			|| (revisionNo == 1 && (previousRevisionId != null || !rootHoldId.equals(id)))
			|| (revisionNo > 1 && previousRevisionId == null)
			|| ((endedAt == null) != (endReason == null))
			|| (releasedAt != null && endReason != LiquidityHoldEndReason.RELEASED)) {
			throw invalid();
		}
		this.id = id;
		this.accountId = accountId;
		this.rootHoldId = rootHoldId;
		this.previousRevisionId = previousRevisionId;
		this.revisionNo = revisionNo;
		this.type = type;
		this.amount = amount;
		this.currency = currency;
		this.effectiveAt = effectiveAt;
		this.expiresAt = expiresAt;
		this.releasedAt = releasedAt;
		this.source = source;
		this.note = note;
		this.endedAt = endedAt;
		this.endReason = endReason;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.version = version;
	}

	public static LiquidityHold createRoot(
		UUID id,
		UUID accountId,
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		Instant expiresAt,
		String reason,
		UUID createdBy,
		Instant now) {
		requireReason(reason);
		return new LiquidityHold(id, accountId, id, null, 1, type, amount, currency, effectiveAt, expiresAt,
			null, LiquidityHoldSource.MANUAL, reason, null, null, createdBy, now, now, 1);
	}

	public static LiquidityHold createRevision(
		UUID id,
		LiquidityHold previous,
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		Instant expiresAt,
		String reason,
		UUID createdBy,
		Instant now) {
		if (previous == null) {
			throw invalid();
		}
		requireReason(reason);
		return new LiquidityHold(id, previous.accountId, previous.rootHoldId, previous.id, previous.revisionNo + 1,
			type, amount, currency, effectiveAt, expiresAt, null, LiquidityHoldSource.MANUAL, reason,
			null, null, createdBy, now, now, 1);
	}

	public static LiquidityHold restore(
		UUID id,
		UUID accountId,
		UUID rootHoldId,
		UUID previousRevisionId,
		int revisionNo,
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		Instant expiresAt,
		Instant releasedAt,
		LiquidityHoldSource source,
		String note,
		Instant endedAt,
		LiquidityHoldEndReason endReason,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		int version) {
		return new LiquidityHold(id, accountId, rootHoldId, previousRevisionId, revisionNo, type, amount,
			currency, effectiveAt, expiresAt, releasedAt, source, note, endedAt, endReason, createdBy,
			createdAt, updatedAt, version);
	}

	public LiquidityHoldStatus statusAt(Instant asOf) {
		if (asOf == null) {
			throw invalid();
		}
		if (endReason == LiquidityHoldEndReason.RELEASED) {
			return LiquidityHoldStatus.RELEASED;
		}
		if (endReason == LiquidityHoldEndReason.SUPERSEDED) {
			return LiquidityHoldStatus.SUPERSEDED;
		}
		if (endReason == LiquidityHoldEndReason.EXPIRED || (endedAt == null && expiresAt != null && !expiresAt.isAfter(asOf))) {
			return LiquidityHoldStatus.EXPIRED;
		}
		return effectiveAt.isAfter(asOf) ? LiquidityHoldStatus.PENDING : LiquidityHoldStatus.ACTIVE;
	}

	public boolean isOperableAt(Instant asOf) {
		return statusAt(asOf) == LiquidityHoldStatus.PENDING || statusAt(asOf) == LiquidityHoldStatus.ACTIVE;
	}

	public String etag() {
		return "\"" + version + "\"";
	}

	public static void validateAmountForCurrency(BigDecimal amount, AccountCurrency currency) {
		if (amount == null || currency == null || amount.signum() <= 0) {
			throw invalid();
		}
		int maximumScale = currency == AccountCurrency.JPY ? 0 : 2;
		if (amount.stripTrailingZeros().scale() > maximumScale) {
			throw invalid();
		}
	}

	private static void require(
		UUID id,
		UUID accountId,
		UUID rootHoldId,
		LiquidityHoldType type,
		BigDecimal amount,
		AccountCurrency currency,
		Instant effectiveAt,
		LiquidityHoldSource source,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt) {
		if (id == null || accountId == null || rootHoldId == null || type == null || effectiveAt == null
			|| source == null || createdBy == null || createdAt == null || updatedAt == null) {
			throw invalid();
		}
		validateAmountForCurrency(amount, currency);
	}

	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank() || reason.length() > 500) {
			throw invalid();
		}
	}

	private static AccountDomainException invalid() {
		return new AccountDomainException("流动性占用事实无效。");
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public UUID rootHoldId() { return rootHoldId; }
	public UUID previousRevisionId() { return previousRevisionId; }
	public int revisionNo() { return revisionNo; }
	public LiquidityHoldType type() { return type; }
	public BigDecimal amount() { return amount; }
	public AccountCurrency currency() { return currency; }
	public Instant effectiveAt() { return effectiveAt; }
	public Instant expiresAt() { return expiresAt; }
	public Instant releasedAt() { return releasedAt; }
	public LiquidityHoldSource source() { return source; }
	public String note() { return note; }
	public Instant endedAt() { return endedAt; }
	public LiquidityHoldEndReason endReason() { return endReason; }
	public UUID createdBy() { return createdBy; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public int version() { return version; }
}
