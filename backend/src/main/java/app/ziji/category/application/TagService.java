package app.ziji.category.application;

import java.time.Clock;
import java.time.Instant;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.shared.application.TransactionRunner;

/**
 * 个人标签应用服务；标签只属于 owner，创建和修改都通过 owner 行事实校验。
 */
public class TagService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;
	private static final int MAXIMUM_NAME_LENGTH = 80;

	private final TagQueryReadPort queries;
	private final TagCommandStore commands;
	private final TagCursorCodec cursors;
	private final TransactionRunner transactions;
	private final Clock clock;
	private final Supplier<UUID> idGenerator;

	public TagService(
		TagQueryReadPort queries,
		TagCommandStore commands,
		TagCursorCodec cursors,
		TransactionRunner transactions,
		Clock clock,
		Supplier<UUID> idGenerator) {
		if (queries == null || commands == null || cursors == null || transactions == null
			|| clock == null || idGenerator == null) {
			throw new TagValidationException();
		}
		this.queries = queries;
		this.commands = commands;
		this.cursors = cursors;
		this.transactions = transactions;
		this.clock = clock;
		this.idGenerator = idGenerator;
	}

	/** 创建个人标签；同一 owner 的规范化名称唯一，并发冲突由唯一索引兜底。 */
	public Optional<TagSnapshot> createTag(UUID userId, TagCommand command) {
		if (userId == null || command == null) {
			throw new TagValidationException();
		}
		String name = normalizeName(command.name());
		return transactions.required(() -> {
			if (queries.existsNameConflict(userId, nameNormalized(name), null)) {
				// 可预期重名不能抛出事务异常，否则会连同外层幂等失败终态一起回滚。
				return Optional.empty();
			}
			Instant now = clock.instant();
			TagSnapshot tag = new TagSnapshot(
				idGenerator.get(), userId, name, nameNormalized(name), TagStatus.ACTIVE, now, now, 1);
			try {
				// 并发唯一约束在 savepoint 内回滚，外层幂等终态仍能原子记录为 409。
				return transactions.nested(() -> {
					commands.insert(tag);
					return Optional.of(tag);
				});
			} catch (TagNameConflictException exception) {
				return Optional.empty();
			}
		});
	}

	/** merge-patch 修改标签；INACTIVE 标签不能再关联新交易。 */
	public TagSnapshot updateTag(UUID userId, UUID tagId, TagUpdateCommand command, int expectedVersion) {
		if (userId == null || tagId == null || command == null || expectedVersion < 1) {
			throw new TagValidationException();
		}
		return transactions.required(() -> {
			TagSnapshot current = lockOwnedTag(userId, tagId);
			if (current.version() != expectedVersion) {
				throw new TagVersionConflictException(current.version());
			}
			String name = command.name() == null ? current.name() : normalizeName(command.name());
			TagStatus status = command.status() == null ? current.status() : command.status();
			// 修改状态时排除自身，避免“只停用”被误判为同名冲突。
			if (queries.existsNameConflict(userId, nameNormalized(name), current.id())) {
				throw new TagNameConflictException();
			}
			TagSnapshot updated = new TagSnapshot(
				current.id(), current.ownerUserId(), name, nameNormalized(name), status,
				current.createdAt(), clock.instant(), current.version() + 1);
			return commands.updateIfVersion(updated, expectedVersion)
				.orElseThrow(() -> new TagVersionConflictException(current.version()));
		});
	}

	public TagPage listTags(UUID userId, Integer requestedLimit, String cursor) {
		if (userId == null) {
			throw new TagValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new TagValidationException();
		}
		TagKeysetPosition after = cursor == null ? null : cursors.decode(userId, cursor);
		var rows = queries.listOwner(userId, after, limit + 1);
		boolean hasMore = rows.size() > limit;
		List<TagSnapshot> page = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;
		String nextCursor = hasMore && !page.isEmpty()
			? cursors.encode(userId, new TagKeysetPosition(page.getLast().createdAt(), page.getLast().id()))
			: null;
		return new TagPage(page, nextCursor, hasMore);
	}

	public TagSnapshot getVisibleTag(UUID userId, UUID tagId) {
		if (userId == null || tagId == null) {
			throw new TagValidationException();
		}
		// 幂等回放读取复用行锁查询，必须保留事务边界以满足 PostgreSQL 的 FOR UPDATE 语义。
		return transactions.required(() -> lockOwnedTag(userId, tagId));
	}

	private TagSnapshot lockOwnedTag(UUID userId, UUID tagId) {
		TagSnapshot tag = queries.findByIdForUpdate(tagId).orElseThrow(TagNotVisibleException::new);
		if (!userId.equals(tag.ownerUserId())) {
			throw new TagNotVisibleException();
		}
		return tag;
	}

	private static String normalizeName(String value) {
		if (value == null) {
			throw new TagValidationException();
		}
		// NFKC 与分类名称保持同一入口语义，trim 后仍不得为空或超长。
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAXIMUM_NAME_LENGTH) {
			throw new TagValidationException();
		}
		return normalized;
	}

	private static String nameNormalized(String value) {
		// 展示名保留大小写；唯一性与分类一致使用大小写无关名称。
		return value.toLowerCase(java.util.Locale.ROOT);
	}
}
