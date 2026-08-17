package app.ziji.liability.application;

import java.util.Optional;
import java.util.UUID;

import app.ziji.liability.domain.LiabilityDetail;

/** liability_details 的读写 seam；唯一行和 version 条件由 PostgreSQL adapter 保证。 */
public interface LiabilityDetailStore {

	Optional<LiabilityDetail> findByAccountId(UUID accountId);

	Optional<LiabilityDetail> lockByAccountId(UUID accountId);

	boolean insertIfAbsent(LiabilityDetail detail);

	Optional<LiabilityDetail> updateIfVersion(LiabilityDetail detail, int expectedVersion);
}
