package app.ziji.account.domain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 账户聚合的 class/type 矩阵、币种、文本边界和创建/恢复入口测试。 */
class AccountTests {

	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final UUID CREATED_BY = UUID.fromString("00000000-0000-0000-0000-000000000402");
	private static final Instant CREATED_AT = Instant.parse("2026-08-15T01:02:03Z");
	private static final Set<String> ALLOWED_PAIRS = Set.of(
		"ASSET:BANK", "ASSET:WECHAT", "ASSET:ALIPAY", "ASSET:CASH", "ASSET:OTHER",
		"INVESTMENT:BROKERAGE", "INVESTMENT:OTHER",
		"LIABILITY:CREDIT_CARD", "LIABILITY:LOAN", "LIABILITY:OTHER");

	@Test
	void acceptsEveryLegalClassAndTypePair() {
		for (AccountClass accountClass : AccountClass.values()) {
			for (AccountType accountType : AccountType.values()) {
				if (!ALLOWED_PAIRS.contains(accountClass.name() + ":" + accountType.name())) {
					continue;
				}
				Account account = Account.create(
					ACCOUNT_ID, accountClass, accountType, "合法账户", null,
					AccountCurrency.CNY, null, CREATED_BY, CREATED_AT);
				assertEquals(accountClass, account.accountClass());
				assertEquals(accountType, account.accountType());
			}
		}
	}

	@Test
	void rejectsEveryIllegalClassAndTypePair() {
		for (AccountClass accountClass : AccountClass.values()) {
			for (AccountType accountType : AccountType.values()) {
				if (ALLOWED_PAIRS.contains(accountClass.name() + ":" + accountType.name())) {
					continue;
				}
				assertThrows(AccountDomainException.class, () -> Account.create(
					ACCOUNT_ID, accountClass, accountType, "非法账户", null,
					AccountCurrency.CNY, null, CREATED_BY, CREATED_AT));
				assertThrows(AccountDomainException.class, () -> Account.restore(
					ACCOUNT_ID, accountClass, accountType, "非法账户", null,
					AccountCurrency.CNY, null, AccountStatus.ACTIVE, null,
					CREATED_BY, CREATED_AT, CREATED_AT, 1));
			}
		}
	}

	@Test
	void acceptsAllFiveCurrenciesAndRejectsUnknownCodes() {
		for (AccountCurrency currency : AccountCurrency.values()) {
			Account account = Account.create(
				ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "币种账户", null,
				currency, null, CREATED_BY, CREATED_AT);
			assertEquals(currency, account.currency());
			assertEquals(currency, AccountCurrency.fromCode(currency.name()));
		}
		assertThrows(AccountDomainException.class, () -> AccountCurrency.fromCode(null));
		assertThrows(AccountDomainException.class, () -> AccountCurrency.fromCode(" "));
		assertThrows(AccountDomainException.class, () -> AccountCurrency.fromCode("cny"));
		assertThrows(AccountDomainException.class, () -> AccountCurrency.fromCode("GBP"));
	}

	@Test
	void rejectsMissingRequiredFieldsAndKeepsOriginalText() {
		assertThrows(AccountDomainException.class, () -> Account.create(
			null, AccountClass.ASSET, AccountType.BANK, "工资卡", null,
			AccountCurrency.CNY, null, CREATED_BY, CREATED_AT));
		assertThrows(AccountDomainException.class, () -> Account.create(
			ACCOUNT_ID, null, AccountType.BANK, "工资卡", null,
			AccountCurrency.CNY, null, CREATED_BY, CREATED_AT));
		assertThrows(AccountDomainException.class, () -> Account.create(
			ACCOUNT_ID, AccountClass.ASSET, null, "工资卡", null,
			AccountCurrency.CNY, null, CREATED_BY, CREATED_AT));
		assertThrows(AccountDomainException.class, () -> Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "工资卡", null,
			null, null, CREATED_BY, CREATED_AT));
		assertThrows(AccountDomainException.class, () -> Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "工资卡", null,
			AccountCurrency.CNY, null, null, CREATED_AT));
		assertThrows(AccountDomainException.class, () -> Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, "工资卡", null,
			AccountCurrency.CNY, null, CREATED_BY, null));
		Account account = Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.BANK, " 工资卡 ", " 示例银行 ",
			AccountCurrency.USD, " 备注 001 ", CREATED_BY, CREATED_AT);
		assertEquals(" 工资卡 ", account.name());
		assertEquals(" 示例银行 ", account.institution());
		assertEquals(" 备注 001 ", account.note());
	}

	@Test
	void rejectsNameInstitutionAndNoteOutsideFrozenBounds() {
		String supplementaryCharacter = "💳";
		assertThrows(AccountDomainException.class, () -> createName(null));
		assertThrows(AccountDomainException.class, () -> createName(""));
		assertThrows(AccountDomainException.class, () -> createName("   "));
		assertEquals("A", createName("A").name());
		assertEquals("A".repeat(100), createName("A".repeat(100)).name());
		assertThrows(AccountDomainException.class, () -> createName("A".repeat(101)));
		assertEquals(supplementaryCharacter.repeat(100),
			createName(supplementaryCharacter.repeat(100)).name());
		assertThrows(AccountDomainException.class,
			() -> createName(supplementaryCharacter.repeat(101)));
		assertNull(createInstitution(null).institution());
		assertThrows(AccountDomainException.class, () -> createInstitution(""));
		assertThrows(AccountDomainException.class, () -> createInstitution("   "));
		assertEquals("B".repeat(120), createInstitution("B".repeat(120)).institution());
		assertThrows(AccountDomainException.class, () -> createInstitution("B".repeat(121)));
		assertEquals(supplementaryCharacter.repeat(120),
			createInstitution(supplementaryCharacter.repeat(120)).institution());
		assertThrows(AccountDomainException.class,
			() -> createInstitution(supplementaryCharacter.repeat(121)));
		assertNull(createNote(null).note());
		assertEquals("", createNote("").note());
		assertEquals("C".repeat(2000), createNote("C".repeat(2000)).note());
		assertThrows(AccountDomainException.class, () -> createNote("C".repeat(2001)));
		assertEquals(supplementaryCharacter.repeat(2000),
			createNote(supplementaryCharacter.repeat(2000)).note());
		assertThrows(AccountDomainException.class,
			() -> createNote(supplementaryCharacter.repeat(2001)));
	}

	@Test
	void createDefaultsToActiveVersionOneAndMatchingTimestamps() {
		Account account = Account.create(
			ACCOUNT_ID, AccountClass.INVESTMENT, AccountType.BROKERAGE, "券商", null,
			AccountCurrency.HKD, null, CREATED_BY, CREATED_AT);
		assertEquals(AccountStatus.ACTIVE, account.status());
		assertNull(account.archivedAt());
		assertEquals(1, account.version());
		assertEquals(CREATED_AT, account.createdAt());
		assertEquals(CREATED_AT, account.updatedAt());
	}

	@Test
	void restoreEnforcesArchivedAtPairingAndPreservesHistory() {
		Instant archivedAt = Instant.parse("2026-08-15T04:00:00Z");
		Instant updatedAt = Instant.parse("2026-08-15T04:00:00Z");
		Account archived = Account.restore(
			ACCOUNT_ID, AccountClass.LIABILITY, AccountType.LOAN, "借款", "亲友",
			AccountCurrency.EUR, "历史行", AccountStatus.ARCHIVED, archivedAt,
			CREATED_BY, CREATED_AT, updatedAt, 3);
		assertEquals(AccountStatus.ARCHIVED, archived.status());
		assertEquals(archivedAt, archived.archivedAt());
		assertEquals(3, archived.version());
		assertEquals(updatedAt, archived.updatedAt());
		assertThrows(AccountDomainException.class, () -> Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.CASH, "现金", null,
			AccountCurrency.CNY, null, AccountStatus.ACTIVE, archivedAt,
			CREATED_BY, CREATED_AT, CREATED_AT, 1));
		assertThrows(AccountDomainException.class, () -> Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.CASH, "现金", null,
			AccountCurrency.CNY, null, AccountStatus.ARCHIVED, null,
			CREATED_BY, CREATED_AT, CREATED_AT, 1));
		assertThrows(AccountDomainException.class, () -> Account.restore(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.CASH, "现金", null,
			AccountCurrency.CNY, null, AccountStatus.ACTIVE, null,
			CREATED_BY, CREATED_AT, CREATED_AT, 0));
	}

	@Test
	void domainTypesHaveNoFloatingPointOrBalanceFields() {
		for (Class<?> type : Set.of(
			Account.class, AccountClass.class, AccountType.class, AccountStatus.class,
			AccountCurrency.class, AccountDomainException.class)) {
			assertFalse(hasFloatingPointSurface(type), type.getName());
			for (Field field : type.getDeclaredFields()) {
				assertFalse(field.getName().toLowerCase().contains("balance"), field.getName());
				assertFalse(field.getName().toLowerCase().contains("frozen"), field.getName());
			}
		}
	}

	private static Account createName(String name) {
		return Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.ALIPAY, name, null,
			AccountCurrency.CNY, null, CREATED_BY, CREATED_AT);
	}

	private static Account createInstitution(String institution) {
		return Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.WECHAT, "微信", institution,
			AccountCurrency.CNY, null, CREATED_BY, CREATED_AT);
	}

	private static Account createNote(String note) {
		return Account.create(
			ACCOUNT_ID, AccountClass.ASSET, AccountType.OTHER, "其他", null,
			AccountCurrency.JPY, note, CREATED_BY, CREATED_AT);
	}

	private static boolean hasFloatingPointSurface(Class<?> type) {
		return Arrays.stream(type.getDeclaredFields()).anyMatch(AccountTests::isFloatingPoint)
			|| Arrays.stream(type.getDeclaredConstructors()).anyMatch(AccountTests::hasFloatingPointParameter)
			|| Arrays.stream(type.getDeclaredMethods()).anyMatch(AccountTests::hasFloatingPointParameter);
	}

	private static boolean isFloatingPoint(Field field) {
		return field.getType() == double.class || field.getType() == float.class
			|| field.getType() == Double.class || field.getType() == Float.class;
	}

	private static boolean hasFloatingPointParameter(Constructor<?> constructor) {
		return Arrays.stream(constructor.getParameterTypes()).anyMatch(AccountTests::isFloatingPointType);
	}

	private static boolean hasFloatingPointParameter(Method method) {
		return Arrays.stream(method.getParameterTypes()).anyMatch(AccountTests::isFloatingPointType)
			|| isFloatingPointType(method.getReturnType());
	}

	private static boolean isFloatingPointType(Class<?> type) {
		return type == double.class || type == float.class || type == Double.class || type == Float.class;
	}
}
