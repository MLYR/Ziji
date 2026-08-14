package app.ziji.user.infrastructure;

import java.security.Principal;
import java.util.UUID;

import app.ziji.user.application.CurrentUserIdResolver;
import app.ziji.user.application.UserAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 从已认证 Principal/SecurityContext 读取 UUID；Token 解析仍由认证模块负责。 */
@Component
public final class SecurityContextCurrentUserIdResolver implements CurrentUserIdResolver {

	@Override
	public UUID resolve(Principal principal) {
		Principal candidate = principal;
		if (candidate == null) {
			candidate = SecurityContextHolder.getContext().getAuthentication();
		}
		if (candidate == null || candidate.getName() == null || candidate.getName().isBlank()) {
			throw new UserAuthenticationException();
		}
		try {
			return UUID.fromString(candidate.getName());
		} catch (IllegalArgumentException exception) {
			throw new UserAuthenticationException();
		}
	}
}
