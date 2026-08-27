package app.ziji.account.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.domain.LiquidityHold;

/** LiquidityHold 事实的账户内读写端口；行锁和 version 条件由 PostgreSQL 适配器执行。 */
public interface LiquidityHoldStore {

	List<LiquidityHold> listByAccount(UUID accountId, LiquidityHoldKeysetPosition after, int maximumRecords);

	Optional<LiquidityHold> findByAccountAndId(UUID accountId, UUID holdId);

	Optional<LiquidityHold> lockByAccountAndId(UUID accountId, UUID holdId);

	void insert(LiquidityHold hold);

	Optional<LiquidityHold> supersedeIfVersion(
		UUID accountId, UUID holdId, int expectedVersion, Instant endedAt, Instant updatedAt);

	Optional<LiquidityHold> releaseIfVersion(UUID accountId, UUID holdId, int expectedVersion, Instant now);

	List<LiquidityHold> findExpiredUnended(Instant asOf, int maximumRecords);

	Optional<LiquidityHold> expireIfVersion(
		UUID accountId, UUID holdId, int expectedVersion, Instant asOf, Instant finalizedAt);
}
