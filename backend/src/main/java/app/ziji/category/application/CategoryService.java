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
				nameNormalized(name))) {
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
