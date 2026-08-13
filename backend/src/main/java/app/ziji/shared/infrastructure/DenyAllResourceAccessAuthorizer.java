package app.ziji.shared.infrastructure;

import java.util.UUID;

import app.ziji.shared.application.ResourceAccessAuthorizer;
import org.springframework.stereotype.Component;

@Component
class DenyAllResourceAccessAuthorizer implements ResourceAccessAuthorizer {

	@Override
	public boolean mayAccess(UUID userId, String resourceType, UUID resourceId, String action) {
		// 业务认证/成员权限尚未实现时必须 fail closed，禁止出现临时放行。
		return false;
	}
}
