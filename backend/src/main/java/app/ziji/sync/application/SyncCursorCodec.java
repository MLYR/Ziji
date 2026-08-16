package app.ziji.sync.application;

import java.util.UUID;

/** 绑定当前用户和同步游标域的不透明 sequence 编解码边界。 */
public interface SyncCursorCodec {

	String encode(UUID userId, long sequence);

	long decode(UUID userId, String cursor);
}
