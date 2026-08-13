package app.ziji.shared.application;

import java.util.UUID;

/** 对象级授权扩展点；认证模块完成后由各资源用例在应用边界调用。 */
public interface ResourceAccessAuthorizer {

	boolean mayAccess(UUID userId, String resourceType, UUID resourceId, String action);
}
