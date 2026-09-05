package app.ziji.marketdata.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import app.ziji.marketdata.application.MarketDataPersistenceException;
import app.ziji.marketdata.application.internal.MarketDataSyncStore;
import app.ziji.marketdata.domain.InstrumentType;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 同步运行记录、每日配额和跨实例调度锁的 PostgreSQL 适配器。
 * 调度锁使用会话级 advisory lock：持锁连接在整个同步运行期间保持打开，
 * 进程崩溃或连接关闭时由 PostgreSQL 自动释放，避免死锁残留。
 */
@Repository
public class PostgresMarketDataSyncStore implements MarketDataSyncStore {

	private static final long SYNC_LOCK_KEY = 814726501L;

	private final JdbcTemplate jdbc;
	private final DataSource dataSource;
	private Connection lockConnection;
	private boolean lockHeld;

	public PostgresMarketDataSyncStore(JdbcTemplate jdbc, DataSource dataSource) {
		this.jdbc = Objects.requireNonNull(jdbc, "市场数据同步数据库入口不能为空。");
		this.dataSource = Objects.requireNonNull(dataSource, "市场数据同步数据源不能为空。");
	}

	@Override
	public List<SyncCandidate> listSyncCandidates() {
		try {
			return jdbc.query("""
				SELECT i.id AS instrument_id, i.instrument_type, i.currency, m.id AS mapping_id,
					m.external_code, m.last_synced_at,
					MAX(CASE WHEN p.price_type = 'CLOSE' THEN p.business_date END) AS last_close_date,
					MAX(CASE WHEN p.price_type = 'UNIT_NAV' THEN p.business_date END) AS last_nav_date
				FROM instruments i
				JOIN instrument_source_mappings m ON m.instrument_id = i.id AND m.source = 'THS'
				LEFT JOIN price_snapshots p ON p.instrument_id = i.id AND p.source = 'THS' AND p.is_current
				WHERE i.status = 'ACTIVE'
				GROUP BY i.id, i.instrument_type, i.currency, m.id, m.external_code, m.last_synced_at
				ORDER BY i.id, m.id
				""", (result, ignored) -> {
				java.sql.Date close = result.getDate("last_close_date");
				java.sql.Date nav = result.getDate("last_nav_date");
				Timestamp synced = result.getTimestamp("last_synced_at");
				return new SyncCandidate(
					result.getObject("instrument_id", UUID.class),
					InstrumentType.valueOf(result.getString("instrument_type")),
					result.getString("currency"),
					result.getObject("mapping_id", UUID.class),
					result.getString("external_code"),
					close == null ? Optional.empty() : Optional.of(close.toLocalDate()),
					nav == null ? Optional.empty() : Optional.of(nav.toLocalDate()),
					synced == null ? Optional.empty() : Optional.of(synced.toInstant()));
			});
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public void touchMappingLastSyncedAt(UUID mappingId, Instant syncedAt) {
		try {
			int updated = jdbc.update(
				"UPDATE instrument_source_mappings SET last_synced_at = ? WHERE id = ?",
				Timestamp.from(syncedAt), mappingId);
			if (updated != 1) {
				throw new IllegalStateException("产品映射同步时间更新未生效。");
			}
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public synchronized boolean tryAcquireSyncLock() {
		if (lockHeld) {
			return false;
		}
		try {
			Connection connection = dataSource.getConnection();
			try (var statement = connection.prepareStatement(
				"SELECT pg_try_advisory_lock(?)")) {
				statement.setLong(1, SYNC_LOCK_KEY);
				try (var result = statement.executeQuery()) {
					boolean acquired = result.next() && result.getBoolean(1);
					if (!acquired) {
						connection.close();
						return false;
					}
					lockConnection = connection;
					lockHeld = true;
					return true;
				}
			} catch (SQLException exception) {
				connection.close();
				throw persistence(exception);
			}
		} catch (SQLException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public synchronized void releaseSyncLock() {
		if (!lockHeld) {
			return;
		}
		try {
			try (var statement = lockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				statement.setLong(1, SYNC_LOCK_KEY);
				try (var ignored = statement.executeQuery()) {
					// 解锁结果不区分成败；连接关闭时 PostgreSQL 总会释放会话锁。
				}
			}
		} catch (SQLException ignored) {
			// 连接可能已经失效；关闭后由数据库回收锁。
		} finally {
			try {
				lockConnection.close();
			} catch (SQLException ignored) {
				// 忽略关闭失败。
			}
			lockConnection = null;
			lockHeld = false;
		}
	}

	@Override
	public UUID beginSyncRun(Instant startedAt, int instrumentCount) {
		try {
			UUID id = UUID.randomUUID();
			int inserted = jdbc.update("""
				INSERT INTO market_data_sync_runs (id, started_at, status, instrument_count)
				VALUES (?, ?, 'RUNNING', ?)
				""", id, Timestamp.from(startedAt), instrumentCount);
			if (inserted != 1) {
				throw new IllegalStateException("同步运行记录写入未生效。");
			}
			return id;
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public void completeSyncRun(
		UUID runId, Instant completedAt, String status, int succeededCount, int failedCount,
		String outcome, String errorSummary) {
		try {
			int updated = jdbc.update("""
				UPDATE market_data_sync_runs
				SET completed_at = ?, status = ?, succeeded_count = ?, failed_count = ?, outcome = ?,
					error_summary = ?
				WHERE id = ? AND status = 'RUNNING'
				""", Timestamp.from(completedAt), status, succeededCount, failedCount, outcome, errorSummary, runId);
			if (updated != 1) {
				throw new IllegalStateException("同步运行记录结束写入未生效。");
			}
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public boolean reserveQuotaCall(LocalDate usageDate, int callLimit) {
		if (usageDate == null || callLimit < 1) {
			throw new IllegalArgumentException("同步配额参数无效。");
		}
		try {
			Integer reserved = jdbc.query("""
				INSERT INTO market_data_daily_quotas (usage_date, used_calls, call_limit)
				VALUES (?, 1, ?)
				ON CONFLICT (usage_date) DO UPDATE
				SET used_calls = market_data_daily_quotas.used_calls + 1
				WHERE market_data_daily_quotas.used_calls < market_data_daily_quotas.call_limit
				RETURNING used_calls
				""", result -> result.next() ? result.getInt(1) : null, java.sql.Date.valueOf(usageDate), callLimit);
			return reserved != null;
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	@Override
	public int usedQuotaCalls(LocalDate usageDate) {
		if (usageDate == null) {
			return 0;
		}
		try {
			Integer used = jdbc.queryForObject(
				"SELECT used_calls FROM market_data_daily_quotas WHERE usage_date = ?",
				Integer.class, java.sql.Date.valueOf(usageDate));
			return used == null ? 0 : used;
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			return 0;
		} catch (RuntimeException exception) {
			throw persistence(exception);
		}
	}

	private static MarketDataPersistenceException persistence(Throwable exception) {
		return exception instanceof MarketDataPersistenceException failure
			? failure : new MarketDataPersistenceException(exception);
	}
}
