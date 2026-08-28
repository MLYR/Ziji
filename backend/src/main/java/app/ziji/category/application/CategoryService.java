package app.ziji.category.application;

import java.time.Clock;
import java.time.Instant;
import java.text.Normalizer;
import java.util.Optional;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.shared.application.TransactionRunner;

/**
 * 分类查询与创建应用服务。V1 固定两级：根分类可用，二级必须挂到 ACTIVE 根分类；
 * 默认、个人与当前 ACTIVE 账户级分类共同构成可见范围。
 */
public class CategoryService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAXIMUM_LIMIT = 200;
	private static final int MAXIMUM_NAME_LENGTH = 80;

	private final CategoryQueryReadPort queries;
	private final CategoryCommandStore commands;
	private final AccountMembershipReadPort memberships;
	private final CategoryCursorCodec cursors;
	private final TransactionRunner transactions;
	private final Clock clock;
	private final Supplier<UUID> idGenerator;

	public CategoryService(
		CategoryQueryReadPort queries,
		CategoryCommandStore commands,
		AccountMembershipReadPort memberships,
		CategoryCursorCodec cursors,
		TransactionRunner transactions,
		Clock clock,
		Supplier<UUID> idGenerator) {
		if (queries == null || commands == null || memberships == null || cursors == null
			|| transactions == null || clock == null || idGenerator == null) {
			throw new CategoryValidationException();
		}
		this.queries = queries;
		this.commands = commands;
		this.memberships = memberships;
		this.cursors = cursors;
		this.transactions = transactions;
		this.clock = clock;
		this.idGenerator = idGenerator;
	}

	/**
	 * merge-patch 修改分类；已合并分类是终态事实，不允许再改名或改状态。
	 * 行锁先于版本比较，乐观锁失败交给基础设施的原子 UPDATE 再复核。
	 */
	public CategorySnapshot updateCategory(
		UUID userId,
		UUID categoryId,
		CategoryUpdateCommand command,
		int expectedVersion) {
		if (userId == null || categoryId == null || command == null || expectedVersion < 1) {
			throw new CategoryValidationException();
		}
		return transactions.required(() -> {
			CategorySnapshot current = lockVisibleCategory(userId, categoryId);
			assertWritable(current, userId);
			if (current.status() == CategoryStatus.MERGED) {
				throw new CategoryValidationException();
			}
			if (current.version() != expectedVersion) {
				throw new CategoryVersionConflictException(current.version());
			}

			String name = command.name() == null ? current.name() : normalizeName(command.name());
			CategoryStatus status = command.status() == null ? current.status() : command.status();
			// 归一化重名检查必须排除自身，否则不改名只改状态的合法 PATCH 会被误判冲突。
			if (queries.existsNameConflict(
				current.ownerUserId(), current.accountId(), current.type(), current.parentId(),
				nameNormalized(name), current.id())) {
				throw new CategoryNameConflictException();
			}
			CategorySnapshot updated = new CategorySnapshot(
				current.id(), current.ownerUserId(), current.accountId(), current.type(), current.parentId(),
				name, nameNormalized(name), status, current.mergedIntoId(), current.createdAt(),
				clock.instant(), current.version() + 1);
			return commands.updateIfVersion(updated, expectedVersion)
				.orElseThrow(() -> new CategoryVersionConflictException(current.version()));
		});
	}

	/**
	 * 合并只改 categories 自身状态和 merged_into_id；transaction_categories 历史映射不迁移也不删除。
	 * 先按 UUID 顺序锁两行，避免反向合并并发请求形成锁等待环。
	 */
	public CategorySnapshot mergeCategory(
		UUID userId,
		UUID categoryId,
		UUID targetCategoryId,
		int expectedVersion) {
		if (userId == null || categoryId == null || targetCategoryId == null || expectedVersion < 1) {
			throw new CategoryValidationException();
		}
		if (categoryId.equals(targetCategoryId)) {
			throw new CategoryValidationException();
		}
		return transactions.nested(() -> {
			UUID first = categoryId.compareTo(targetCategoryId) < 0 ? categoryId : targetCategoryId;
			UUID second = first.equals(categoryId) ? targetCategoryId : categoryId;
			CategorySnapshot lockedFirst = lockVisibleCategory(userId, first);
			CategorySnapshot lockedSecond = lockVisibleCategory(userId, second);
			CategorySnapshot current = categoryId.equals(first) ? lockedFirst : lockedSecond;
			CategorySnapshot target = targetCategoryId.equals(first) ? lockedFirst : lockedSecond;
			assertWritable(current, userId);
			if (current.status() != CategoryStatus.ACTIVE && current.status() != CategoryStatus.INACTIVE) {
				throw new CategoryValidationException();
			}
			if (current.version() != expectedVersion) {
				throw new CategoryVersionConflictException(current.version());
			}
			if (target.status() != CategoryStatus.ACTIVE
				|| target.type() != current.type()
				|| !java.util.Objects.equals(target.ownerUserId(), current.ownerUserId())
				|| !java.util.Objects.equals(target.accountId(), current.accountId())) {
				throw new CategoryValidationException();
			}
			return commands.markMergedIfVersion(current.id(), target.id(), expectedVersion, clock.instant())
				.orElseThrow(() -> new CategoryVersionConflictException(current.version()));
		});
	}

	public CategoryPage listCategories(UUID userId, UUID accountId, Integer requestedLimit, String cursor) {
		if (userId == null) {
			throw new CategoryValidationException();
		}
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAXIMUM_LIMIT) {
			throw new CategoryValidationException();
		}

		Map<UUID, AccountMembershipReadPort.ActiveMembership> activeAccounts = new HashMap<>();
		for (AccountMembershipReadPort.ActiveMembership membership : memberships.listActiveMemberships(userId)) {
			activeAccounts.put(membership.accountId(), membership);
		}
		if (accountId != null && !activeAccounts.containsKey(accountId)) {
			throw new CategoryNotVisibleException();
		}

		CategoryKeysetPosition after = cursor == null ? null : cursors.decode(userId, accountId, cursor);
		if (after != null && activeAccounts.isEmpty()) {
			throw new CategoryValidationException();
		}
		var rows = queries.listVisible(userId, activeAccounts.keySet(), accountId, after, limit + 1);
		boolean hasMore = rows.size() > limit;
		List<CategorySnapshot> page = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;
		String nextCursor = hasMore && page.getLast() != null
			? cursors.encode(userId, accountId, new CategoryKeysetPosition(
				page.getLast().createdAt(), page.getLast().id()))
			: null;
		return new CategoryPage(page, nextCursor, hasMore);
	}

	public CategorySnapshot getVisibleCategory(UUID userId, UUID categoryId) {
		if (userId == null || categoryId == null) {
			throw new CategoryValidationException();
		}
		CategorySnapshot category = queries.findById(categoryId).orElseThrow(CategoryNotVisibleException::new);
		if (!isVisible(category, userId)) {
			throw new CategoryNotVisibleException();
		}
		return category;
	}

	private CategorySnapshot lockVisibleCategory(UUID userId, UUID categoryId) {
		CategorySnapshot category = queries.findByIdForUpdate(categoryId)
			.orElseThrow(CategoryNotVisibleException::new);
		if (!isVisible(category, userId)) {
			throw new CategoryNotVisibleException();
		}
		return category;
	}

	private void assertWritable(CategorySnapshot category, UUID userId) {
		if (category.ownerUserId() == null && category.accountId() == null) {
			throw new CategoryPermissionDeniedException();
		}
		if (category.accountId() != null) {
			var membership = memberships.findActiveMembership(userId, category.accountId())
				.orElseThrow(CategoryNotVisibleException::new);
			if (!"OWNER".equals(membership.role()) && !"EDITOR".equals(membership.role())) {
				throw new CategoryPermissionDeniedException();
			}
			return;
		}
		if (!userId.equals(category.ownerUserId())) {
			throw new CategoryPermissionDeniedException();
		}
	}

	/** empty 表示当前作用域同名冲突；调用方必须映射稳定 409，避免异常污染外层幂等事务。 */
	public Optional<CategorySnapshot> createCategory(UUID userId, CategoryCommand command) {
		if (userId == null || command == null) {
			throw new CategoryValidationException();
		}
		String name = normalizeName(command.name());
		if (command.categoryType() == null) {
			throw new CategoryValidationException();
		}

		return transactions.required(() -> {
			Map<UUID, AccountMembershipReadPort.ActiveMembership> activeAccounts = new HashMap<>();
			for (AccountMembershipReadPort.ActiveMembership membership : memberships.listActiveMemberships(userId)) {
				activeAccounts.put(membership.accountId(), membership);
			}

			if (command.accountId() != null) {
				var membership = activeAccounts.get(command.accountId());
				if (membership == null) {
					throw new CategoryNotVisibleException();
				}
				if (!"OWNER".equals(membership.role()) && !"EDITOR".equals(membership.role())) {
					throw new CategoryPermissionDeniedException();
				}
			}

			CategorySnapshot parent = validateParent(userId, command, activeAccounts);
			Instant now = clock.instant();
			CategorySnapshot category = new CategorySnapshot(
				idGenerator.get(), userId, command.accountId(), command.categoryType(),
				parent == null ? null : parent.id(), name, nameNormalized(name),
				CategoryStatus.ACTIVE, null, now, now, 1);
			// 可预期冲突先按当前事实预检；并发竞争仍由唯一索引 fail closed，不静默改写结果。
			if (queries.existsNameConflict(
				userId, command.accountId(), command.categoryType(), parent == null ? null : parent.id(),
				nameNormalized(name), null)) {
				return Optional.empty();
			}
			commands.insert(category);
			return Optional.of(queries.findById(category.id()).orElseThrow(
				() -> new CategoryPersistenceException(new IllegalStateException("分类读取未生效。"))));
		});
	}

	private CategorySnapshot validateParent(
		UUID userId,
		CategoryCommand command,
		Map<UUID, AccountMembershipReadPort.ActiveMembership> activeAccounts) {
		if (command.parentId() == null) {
			return null;
		}
		CategorySnapshot parent = queries.findById(command.parentId())
			.orElseThrow(CategoryNotVisibleException::new);
		if (!isVisible(parent, userId) || parent.status() != CategoryStatus.ACTIVE
			|| parent.parentId() != null || parent.type() != command.categoryType()) {
			throw new CategoryValidationException();
		}
		// 子分类必须与父分类同树；默认树、个人树和账户树不得混合挂载。
		if (!java.util.Objects.equals(parent.accountId(), command.accountId())) {
			throw new CategoryValidationException();
		}
		if (parent.accountId() != null && !activeAccounts.containsKey(parent.accountId())) {
			throw new CategoryNotVisibleException();
		}
		return parent;
	}

	private boolean isVisible(CategorySnapshot category, UUID userId) {
		if (category.ownerUserId() == null && category.accountId() == null) {
			return true;
		}
		if (userId.equals(category.ownerUserId())) {
			return true;
		}
		return category.accountId() != null
			&& memberships.findActiveMembership(userId, category.accountId()).isPresent();
	}

	private static String normalizeName(String value) {
		if (value == null) {
			throw new CategoryValidationException();
		}
		// NFKC 和 trim 后再校验，避免兼容字符或空白绕过展示名与规范化名边界。
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAXIMUM_NAME_LENGTH) {
			throw new CategoryValidationException();
		}
		return normalized;
	}

	private static String nameNormalized(String value) {
		// 名称唯一性大小写无关；保留原大小写作为展示名，避免中文和品牌名被客户端误读。
		return value.toLowerCase(java.util.Locale.ROOT);
	}
}
