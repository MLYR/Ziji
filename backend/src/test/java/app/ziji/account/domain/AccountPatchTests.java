package app.ziji.account.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账户资料部分更新在领域边界的字段区分、校验与不可变应用测试。 */
class AccountPatchTests {

	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000411");
	private static final UUID CREATED_BY = UUID.fromString("00000000-0000-0000-0000-000000000412");
	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-08-15T05:06:07Z");

	@Test
	void appliesNameAndInstitutionWithoutChangingIdentityOrStatus() {
		Account original = account("旧名称", "旧机构", 4);

		Account updated = original.apply(new AccountPatch(true, "新名称", true, "新机构"), UPDATED_AT);

		assertEquals(ACCOUNT_ID, updated.id());
		assertEquals(original.accountClass(), updated.accountClass());
		assertEquals(original.accountType(), updated.accountType());
		assertEquals("新名称", updated.name());
		assertEquals("新机构", updated.institution());
		assertEquals(original.currency(), updated.currency());
		assertEquals(original.note(), updated.note());
		assertEquals(original.status(), updated.status());
		assertEquals(original.archivedAt(), updated.archivedAt());
		assertEquals(original.createdBy(), updated.createdBy());
		assertEquals(original.createdAt(), updated.createdAt());
		assertEquals(UPDATED_AT, updated.updatedAt());
		assertEquals(5, updated.version());
	}

	@Test
	void absentInstitutionKeepsOriginalWhileExplicitNullClearsIt() {
		Account original = account("名称", "机构", 3);

		Account nameOnly = original.apply(new AccountPatch(true, "只改名称", false, null), UPDATED_AT);
		Account cleared = original.apply(new AccountPatch(false, null, true, null), UPDATED_AT);

		assertEquals("机构", nameOnly.institution());
		assertNull(cleared.institution());
		assertEquals("名称", cleared.name());
	}

	@Test
	void rejectsEmptyPatchAndInvalidNameOrInstitution() {
		Account original = account("名称", null, 2);

		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(false, null, false, null));
		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(true, null, false, null));
		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(true, " ", false, null));
		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(true, "A".repeat(101), false, null));
		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(false, null, true, " "));
		assertThrows(AccountDomainException.class,
			() -> new AccountPatch(false, null, true, "B".repeat(121)));
		assertThrows(AccountDomainException.class,
			() -> original.apply(new AccountPatch(true, "A".repeat(101), false, null), UPDATED_AT));
	}

	@Test
	void countsUnicodeCodePointsInsteadOfUtf16Units() {
		String supplementary = "💳";
		Account original = account("名称", "机构", 1);

		Account nameOk = original.apply(new AccountPatch(true, supplementary.repeat(100), false, null), UPDATED_AT);
		assertEquals(supplementary.repeat(100), nameOk.name());
		assertThrows(AccountDomainException.class,
			() -> original.apply(new AccountPatch(true, supplementary.repeat(101), false, null), UPDATED_AT));

		Account institutionOk = original.apply(new AccountPatch(false, null, true, supplementary.repeat(120)), UPDATED_AT);
		assertEquals(supplementary.repeat(120), institutionOk.institution());
		assertThrows(AccountDomainException.class,
			() -> original.apply(new AccountPatch(false, null, true, supplementary.repeat(121)), UPDATED_AT));
	}

	@Test
	void exposesPresenceWithoutConfusingAbsentAndNull() {
		AccountPatch nameOnly = new AccountPatch(true, "新名称", false, null);
		AccountPatch clearInstitution = new AccountPatch(false, null, true, null);

		assertTrue(nameOnly.hasName());
		assertFalse(nameOnly.hasInstitution());
		assertEquals("新名称", nameOnly.name());
		assertTrue(clearInstitution.hasInstitution());
		assertNull(clearInstitution.institution());
		assertFalse(nameOnly.isEmpty());
	}

	private static Account account(String name, String institution, int version) {
		return Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, name, institution,
			AccountCurrency.CNY, "备注", AccountStatus.ACTIVE, null,
			CREATED_BY, CREATED_AT, CREATED_AT, version);
	}
}
