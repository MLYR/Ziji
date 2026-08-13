package app.ziji;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresIntegrationTestSupport {

	// 全部 PostgreSQL 集成测试共享真实数据库能力，避免使用与生产语义不同的内存数据库。
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
		.withDatabaseName("ziji_test")
		.withUsername("ziji")
		.withPassword("ziji-test");
}
