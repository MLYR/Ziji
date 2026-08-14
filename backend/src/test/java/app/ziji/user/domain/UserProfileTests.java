package app.ziji.user.domain;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 用户资料值对象的固定枚举、IANA 时区和不可变部分更新基线。 */
class UserProfileTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

	@Test
	void supportsAllV1CurrenciesFormatsAndStatuses() {
		for (BaseCurrency currency : BaseCurrency.values()) {
			for (AmountFormat format : AmountFormat.values()) {
				for (UserStatus status : UserStatus.values()) {
					UserProfile profile = profile(currency, format, status, 1);
					assertEquals(currency, profile.baseCurrency());
					assertEquals(format, profile.amountFormat());
					assertEquals(status, profile.status());
				}
			}
		}
	}

	@Test
	void rejectsNullRequiredFieldsAndNonPositiveVersion() {
		assertThrows(UserDomainException.class,
			() -> new UserProfile(null, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
				BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, 1));
		assertThrows(UserDomainException.class,
			() -> new UserProfile(USER_ID, null, "昵称", ZoneId.of("Asia/Shanghai"),
				BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, 1));
		assertThrows(UserDomainException.class,
			() -> new UserProfile(USER_ID, "user@example.com", "昵称", null,
				BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, 1));
		assertThrows(UserDomainException.class,
			() -> new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
				BaseCurrency.CNY, "zh-CN", AmountFormat.STANDARD, UserStatus.ACTIVE, 0));
	}

	@Test
	void rejectsLocaleOutsideTwoToSixteenCharacters() {
		assertThrows(UserDomainException.class,
			() -> new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
				BaseCurrency.CNY, "a", AmountFormat.STANDARD, UserStatus.ACTIVE, 1));
		assertThrows(UserDomainException.class,
			() -> new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
				BaseCurrency.CNY, "a".repeat(17), AmountFormat.STANDARD, UserStatus.ACTIVE, 1));
		assertEquals("ab", new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
			BaseCurrency.CNY, "ab", AmountFormat.STANDARD, UserStatus.ACTIVE, 1).locale());
		assertEquals("a".repeat(16), new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
			BaseCurrency.CNY, "a".repeat(16), AmountFormat.STANDARD, UserStatus.ACTIVE, 1).locale());
	}

	@Test
	void rejectsEmptyPatchAtConstruction() {
		assertThrows(UserDomainException.class,
			() -> new UserProfilePatch(
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
	}

	@Test
	void appliesPartialUpdateWithoutChangingIdentityOrStatus() {
		UserProfile original = profile(BaseCurrency.CNY, AmountFormat.STANDARD, UserStatus.ACTIVE, 7);
		UserProfile updated = original.apply(new UserProfilePatch(
			Optional.of("新昵称"), Optional.empty(), Optional.of(BaseCurrency.USD),
			Optional.empty(), Optional.empty()));

		assertEquals(USER_ID, updated.id());
		assertEquals(original.email(), updated.email());
		assertEquals("新昵称", updated.nickname());
		assertEquals(ZoneId.of("Asia/Shanghai"), updated.timezone());
		assertEquals(BaseCurrency.USD, updated.baseCurrency());
		assertEquals(AmountFormat.STANDARD, updated.amountFormat());
		assertEquals(UserStatus.ACTIVE, updated.status());
		assertEquals(8, updated.version());
	}

	@Test
	void exposesQuotedPositiveEtag() {
		assertEquals("\"7\"", profile(BaseCurrency.CNY, AmountFormat.STANDARD, UserStatus.ACTIVE, 7).etag());
	}

	private static UserProfile profile(
		BaseCurrency currency,
		AmountFormat format,
		UserStatus status,
		int version) {
		return new UserProfile(USER_ID, "user@example.com", "昵称", ZoneId.of("Asia/Shanghai"),
			currency, "zh-CN", format, status, version);
	}
}
