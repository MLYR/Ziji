# Ziji Backend — Java、jOOQ 与 Flyway 局部规范

- 后端保持 `interfaces/application/domain/infrastructure` 分层；jOOQ 类型只能出现在 infrastructure，跨模块通过公开 application port 或领域事件，不能直接访问其他模块的 Repository 或所属表。
- Java 财务值使用 `BigDecimal`，数据库使用 `NUMERIC`；事实写入与 outbox 在同一事务提交，事件消费者必须幂等。不得通过缓存或投影创建第二套财务事实。
- 数据库结构变化以 Flyway 表达并同步 `doc/数据库设计.md`。V001～V007 在未被持久环境采用前的修正也必须关联任务并执行空库验证；一旦迁移进入共享、staging 或 production，只能新增更高版本迁移。
- 所有新增 SQL 表使用中文 `COMMENT ON TABLE`；重要字段、状态、约束、索引、视图和权限边界按需添加中文 COMMENT。注释解释业务不变量，不复述 SQL 字面含义。
- 启动、Flyway、jOOQ 生成和测试命令以根 `README.md` 与 `backend/pom.xml` 为准，不凭记忆拼命令；只执行任务范围要求的定向验证，不机械运行无关完整套件。
