package app.ziji.user.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.user.domain.AmountFormat;
import app.ziji.user.domain.BaseCurrency;
import app.ziji.user.domain.UserDomainException;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import app.ziji.user.domain.UserStatus;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 应用层测试版本条件更新、部分字段保留和冲突详情来源。 */
class UserProfileApplicationServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000302");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

	@Test
	void partialUpdateKeepsMissingFieldsAndIncrementsVersion() {
		FakeStore store = new FakeStore(profile(4));
		UserProfileApplicationService service = service(store);

		UserProfile updated = service.updateCurrentUser(USER_ID, 4, new UserProfilePatch(
			Optional.empty(), Optional.of(ZoneId.of("Asia/Shanghai")), Optional.empty(),
			Optional.of("en-US"), Optional.empty()));

		assertEquals("昵称", updated.nickname());
		assertEquals(BaseCurrency.CNY, updated.baseCurrency());
		assertEquals("en-US", updated.locale());
		assertEquals(5, updated.version());
	}

	@Test
	void staleVersionReturnsCurrentSafeProfile() {
		FakeStore store = new FakeStore(profile(6));
		UserProfileVersionConflictAssertion assertion = new UserProfileVersionConflictAssertion(
			service(store), USER_ID);

		UserVersionConflictException exception = assertion.updateWithStaleVersion();

		assertEquals(6, exception.current().version());
		assertEquals("\"6\"", exception.current().etag());
		assertEquals(USER_ID, exception.current().id());
	}

	@Test
	void missingUserDoesNotCreateAProfile() {
		FakeStore store = new FakeStore(null);
		UserProfileApplicationService service = service(store);

		assertThrows(UserAuthenticationException.class, () -> service.getCurrentUser(USER_ID));
		assertEquals(0, store.profiles.size());
	}

	@Test
	void nullPatchIsRejectedBeforeStoreUpdate() {
		FakeStore store = new FakeStore(profile(4));
		UserProfileApplicationService service = service(store);

		assertThrows(UserValidationException.class,
			() -> service.updateCurrentUser(USER_ID, 4, null));
		assertEquals(0, store.updateCalls);
		assertNull(store.lastUpdatedAt);
		assertEquals(4, store.profiles.get(USER_ID).version());
	}

	@Test
	void emptyPatchCannotReachStoreUpdate() {
		FakeStore store = new FakeStore(profile(4));
		UserProfileApplicationService service = service(store);

		assertThrows(UserDomainException.class, () -> service.updateCurrentUser(USER_ID, 4,
			new UserProfilePatch(
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		assertEquals(0, store.updateCalls);
		assertNull(store.lastUpdatedAt);
		assertEquals(4, store.profiles.get(USER_ID).version());
	}

	private static UserProfileApplicationService service(FakeStore store) {
		return new UserProfileApplicationService(store, new DirectTransactionRunner(), CLOCK);
	}

	private static UserProfile profile(int version) {
		return new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
			BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, version);
	}

	private static final class FakeStore implements UserProfileStore {
		private final Map<UUID, UserProfile> profiles = new HashMap<>();
		private int updateCalls;
		private Instant lastUpdatedAt;

		private FakeStore(UserProfile profile) {
			if (profile != null) {
				profiles.put(profile.id(), profile);
			}
		}

		@Override
		public Optional<UserProfile> findById(UUID userId) {
			return Optional.ofNullable(profiles.get(userId));
		}

		@Override
		public Optional<UserProfile> updateIfVersion(
			UUID userId, int expectedVersion, UserProfilePatch patch, Instant updatedAt) {
			updateCalls++;
			lastUpdatedAt = updatedAt;
			UserProfile current = profiles.get(userId);
			if (current == null || current.version() != expectedVersion) {
				return Optional.empty();
			}
			UserProfile updated = current.apply(patch);
			profiles.put(userId, updated);
			return Optional.of(updated);
		}
	}

	private static final class DirectTransactionRunner implements app.ziji.shared.application.TransactionRunner {
		@Override
		public <T> T required(java.util.function.Supplier<T> action) {
			return action.get();
		}

		@Override
		public void required(Runnable action) {
			action.run();
		}
	}

	private static final class UserProfileVersionConflictAssertion {
		private final UserProfileApplicationService service;
		private final UUID userId;

		private UserProfileVersionConflictAssertion(UserProfileApplicationService service, UUID userId) {
			this.service = service;
			this.userId = userId;
		}

		private UserVersionConflictException updateWithStaleVersion() {
			return assertThrows(UserVersionConflictException.class,
				() -> service.updateCurrentUser(userId, 5, new UserProfilePatch(
					Optional.of("新昵称"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
		}
	}
}
