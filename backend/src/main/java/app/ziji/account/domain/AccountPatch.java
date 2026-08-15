package app.ziji.account.domain;

/**
 * 账户资料的部分更新命令；只允许冻结契约中的 name 与 institution。
 * institution 显式 null 表示清空，缺失表示保持不变，二者必须在领域边界可区分。
 */
public final class AccountPatch {

	private final boolean namePresent;
	private final String name;
	private final boolean institutionPresent;
	private final String institution;

	public AccountPatch(boolean namePresent, String name, boolean institutionPresent, String institution) {
		if (!namePresent && !institutionPresent) {
			throw new AccountDomainException("账户更新至少包含一个字段。");
		}
		if (namePresent && (name == null || name.isBlank()
			|| Account.length(name) < Account.NAME_MIN_LENGTH
			|| Account.length(name) > Account.NAME_MAX_LENGTH)) {
			throw new AccountDomainException("账户名称格式无效。");
		}
		if (institutionPresent && institution != null
			&& (institution.isBlank() || Account.length(institution) > Account.INSTITUTION_MAX_LENGTH)) {
			throw new AccountDomainException("所属机构格式无效。");
		}
		this.namePresent = namePresent;
		this.name = namePresent ? name : null;
		this.institutionPresent = institutionPresent;
		this.institution = institutionPresent ? institution : null;
	}

	public boolean hasName() {
		return namePresent;
	}

	public String name() {
		return name;
	}

	public boolean hasInstitution() {
		return institutionPresent;
	}

	/** 仅当 {@link #hasInstitution()} 为 true 时有效；null 表示显式清空机构。 */
	public String institution() {
		return institution;
	}

	public boolean isEmpty() {
		return !namePresent && !institutionPresent;
	}
}
