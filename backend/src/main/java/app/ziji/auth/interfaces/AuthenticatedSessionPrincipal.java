package app.ziji.auth.interfaces;

import java.security.Principal;
import java.util.UUID;

/** 仅由已验签且仍有效的服务端会话创建，Controller 不接受客户端提供的当前 sessionId。 */
public record AuthenticatedSessionPrincipal(UUID userId, UUID sessionId) implements Principal {

	public AuthenticatedSessionPrincipal {
		if (userId == null || sessionId == null) {
			throw new IllegalArgumentException("认证会话主体无效。");
		}
	}

	@Override
	public String getName() {
		return userId.toString();
	}
}
