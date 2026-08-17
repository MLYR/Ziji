package app.ziji.ledger.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.UUID;

import app.ziji.ledger.application.TransactionKeysetPosition;
import app.ziji.ledger.application.TransactionQuery;
import app.ziji.ledger.application.TransactionQueryValidationException;
import app.ziji.ledger.domain.TransactionType;
import org.junit.jupiter.api.Test;

/** 游标必须对 user、所有筛选条件和固定排序定义 fail-closed。 */
class AesGcmTransactionCursorCodecTests {

	@Test
	void bindsUserAndFiltersAndRejectsTampering() {
		AesGcmTransactionCursorCodec codec = new AesGcmTransactionCursorCodec(new byte[32], new SecureRandom());
		UUID user = UUID.randomUUID();
		TransactionQuery query = new TransactionQuery(UUID.randomUUID(), TransactionType.EXPENSE,
			LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 17), UUID.randomUUID());
		TransactionKeysetPosition position = new TransactionKeysetPosition(LocalDate.of(2026, 8, 15), UUID.randomUUID());
		String cursor = codec.encode(user, query, position);

		assertEquals(position, codec.decode(user, query, cursor));
		assertThrows(TransactionQueryValidationException.class,
			() -> codec.decode(UUID.randomUUID(), query, cursor));
		assertThrows(TransactionQueryValidationException.class,
			() -> codec.decode(user, new TransactionQuery(query.accountId(), TransactionType.INCOME,
				query.dateFrom(), query.dateTo(), query.categoryId()), cursor));
		assertThrows(TransactionQueryValidationException.class,
			() -> codec.decode(user, query, cursor.substring(0, cursor.length() - 1)
				+ (cursor.endsWith("A") ? "B" : "A")));
	}
}
