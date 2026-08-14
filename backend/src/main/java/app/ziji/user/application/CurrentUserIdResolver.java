package app.ziji.user.application;

import java.security.Principal;
import java.util.UUID;

/** 当前用户身份解析边界；不在用户模块内解析 Token 或实现登录。 */
public interface CurrentUserIdResolver {

	UUID resolve(Principal principal);
}
