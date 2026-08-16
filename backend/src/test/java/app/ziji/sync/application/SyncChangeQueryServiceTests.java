package app.ziji.sync.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯应用层验证 limit+1、sequence 边界、游标归属和新增记录后的稳定分页。 */
class SyncChangeQueryServiceTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000911");
	private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000912");

	@Test
	void pagesInStrictSequenceOrderAndKeepsEmptyContinuationStable() {
		FakePort port = new FakePort();
		for (long sequence = 1; sequence <= 5; sequence++) {
			port.add(USER_ID, change(sequence));
		}
		SyncChangeQueryService service = new SyncChangeQueryService(port, new FakeCursorCodec());

		SyncChangePage first = service.list(USER_ID, 2, null);
		SyncChangePage second = service.list(USER_ID, 2, first.nextCursor());
		SyncChangePage third = service.list(USER_ID, 2, second.nextCursor());
		SyncChangePage empty = service.list(USER_ID, 2, third.nextCursor());

		assertEquals(List.of(1L, 2L), sequences(first));
		assertEquals(List.of(3L, 4L), sequences(second));
		assertEquals(List.of(5L), sequences(third));
		assertFalse(third.hasMore());
		assertTrue(third.nextCursor().endsWith(":5"));
		assertTrue(empty.changes().isEmpty());
		assertEquals(third.nextCursor(), empty.nextCursor());
	}

	@Test
	void appliesDefaultAndMaximumLimitsAndRejectsInvalidValues() {
		FakePort port = new FakePort();
		for (long sequence = 1; sequence <= 55; sequence++) {
			port.add(USER_ID, change(sequence));
		}
		SyncChangeQueryService service = new SyncChangeQueryService(port, new FakeCursorCodec());

		assertEquals(50, service.list(USER_ID, null, null).changes().size());
		assertEquals(55, service.list(USER_ID, 200, null).changes().size());
		assertThrows(SyncQueryValidationException.class, () -> service.list(USER_ID, 0, null));
		assertThrows(SyncQueryValidationException.class, () -> service.list(USER_ID, 201, null));
		assertThrows(SyncQueryValidationException.class, () -> service.list(USER_ID, 1, "999"));
	}

	@Test
	void rejectsForeignCursorAndKeepsRecipientIsolation() {
		FakePort port = new FakePort();
		port.add(USER_ID, change(1));
		port.add(OTHER_USER_ID, change(1));
		SyncChangeQueryService service = new SyncChangeQueryService(port, new FakeCursorCodec());

		String cursor = service.list(USER_ID, 1, null).nextCursor();
		assertThrows(SyncQueryValidationException.class, () -> service.list(OTHER_USER_ID, 1, cursor));
	}

	private static SyncChange change(long sequence) {
		return new SyncChange(sequence, "TRANSACTION", UUID.randomUUID(), 1, "TOMBSTONE", 1, null);
	}

	private static List<Long> sequences(SyncChangePage page) {
		return page.changes().stream().map(SyncChange::sequence).toList();
	}

	private static final class FakePort implements SyncChangeReadPort {
		private final List<Row> rows = new ArrayList<>();

		void add(UUID userId, SyncChange change) {
			rows.add(new Row(userId, change));
		}

		@Override
		public List<SyncChange> listAfter(UUID recipientUserId, long sequenceExclusive, int maximumRows) {
			return rows.stream()
				.filter(row -> row.userId().equals(recipientUserId) && row.change().sequence() > sequenceExclusive)
				.sorted((left, right) -> Long.compare(left.change().sequence(), right.change().sequence()))
				.limit(maximumRows)
				.map(Row::change)
				.toList();
		}

		@Override
		public boolean containsSequence(UUID recipientUserId, long sequence) {
			return rows.stream().anyMatch(row -> row.userId().equals(recipientUserId)
				&& row.change().sequence() == sequence);
		}

		private record Row(UUID userId, SyncChange change) {
		}
	}

	private static final class FakeCursorCodec implements SyncCursorCodec {
		@Override
		public String encode(UUID userId, long sequence) {
			return userId + ":" + sequence;
		}

		@Override
		public long decode(UUID userId, String cursor) {
			try {
			String[] parts = cursor.split(":", -1);
			if (!userId.toString().equals(parts[0])) {
				throw new SyncQueryValidationException();
			}
			return Long.parseLong(parts[1]);
			} catch (RuntimeException exception) {
				throw new SyncQueryValidationException();
			}
		}
	}
}
