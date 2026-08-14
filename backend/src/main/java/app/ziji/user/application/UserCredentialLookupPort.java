package app.ziji.user.application;

import java.util.Optional;

/**
 * 认证模块按规范化邮箱查询凭据的公开 application 端口；禁止 auth 跨模块访问 user infrastructure 或 users 表 SQL。
 * 实现只读取安全认证所需列，不返回用户资料、账务数据或 Token，不存在用户返回 {@link Optional#empty()}。
 */
public interface UserCredentialLookupPort {

	Optional<UserCredential> findByNormalizedEmail(String emailNormalized);
}
