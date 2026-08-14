package app.ziji.user.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import org.springframework.stereotype.Service;

/** 用户资料用例；只更新 users 设置列，不修改历史账务或认证敏感字段。 */
@Service
public class UserProfileApplicationService implements UserProfileUseCase {

	private final UserProfileStore store;
	private final TransactionRunner transactionRunner;
	private final Clock clock;

	public UserProfileApplicationService(
		UserProfileStore store,
		TransactionRunner transactionRunner,
		Clock clock) {
		this.store = required(store, "用户资料存储");
		this.transactionRunner = required(transactionRunner, "事务入口");
		this.clock = required(clock, "时钟");
	}

	@Override
	public UserProfile getCurrentUser(UUID userId) {
		if (userId == null) {
			throw new UserAuthenticationException();
		}
		return store.findById(userId).orElseThrow(UserAuthenticationException::new);
	}

	@Override
	public UserProfile updateCurrentUser(
		UUID userId,
		int expectedVersion,
		UserProfilePatch patch) {
		if (userId == null) {
			throw new UserAuthenticationException();
		}
		// 应用层也拒绝空 patch，避免非 HTTP 调用绕过 Controller 造成版本/时间无意义变化。
		if (expectedVersion < 1 || patch == null || patch.isEmpty()) {
			throw new UserValidationException("用户资料更新请求无效。");
		}

		return transactionRunner.required(() -> {
			Instant now = clock.instant();
			return store.updateIfVersion(userId, expectedVersion, patch, now)
				.orElseGet(() -> currentAfterMiss(userId));
		});
	}

	private UserProfile currentAfterMiss(UUID userId) {
		UserProfile current = store.findById(userId).orElseThrow(UserAuthenticationException::new);
		throw new UserVersionConflictException(current);
	}

	private static <T> T required(T value, String name) {
		if (value == null) {
			throw new UserValidationException(name + "不能为空。");
		}
		return value;
	}
}
