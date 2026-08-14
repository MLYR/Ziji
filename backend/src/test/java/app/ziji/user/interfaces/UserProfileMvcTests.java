package app.ziji.user.interfaces;

import java.time.ZoneId;
import java.util.UUID;

import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.UserProfileUseCase;
import app.ziji.user.application.UserVersionConflictException;
import app.ziji.user.domain.AmountFormat;
import app.ziji.user.domain.BaseCurrency;
import app.ziji.user.domain.UserProfile;
import app.ziji.user.domain.UserProfilePatch;
import app.ziji.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 当前用户路由的 merge-patch、If-Match、ETag 和 VERSION_CONFLICT 契约测试。 */
class UserProfileMvcTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000303");

	@Test
	void getReturnsUserEnvelopeAndEtag() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(get("/api/v1/users/me").principal(principal()))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"7\""))
			.andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
			.andExpect(jsonPath("$.data.baseCurrency").value("CNY"))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));
	}

	@Test
	void patchReturnsUpdatedEnvelopeAndEtag() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me")
				.principal(principal())
				.header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"8\""))
			.andExpect(jsonPath("$.data.nickname").value("新昵称"));
	}

	@Test
	void patchRejectsMissingIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal())
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsEmptyAndExplicitNullFields() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":null}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void patchRejectsUnknownFieldWithValidIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json").content("{\"unknown\":\"x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsUnquotedIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "7")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsZeroIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"0\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsNegativeIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"-1\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsWeakIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "W/\"7\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsWildcardIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "*")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsOverflowIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"2147483648\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsCommaSeparatedIfMatch() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\", \"8\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsDuplicateIfMatchHeaders() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"", "\"8\"")
				.contentType("application/merge-patch+json").content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsInvalidTimezone() throws Exception {
		MockMvc invalidTimezone = mvc(new FakeUseCase(profile(7)));
		invalidTimezone.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"timezone\":\"Not/A/Timezone\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void patchRejectsInvalidBaseCurrency() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"baseCurrency\":\"ABC\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void patchRejectsInvalidAmountFormat() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"amountFormat\":\"INVALID\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void staleIfMatchReturnsBoundedVersionConflict() throws Exception {

		MockMvc conflict = mvc(new ConflictingUseCase(profile(8)));
		conflict.perform(patch("/api/v1/users/me").principal(principal()).header("If-Match", "\"7\"")
				.contentType("application/merge-patch+json")
				.content("{\"nickname\":\"新昵称\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
			.andExpect(jsonPath("$.versionConflict.currentVersion").value(8))
			.andExpect(jsonPath("$.versionConflict.currentEtag").value("\"8\""))
			.andExpect(jsonPath("$.versionConflict.resourceLocation").value("/api/v1/users/me"))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void missingPrincipalIsRejected() throws Exception {
		MockMvc mvc = mvc(new FakeUseCase(profile(7)));

		mvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	private MockMvc mvc(UserProfileUseCase useCase) {
		CurrentUserIdResolver resolver = principal -> {
			if (principal == null) {
				throw new app.ziji.user.application.UserAuthenticationException();
			}
			return USER_ID;
		};
		return MockMvcBuilders.standaloneSetup(new UserProfileController(useCase, resolver))
			.setControllerAdvice(new UserApiExceptionHandler())
			.build();
	}

	private java.security.Principal principal() {
		return () -> USER_ID.toString();
	}

	private static UserProfile profile(int version) {
		return new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
			BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, version);
	}

	private static final class FakeUseCase implements UserProfileUseCase {
		private UserProfile profile;

		private FakeUseCase(UserProfile profile) {
			this.profile = profile;
		}

		@Override
		public UserProfile getCurrentUser(UUID userId) {
			return profile;
		}

		@Override
		public UserProfile updateCurrentUser(UUID userId, int expectedVersion, UserProfilePatch patch) {
			profile = profile.apply(patch);
			return profile;
		}
	}

	private static final class ConflictingUseCase implements UserProfileUseCase {
		private final UserProfile current;

		private ConflictingUseCase(UserProfile current) {
			this.current = current;
		}

		@Override
		public UserProfile getCurrentUser(UUID userId) {
			return current;
		}

		@Override
		public UserProfile updateCurrentUser(UUID userId, int expectedVersion, UserProfilePatch patch) {
			throw new UserVersionConflictException(current);
		}
	}
}
