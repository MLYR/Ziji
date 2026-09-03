package app.ziji.marketdata.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.application.MarketDataPersistenceException;
import app.ziji.marketdata.application.internal.MarketDataCommandStore;
import app.ziji.marketdata.domain.Instrument;
import app.ziji.marketdata.domain.InstrumentSourceMapping;
import app.ziji.marketdata.domain.InstrumentStatus;
import app.ziji.marketdata.domain.InstrumentType;
import app.ziji.marketdata.domain.PriceSnapshot;
import app.ziji.marketdata.domain.PriceSource;
import app.ziji.marketdata.domain.PriceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** instruments、mapping 和 versioned price 快照的 PostgreSQL 适配器。 */
@Repository
public class PostgresMarketDataStore implements MarketDataCommandStore {

	private final JdbcTemplate jdbc;

	public PostgresMarketDataStore(JdbcTemplate jdbc) {
		this.jdbc = java.util.Objects.requireNonNull(jdbc, "市场数据数据库入口不能为空。");
	}

	@Override
	public Optional<Instrument> findInstrument(UUID instrumentId) {
		if (instrumentId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_type, name, market, currency, status, created_at, updated_at, version
				FROM instruments WHERE id = ?
				""", result -> result.next() ? Optional.of(instrument(result)) : Optional.empty(), instrumentId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public List<Instrument> search(String query, int limit) {
		String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
		try {
			return jdbc.query("""
				SELECT DISTINCT i.id, i.instrument_type, i.name, i.market, i.currency, i.status,
					i.created_at, i.updated_at, i.version
				FROM instruments i
				LEFT JOIN instrument_source_mappings m ON m.instrument_id = i.id
				WHERE i.status <> 'INACTIVE'
				  AND (LOWER(i.name) LIKE ? OR LOWER(i.market) LIKE ? OR LOWER(m.external_code) LIKE ?)
				ORDER BY i.name, i.id
				LIMIT ?
				""", (result, ignored) -> instrument(result), pattern, pattern, pattern, limit);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public List<InstrumentSourceMapping> listMappings(UUID instrumentId) {
		if (instrumentId == null) {
			return List.of();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_id, source, external_code, source_market, raw_metadata, last_synced_at
				FROM instrument_source_mappings WHERE instrument_id = ? ORDER BY source, external_code
				""", (result, ignored) -> new InstrumentSourceMapping(
				result.getObject("id", UUID.class), result.getObject("instrument_id", UUID.class),
				PriceSource.valueOf(result.getString("source")), result.getString("external_code"),
				result.getString("source_market"), result.getString("raw_metadata"), instant(result.getTimestamp("last_synced_at"))),
				instrumentId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Instrument insertInstrument(Instrument instrument) {
		if (instrument == null) {
			throw new MarketDataPersistenceException(new IllegalArgumentException("产品不能为空。"));
		}
		try {
			int inserted = jdbc.update("""
				INSERT INTO instruments (id, instrument_type, name, market, currency, status, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", instrument.id(), instrument.instrumentType().name(), instrument.name(), instrument.market(),
				instrument.currency(), instrument.status().name(), timestamp(instrument.createdAt()),
				timestamp(instrument.updatedAt()), instrument.version());
			if (inserted != 1) {
				throw new IllegalStateException("产品写入未生效。");
			}
			return instrument;
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public List<PriceSnapshot> listCurrentPrices(UUID instrumentId, LocalDate from, LocalDate to, int limit) {
		if (instrumentId == null) {
			return List.of();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_id, source, price_type, business_date, price, currency, source_updated_at,
					fetched_at, revision_no, is_current, supersedes_id, created_by, reason, raw_payload_hash, content_hash
				FROM price_snapshots
				WHERE instrument_id = ? AND is_current
				  AND (? IS NULL OR business_date >= ?)
				  AND (? IS NULL OR business_date <= ?)
				ORDER BY business_date DESC, source, price_type, revision_no DESC
				LIMIT ?
				""", (result, ignored) -> price(result), instrumentId, from, from, to, to, limit);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<PriceSnapshot> findPriceById(UUID priceId) {
		if (priceId == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_id, source, price_type, business_date, price, currency, source_updated_at,
					fetched_at, revision_no, is_current, supersedes_id, created_by, reason, raw_payload_hash, content_hash
				FROM price_snapshots WHERE id = ?
				""", result -> result.next() ? Optional.of(price(result)) : Optional.empty(), priceId);
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public Optional<PriceSnapshot> findLatestPrice(
		UUID instrumentId, PriceSource source, PriceType priceType, LocalDate asOf) {
		if (instrumentId == null || source == null || priceType == null || asOf == null) {
			return Optional.empty();
		}
		try {
			return jdbc.query("""
				SELECT id, instrument_id, source, price_type, business_date, price, currency, source_updated_at,
					fetched_at, revision_no, is_current, supersedes_id, created_by, reason, raw_payload_hash, content_hash
				FROM price_snapshots
				WHERE instrument_id = ? AND source = ? AND price_type = ? AND business_date <= ? AND is_current
				ORDER BY business_date DESC, revision_no DESC LIMIT 1
				""", result -> result.next() ? Optional.of(price(result)) : Optional.empty(),
				instrumentId, source.name(), priceType.name(), java.sql.Date.valueOf(asOf));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public PriceSnapshot insertPrice(
		UUID id,
		UUID instrumentId,
		PriceSource source,
		PriceType priceType,
		LocalDate businessDate,
		BigDecimal price,
		String currency,
		Instant sourceUpdatedAt,
		Instant fetchedAt,
		UUID createdBy,
		String reason,
		String rawPayloadHash,
		UUID supersedesId,
		String contentHash) {
		try {
			List<PriceSnapshot> current = jdbc.query("""
				SELECT id, instrument_id, source, price_type, business_date, price, currency, source_updated_at,
					fetched_at, revision_no, is_current, supersedes_id, created_by, reason, raw_payload_hash, content_hash
				FROM price_snapshots
				WHERE instrument_id = ? AND source = ? AND price_type = ? AND business_date = ? AND is_current
				FOR UPDATE
				""", (result, ignored) -> price(result), instrumentId, source.name(), priceType.name(),
				java.sql.Date.valueOf(businessDate));
			PriceSnapshot existing = current.isEmpty() ? null : current.getFirst();
			if (existing != null && existing.contentHash().equals(contentHash) && supersedesId == null) {
				return existing;
			}
			if (existing != null) {
				jdbc.update("UPDATE price_snapshots SET is_current = false WHERE id = ?", existing.id());
			}
			if (supersedesId != null) {
				jdbc.update("UPDATE price_snapshots SET is_current = false WHERE id = ? AND is_current", supersedesId);
			}
			Integer maximumRevision = jdbc.queryForObject("""
				SELECT COALESCE(MAX(revision_no), 0) FROM price_snapshots
				WHERE instrument_id = ? AND source = ? AND price_type = ? AND business_date = ?
				""", Integer.class, instrumentId, source.name(), priceType.name(), java.sql.Date.valueOf(businessDate));
			int revision = (maximumRevision == null ? 0 : maximumRevision) + 1;
			int inserted = jdbc.update("""
				INSERT INTO price_snapshots (
					id, instrument_id, source, price_type, business_date, price, currency, source_updated_at,
					fetched_at, revision_no, is_current, supersedes_id, created_by, reason, raw_payload_hash, content_hash)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?)
				""", id, instrumentId, source.name(), priceType.name(), java.sql.Date.valueOf(businessDate), price,
				currency, timestamp(sourceUpdatedAt), timestamp(fetchedAt), revision, supersedesId, createdBy, reason,
				rawPayloadHash, contentHash);
			if (inserted != 1) {
				throw new IllegalStateException("价格快照写入未生效。");
			}
			return findPriceById(id).orElseThrow(() -> new IllegalStateException("价格快照读取失败。"));
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public MarketDataStatus status(Instant now) {
		try {
			Timestamp latest = jdbc.queryForObject(
				"SELECT MAX(fetched_at) FROM price_snapshots WHERE source = 'TUSHARE'",
				Timestamp.class);
			if (latest == null) {
				return new MarketDataStatus("UNAVAILABLE", null, "UNAVAILABLE");
			}
			Instant instant = latest.toInstant();
			boolean fresh = !instant.isBefore(now.minus(java.time.Duration.ofDays(3)));
			return new MarketDataStatus(fresh ? "AVAILABLE" : "DEGRADED", instant, fresh ? "FRESH" : "STALE");
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private static Instrument instrument(java.sql.ResultSet result) throws java.sql.SQLException {
		return new Instrument(
			result.getObject("id", UUID.class), InstrumentType.valueOf(result.getString("instrument_type")),
			result.getString("name"), result.getString("market"), result.getString("currency"),
			InstrumentStatus.valueOf(result.getString("status")), instant(result.getTimestamp("created_at")),
			instant(result.getTimestamp("updated_at")), result.getInt("version"));
	}

	private static PriceSnapshot price(java.sql.ResultSet result) throws java.sql.SQLException {
		return new PriceSnapshot(
			result.getObject("id", UUID.class), result.getObject("instrument_id", UUID.class),
			PriceSource.valueOf(result.getString("source")), PriceType.valueOf(result.getString("price_type")),
			result.getDate("business_date").toLocalDate(), result.getBigDecimal("price"), result.getString("currency"),
			instant(result.getTimestamp("source_updated_at")), instant(result.getTimestamp("fetched_at")),
			result.getInt("revision_no"), result.getBoolean("is_current"),
			result.getObject("supersedes_id", UUID.class), result.getObject("created_by", UUID.class),
			result.getString("reason"), result.getString("raw_payload_hash"), result.getString("content_hash"));
	}

	private static Instant instant(Timestamp value) {
		return value == null ? null : value.toInstant();
	}

	private static Timestamp timestamp(Instant value) {
		return value == null ? null : Timestamp.from(value);
	}

	private static MarketDataPersistenceException persistence(Throwable exception) {
		return exception instanceof MarketDataPersistenceException failure
			? failure : new MarketDataPersistenceException(exception);
	}
}
