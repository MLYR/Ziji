package app.ziji.account.application;

import java.util.UUID;

/** 不透明账户列表游标边界；实现必须绑定当前用户并拒绝篡改或跨用户复用。 */
public interface AccountCursorCodec {

	String encode(UUID userId, AccountKeysetPosition position);

	AccountKeysetPosition decode(UUID userId, String cursor);
}
