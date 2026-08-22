package app.ziji.account.domain;

import java.time.Instant;
import java.util.UUID;

/** 可见账户聚合；不保存或计算余额，创建与恢复入口分开以免伪造默认状态。 */
public final class Account {

	public static final int NAME_MIN_LENGTH = 1;
	public static final int NAME_MAX_LENGTH = 100;
	public static final int INSTITUTION_MAX_LENGTH = 120;
	public static final int NOTE_MAX_LENGTH = 2000;

	private final UUID id;
	private final AccountClass accountClass;
	private final AccountType accountType;
	private final String name;
	private final String institution;
	private final AccountCurrency currency;
	private final String note;
	private final AccountStatus status;
	private final Instant archivedAt;
	private final UUID createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;
	private final int version;

	private Account(
		UUID id,
		AccountClass accountClass,
		AccountType accountType,
		String name,
		String institution,
		AccountCurrency currency,
		String note,
		AccountStatus status,
		Instant archivedAt,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		int version) {
		this.id = require(id, "账户 ID");
		this.accountClass = require(accountClass, "账户大类");
		this.accountType = require(accountType, "账户类型");
		// 跨类配对必须在领域边界拒绝，不能等到数据库 CHECK。
		if (!this.accountType.isAllowedFor(this.accountClass)) {
			throw new AccountDomainException("账户类型与账户大类不匹配。");
		}
		this.name = requiredName(name);
		this.institution = optionalInstitution(institution);
		this.currency = require(currency, "账户币种");
		this.note = optionalNote(note);
		this.status = require(status, "账户状态");
		this.archivedAt = archivedAt;
		if (this.status == AccountStatus.ACTIVE && this.archivedAt != null) {
			throw new AccountDomainException("正常账户不能有归档时间。");
		}
		if (this.status == AccountStatus.ARCHIVED && this.archivedAt == null) {
			throw new AccountDomainException("已归档账户必须有归档时间。");
		}
		this.createdBy = require(createdBy, "创建人");
		this.createdAt = require(createdAt, "创建时间");
		this.updatedAt = require(updatedAt, "更新时间");
		if (version < 1) {
			throw new AccountDomainException("账户版本必须为正整数。");
		}
		this.version = version;
	}

	/** 新建账户固定为 ACTIVE、version=1，创建和更新时间相同。 */
	public static Account create(
		UUID id,
		AccountClass accountClass,
		AccountType accountType,
		String name,
		String institution,
		AccountCurrency currency,
		String note,
		UUID createdBy,
		Instant createdAt) {
		Instant created = require(createdAt, "创建时间");
		return new Account(
			id, accountClass, accountType, name, institution, currency, note,
			AccountStatus.ACTIVE, null, createdBy, created, created, 1);
	}

	/** 从持久化事实恢复，保留历史状态和版本，不改写创建默认值。 */
	public static Account restore(
		UUID id,
		AccountClass accountClass,
		AccountType accountType,
		String name,
		String institution,
		AccountCurrency currency,
		String note,
		AccountStatus status,
		Instant archivedAt,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		int version) {
		return new Account(
			id, accountClass, accountType, name, institution, currency, note,
			status, archivedAt, createdBy, createdAt, updatedAt, version);
	}

	/** 将已确认的 name/institution 部分更新应用到新快照，保留身份、状态和账务事实。 */
	public Account apply(AccountPatch patch, Instant updatedAt) {
		if (patch == null || patch.isEmpty()) {
			throw new AccountDomainException("账户更新至少包含一个字段。");
		}
		Instant updated = require(updatedAt, "更新时间");
		return new Account(
			id,
			accountClass,
			accountType,
			patch.hasName() ? patch.name() : name,
			patch.hasInstitution() ? patch.institution() : institution,
			currency,
			note,
			status,
			archivedAt,
			createdBy,
			createdAt,
			updated,
			version + 1);
	}

	/** 归档只允许 ACTIVE 账户发生一次状态迁移，保留所有身份、版本和历史事实。 */
	public Account archive(Instant archivedAt) {
		if (status != AccountStatus.ACTIVE) {
			throw new AccountDomainException("已归档账户不能再次归档。");
		}
		Instant archived = require(archivedAt, "归档时间");
		return new Account(
			id,
			accountClass,
			accountType,
			name,
			institution,
			currency,
			note,
			AccountStatus.ARCHIVED,
			archived,
			createdBy,
			createdAt,
			archived,
			version + 1);
	}

	public UUID id() {
		return id;
	}

	public AccountClass accountClass() {
		return accountClass;
	}

	public AccountType accountType() {
		return accountType;
	}

	public String name() {
		return name;
	}

	public String institution() {
		return institution;
	}

	public AccountCurrency currency() {
		return currency;
	}

	public String note() {
		return note;
	}

	public AccountStatus status() {
		return status;
	}

	public Instant archivedAt() {
		return archivedAt;
	}

	public UUID createdBy() {
		return createdBy;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	public int version() {
		return version;
	}

	private static <T> T require(T value, String field) {
		if (value == null) {
			throw new AccountDomainException(field + "不能为空。");
		}
		return value;
	}

	private static String requiredName(String value) {
		if (value == null || value.isBlank()
			|| length(value) < NAME_MIN_LENGTH || length(value) > NAME_MAX_LENGTH) {
			throw new AccountDomainException("账户名称格式无效。");
		}
		return value;
	}

	private static String optionalInstitution(String value) {
		if (value == null) {
			return null;
		}
		if (value.isBlank() || length(value) > INSTITUTION_MAX_LENGTH) {
			throw new AccountDomainException("所属机构格式无效。");
		}
		return value;
	}

	private static String optionalNote(String value) {
		if (value == null) {
			return null;
		}
		if (length(value) > NOTE_MAX_LENGTH) {
			throw new AccountDomainException("账户备注格式无效。");
		}
		return value;
	}

	static int length(String value) {
		// OpenAPI maxLength 按 Unicode code point 计数，不能使用 UTF-16 code unit 数量。
		return value.codePointCount(0, value.length());
	}
}
