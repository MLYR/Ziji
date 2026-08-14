package app.ziji;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.annotation.DirtiesContext;

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PostgresIntegrationTestSupport {

	// 全部 PostgreSQL 集成测试共享真实数据库能力，避免使用与生产语义不同的内存数据库。
	// 每个测试类结束后清理 Spring Context，避免缓存旧 Testcontainers 端口对应的数据源。
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
		.withDatabaseName("ziji_test")
		.withUsername("ziji")
		.withPassword("ziji-test");
}
