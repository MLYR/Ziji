package app.ziji.liability.application;

import app.ziji.liability.domain.LiabilityDetail;

/** PUT/PATCH 的已执行或安全重放结果。 */
public record LiabilityDetailWriteResult(LiabilityDetail detail, int status) {

	public LiabilityDetailWriteResult {
		if (detail == null || status != 200 && status != 201) {
			throw new IllegalArgumentException("负债详情写入结果无效。");
		}
	}
}
