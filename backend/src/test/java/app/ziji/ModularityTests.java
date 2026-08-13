package app.ziji;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void modulesRespectDeclaredBoundaries() {
		// 在业务代码进入工程后持续阻止跨模块内部依赖。
		ApplicationModules.of(ZijiBackendApplication.class).verify();
	}
}
