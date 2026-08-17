package app.ziji.liability.application;

import java.util.UUID;

import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;

/** 独立负债详情的深 interface：隐藏权限、字段矩阵、版本、事务与幂等重放。 */
public interface LiabilityDetailUseCase {

	LiabilityDetail get(UUID userId, UUID accountId);

	/** HTTP 边界在解析条件头与业务字段前先完成安全前置。 */
	void authorizeWrite(UUID userId, UUID accountId);

	LiabilityDetailWriteResult put(
		UUID userId,
		UUID accountId,
		LiabilityDetailPutCondition condition,
		LiabilityDetailValues values,
		String idempotencyKey);

	LiabilityDetailWriteResult patch(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		LiabilityDetailPatch patch,
		String idempotencyKey);
}
