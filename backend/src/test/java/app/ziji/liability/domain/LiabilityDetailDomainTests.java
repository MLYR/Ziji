package app.ziji.liability.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiabilityDetailDomainTests {

	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");
	private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

	@Test
	void emptyProjectionHasSixNullFieldsVersionZeroAndStrongEtag() {
		LiabilityDetail detail = LiabilityDetail.empty(ACCOUNT_ID);

		assertNull(detail.interestRate());
		assertNull(detail.loanDate());
		assertNull(detail.dueDate());
		assertNull(detail.billingDay());
		assertNull(detail.repaymentDay());
		assertNull(detail.currentAmountDue());
		assertEquals(0, detail.version());
		assertEquals("\"0\"", detail.etag());
	}

	@Test
	void fourLiabilityTypesApplyTheFrozenFieldMatrix() {
		values("0.125", null, null, 8, 20, "123.45").validateFor("CREDIT_CARD", "CNY");
		values("0.125", "2026-01-01", "2027-01-01", null, 20, "123.45").validateFor("LOAN", "USD");
		values("0.125", "2026-01-01", "2027-01-01", null, 20, "123.45").validateFor("CONSUMER_LOAN", "EUR");
		values("0.125", "2026-01-01", "2027-01-01", 8, 20, "123.45").validateFor("OTHER", "HKD");

		assertThrows(LiabilityDetailException.BusinessRule.class,
			() -> values(null, "2026-01-01", null, null, null, null).validateFor("CREDIT_CARD", "CNY"));
		assertThrows(LiabilityDetailException.BusinessRule.class,
			() -> values(null, null, null, 8, null, null).validateFor("LOAN", "CNY"));
		assertThrows(LiabilityDetailException.BusinessRule.class,
			() -> values(null, null, null, 8, null, null).validateFor("CONSUMER_LOAN", "CNY"));
	}

	@Test
	void formatRangesAreValidationWhileRelationshipsAndCurrencyPrecisionAreBusinessRules() {
		assertThrows(LiabilityDetailException.Validation.class,
			() -> values("1.000000001", null, null, null, null, null));
		assertThrows(LiabilityDetailException.Validation.class,
			() -> values("-0.01", null, null, null, null, null));
		assertThrows(LiabilityDetailException.Validation.class,
			() -> values(null, null, null, 0, null, null));
		assertThrows(LiabilityDetailException.Validation.class,
			() -> values(null, null, null, null, 32, null));

		assertThrows(LiabilityDetailException.BusinessRule.class,
			() -> values(null, "2026-02-02", "2026-02-01", null, null, null).validateFor("LOAN", "CNY"));
		assertThrows(LiabilityDetailException.BusinessRule.class,
			() -> values(null, null, null, null, null, "10.01").validateFor("OTHER", "JPY"));
	}

	@Test
	void createReplaceAndPatchUseIndependentVersionsAndCanonicalDecimals() {
		LiabilityDetail created = LiabilityDetail.create(
			ACCOUNT_ID, values("0.05000000", null, null, 8, 20, "100.00"), NOW);
		assertEquals(1, created.version());
		assertEquals("0.05", created.interestRate().toPlainString());
		assertEquals("100", created.currentAmountDue().toPlainString());

		LiabilityDetail replaced = created.replace(
			values("0.06", null, null, 9, 21, "80.50"), NOW.plusSeconds(1));
		assertEquals(2, replaced.version());
		assertEquals("80.5", replaced.currentAmountDue().toPlainString());

		LiabilityDetail patched = replaced.patch(new LiabilityDetailPatch(
			false, null,
			false, null,
			false, null,
			true, null,
			true, 22,
			true, BigDecimal.ZERO), NOW.plusSeconds(2));
		assertEquals(3, patched.version());
		assertNull(patched.billingDay());
		assertEquals(22, patched.repaymentDay());
		assertEquals(BigDecimal.ZERO, patched.currentAmountDue());
	}

	private static LiabilityDetailValues values(
		String interestRate,
		String loanDate,
		String dueDate,
		Integer billingDay,
		Integer repaymentDay,
		String currentAmountDue) {
		return new LiabilityDetailValues(
			interestRate == null ? null : new BigDecimal(interestRate),
			loanDate == null ? null : LocalDate.parse(loanDate),
			dueDate == null ? null : LocalDate.parse(dueDate),
			billingDay,
			repaymentDay,
			currentAmountDue == null ? null : new BigDecimal(currentAmountDue));
	}
}
