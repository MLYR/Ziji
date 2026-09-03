package app.ziji.statistics.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import app.ziji.account.application.AccountQueryReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 无账户时账户趋势不能组装空 values 点，否则 Point 构造会把 Dashboard 打成 500。 */
class StatisticsApplicationServiceEmptyAccountsTests {

	@Test
	void getAccountStatisticsWithoutAccountsReturnsEmptyPoints() {
		UUID userId = UUID.fromString("8e59505f-a20f-4a50-8245-f369cb33702b");
		AccountMembershipReadPort memberships = mock(AccountMembershipReadPort.class);
		when(memberships.listActiveMemberships(userId)).thenReturn(List.of());
		StatisticsApplicationService service = new StatisticsApplicationService(
			memberships,
			mock(AccountQueryReadPort.class),
			mock(StatisticsFactReadPort.class),
			(CurrentUserBaseCurrencyPort) ignored -> "CNY");

		StatisticsSeriesResult result = service.getAccountStatistics(
			userId, LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 29), "DAY");

		assertEquals("CNY", result.baseCurrency());
		assertEquals(1, result.valuationRevision());
		assertTrue(result.points().isEmpty());
	}
}
