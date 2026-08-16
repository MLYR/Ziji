package app.ziji.sync.application;

import java.util.List;

/** change_log 的追加式、幂等写入边界。 */
public interface ChangeLogStore {

	void appendIfAbsent(List<ChangeLogWrite> changes);
}
