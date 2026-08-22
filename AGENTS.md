# 资迹 Ziji — 项目级 AI 开发规范

本文件只补充资迹项目特有规则。通用的工具使用、文件编辑、Git、安全审批、沟通和验证要求继承 Codex 全局规范，不在此重复。

## 1. 基线与事实源

开始任务前，按改动范围读取对应基线，不要凭聊天摘要或旧记忆实施：

| 事项 | 权威文件 |
| --- | --- |
| V1 范围、产品规则、批次和验收原则 | `doc/产品需求文档.md` |
| 需求 ID、设计与测试追踪关系 | `doc/需求追踪矩阵.md` |
| 聚合、账务语义、计算口径和领域事件 | `doc/核心领域与账务设计.md` |
| 模块边界、技术栈、同步、安全和部署 | `doc/系统架构设计.md` |
| 表、约束、索引和事务边界 | `doc/数据库设计.md` |
| 人类可读 HTTP 语义 | `doc/API契约.md` |
| 机器可读 API 契约 | `openapi/ziji-v1.yaml` |
| 自动化与发布验收标准 | `doc/测试与验收方案.md` |
| 当前任务、依赖、状态和进度 | `doc/开发进度与任务跟踪.md` |
| 当前数据库机器基线 | `backend/src/main/resources/db/migration/` |

同一规则跨文档不一致时，不得默认选择其中一份继续实现。先登记 `CHG-*` 或 `BUG-*` 任务，给出影响范围，同步修正相关基线后再开发。`API契约.md` 与 OpenAPI 不一致时，两者均不自动覆盖另一方。

模块实现前还必须读取目标目录内更近的 `AGENTS.md`；根规则与模块规则同时生效，模块规则只补充局部工具、命令和实现纪律：

| 范围 | 局部规则 |
| --- | --- |
| Backend、Flyway、jOOQ | `backend/AGENTS.md` |
| Web、shadcn/ui、GSAP | `web/AGENTS.md` |
| Mobile、Expo、SQLite | `mobile/AGENTS.md` |
| OpenAPI 与生成类型 | `openapi/AGENTS.md` |
| UI 原型与提示词 | `prototypes/AGENTS.md` |

## 2. 任务追踪与状态

开发工作必须具有可追溯上下文，但并非所有微小改动都新建叶子任务：

1. 产品行为、API/OpenAPI、数据库迁移、Ledger/金额、权限/Auth、同步、幂等、安全、新功能、架构变化或预计超过半个工作日的工作，开始前必须登记独立叶子任务。
2. typo、格式、注释、lint、小范围测试修正、CI 文案、局部重构及明显属于当前任务的附带修复，可挂靠当前任务，在其产出物和验证证据中说明；没有可挂靠上下文时才新增任务。
3. 依赖未完成时不得绕过依赖实施；用户明确调整顺序时，同步修改依赖和状态。
4. L1 在完成最小自检后可由 `IN_PROGRESS → DONE`；L2 使用 `IN_PROGRESS → VERIFYING → DONE`；L3 或明确要求独立审查的任务使用 `IN_PROGRESS → REVIEW → VERIFYING → DONE`。
5. 只有满足台账中的 DoD 并填写产出物和验证证据后才能标记 `DONE`。同一轮连续状态变化可合并为一次台账更新，不为每个中间状态单独制造提交或变更记录。
6. 任务状态变化同步更新受影响的总览、最后更新时间和一条合并变更记录；只有需求达到“已实现”或“已验收”时才更新 RTM，纯流程、文档和内部重构不机械修改 RTM。

任务 ID 一经使用不得复用或改成其他含义。缺陷使用 `BUG-<领域>-NNN`，需求变更使用 `CHG-<领域>-NNN`。

## 3. V1 范围不得漂移

V1 必须完成 PRD 的 B1～B4 全部批次。以下内容不属于 V1，不得创建占位入口、假数据页面或隐含实现：

- 财务目标、分类预算、灵活预算、预算结转和未来支出预测
- 微信登录、手机号注册或登录
- 房产、汽车等实物资产
- 银行、微信、支付宝或证券平台 API 自动同步
- 盘中实时行情

Tushare Pro 是 V1 股票、ETF 和基金信息及盘后数据的首选来源，手工产品、价格和净值是明确的降级路径。不得把盘后数据描述为“实时”。

## 4. 技术栈与架构边界

已冻结技术栈：

- 后端：Java 25、Spring Boot 4、Spring MVC、Spring Security、Spring Modulith、jOOQ、Flyway、PostgreSQL
- Web：React、TypeScript、Vite、Tailwind CSS、Radix UI/shadcn/ui、TanStack Query、React Router、Zustand、ECharts
- Mobile：React Native、Expo Router、NativeWind、Zustand、SQLite
- 契约：OpenAPI 3.1、openapi-typescript
- 测试：JUnit、Testcontainers、Vitest、Playwright、Maestro

工程工具链已由 `ADR-024` 冻结：后端使用 Maven Wrapper，Web/Mobile 使用 pnpm 10.4.1 workspace，Node.js 固定为 22.22.3，仓库只共享 OpenAPI 生成类型。未经过新任务和 ADR，不得引入 WebFlux、微服务、Kafka、Kubernetes、Elasticsearch、默认 Redis、Nx/Turborepo、另一套 ORM、状态库或 UI 组件体系。

后端保持模块化单体和 `interfaces/application/domain/infrastructure` 分层：

- 模块不得直接访问另一模块的 jOOQ Repository 或所属表；跨模块使用公开 application port 或领域事件。
- jOOQ 类型只允许留在 infrastructure 层，不得泄漏到 domain 或 API DTO。
- 外部邮件、对象存储、Tushare 和汇率供应商必须通过端口/适配器隔离。
- 事实写入与 outbox 必须在同一数据库事务提交；事件消费者必须幂等。

## 5. 不可破坏的账务规则

以下规则优先于局部实现便利：

1. `Transaction + LedgerEntry` 是资产和负债变化的唯一账务事实；不得直接改余额、持仓或统计投影来制造业务结果。
2. 余额重建汇总所有已入账的 `POSTED` 分录，包括原交易及其冲正分录。不得因原交易已被冲正或状态变化而排除原分录。
3. 修改已确认交易使用“原交易 + 冲正交易 + 新交易”；作废使用原交易及冲正交易。已确认事实不得物理删除或原地改写。
4. 每笔已入账交易必须在每个币种内借贷平衡，整笔交易和分录原子提交。
5. 客户端只提交支出、收入、退款、转账、负债还款等语义命令，不得提交任意借贷分录或内部科目 ID。
6. Java 使用 `BigDecimal`，数据库使用 `NUMERIC`，API 财务值使用十进制字符串；不得经过二进制浮点数。
7. 入账金额、手续费和税费在入账边界按币种精度 `HALF_UP`；中间计算不得提前舍入，统计只在最终展示时舍入。
8. `business_date` 入账后固定；用户修改时区不得改变历史日、月、年归属。
9. `availableBalance = ledgerBalance - unavailableAmount`。`LiquidityHold` 是可审计的流动性事实，但不产生 `LedgerEntry`、不改变资产和净资产。
10. 投资手续费和税费统一费用化，不进入持仓成本；`POSITION_COST` 只用于成本重建，不重复计入资产总额。
11. 计入开关和比例按 membership 周期保存，只影响生效时间之后的数据，不回改历史统计。
12. 余额、持仓和统计投影必须能从事实表重建；缺失价格或汇率不得静默按 0 或 1 处理。
13. 投资收益日历由服务端按全部投资或单一标的边界计算；转入转出和买卖本金不得误算为收益，真实零收益、非交易日、无持仓、待数据和缺估值必须使用不同状态。
14. “总资产日历”展示总资产或净资产的每日变化，不得命名为投资收益；内部转账贡献为 0，完整值、真实零变化、无资产、待数据和估值不完整必须使用不同状态。

## 6. 身份、权限、幂等与同步

- 认证成功不代表具有资源权限。每个账户相关用例都必须在 application 边界校验当前 `AccountMember` 周期和角色。
- Web 使用 HttpOnly Cookie 刷新会话并执行 CSRF 防护；Mobile 使用响应体刷新凭据和系统安全存储。不得混用两端传输策略。
- 写操作幂等作用域固定为“用户 + API 主版本 + operationId + Idempotency-Key”，并比较规范化请求 Hash；同键异参返回冲突。
- 更新接口使用 `ETag/If-Match` 和乐观版本；不得以最后写入覆盖解决冲突。
- 服务端是最终权威。Mobile SQLite 只保存缓存、待同步队列、游标和冲突，不是账务事实源。
- 变更日志按用户定向投递；成员被移除时发送 `ACCESS_REVOKED`，重新加入通过新 membership 周期和 bootstrap 获取当前数据。
- 审计日志只追加。普通业务角色不得修改或删除审计、已入账交易、成交和分录。

## 7. API 与数据库变更纪律

- API 行为变化必须在同一任务中同步 `doc/API契约.md`、`openapi/ziji-v1.yaml`、生成类型、对应 RTM/`T-*` 和契约测试；详细操作与验证命令见 `openapi/AGENTS.md`。
- 数据库结构变化必须以 Flyway 表达并同步 `doc/数据库设计.md`；已用于共享、staging 或 production 的迁移不得重写。数据库约束只兜底领域不变量，不能替代 application/domain 校验和可理解的 API 错误；详细迁移纪律见 `backend/AGENTS.md`。

## 8. 客户端职责

- TanStack Query 管理服务端状态与缓存；Zustand 只保存界面、草稿和流程状态，不复制余额、持仓、收益或权限事实。
- openapi-typescript 生成类型是两端 API 类型来源，不维护平行的手写响应模型。
- Web/Mobile 不实现独立账务算法；金额、收益、权限和数据质量结论以服务端结果为准。
- 关键金额、盈亏、过期、冲突和风险状态不得只依赖颜色表达。
- UI 原型与设计 Token 未冻结前，不得把临时视觉稿当作生产设计基线。
- Web 实现细节、组件工作流和验证命令见 `web/AGENTS.md`；Mobile 的 Expo、SQLite 和验证命令见 `mobile/AGENTS.md`；原型只按 `prototypes/AGENTS.md` 和其索引文档执行。

## 9. 项目验收要求

- 每个实现任务必须关联 RTM 需求 ID 和 `T-*`，或写明可执行的独立验收条件。
- 财务规则改动至少覆盖领域单元/属性测试和 PostgreSQL 集成测试；保存属性测试随机种子和最小反例。
- 权限改动覆盖 OWNER、EDITOR、VIEWER、已移除成员和无关用户，不以“路由需要登录”代替对象级授权测试。
- API 改动覆盖正向、校验失败、未认证、未授权、幂等重试和版本冲突。
- 同步改动覆盖离线重启、重复提交、断网重试、权限撤销和服务端拒绝。
- 投影改动必须执行删除后重建比对；余额、持仓、统计和数据质量摘要差异必须为 0。
- V1 只有 B1～B4 全部完成、RTM 全部 V1 需求达到“已验收”且发布门禁通过后才算完成。

## 9.1 本地提交与 CI 验证流程

验证分为本地定向验证、PR 统一 CI 和批次/发布验证，三者职责不得重复堆叠：

1. L1 低风险包括文档、文案、CSS/排版、非关键展示、测试代码自身和非行为配置；本地只执行贴近改动的检查，默认不要求 Testcontainers、E2E 或独立审查。
2. L2 普通业务改动包括普通 CRUD、查询、页面逻辑、非账务服务和 Mobile 常规状态处理；本地执行相关类型、单元或集成测试，通常不要求独立审查，不得要求无关端完整测试。
3. L3 高风险包括账务事实、金额、余额/持仓/收益、冲正、权限/认证/对象级授权、幂等、同步冲突、并发、Flyway、OpenAPI breaking change、数据删除、审计、安全以及 CI/workflow 供应链；必须执行定向深度验证，并由匹配角色独立审查。
4. 本地只验证受影响范围：文档执行 `git diff --check`；Web/Mobile 执行对应 check 和相关测试；普通 Backend 执行相关测试类；API/OpenAPI 变化才执行契约与生成类型检查；迁移变化才执行 PostgreSQL Testcontainers、空库和上一版本升级验证。
5. 提交前检查暂存区（`git diff --cached --check`、`git diff --cached`）。PR required CI 是统一干净环境的最终核心回归证据；自动完整 CI 只在 PR 执行一次，合并 main 后不重复运行同一套门禁，也不要求精确 merge SHA 再验证才能 `DONE`。
6. L3 可按风险补充本地或隔离环境验证，但不得机械重复已经成功且适用的同一套 PR CI。E2E 默认在 B1～B4 批次门禁、发布候选或显式手动验证时执行，不作为普通任务或普通 PR 的固定要求。
7. 任务自身要求的验证通过后可记录完成；其他模块无因果关系的失败另建缺陷，不得让任务长期滞留 `VERIFYING`，但任何 required check 失败的 PR 仍不得合并 main。
8. 扫描器无法运行、结果无法解析或确认存在密钥泄露、危险 workflow、HIGH/CRITICAL 漏洞时必须 fail closed；LOW/MODERATE 和普通许可证提示进入风险台账，不默认阻塞普通 PR。不得把 CI 的 `skipped` 步骤记为通过。

## 10. 注释要求
- 只为无法从代码本身理解的业务不变量、财务语义、安全边界、并发原因、兼容性 workaround 和非常规实现添加简洁中文注释。
- 不写无意义的逐行注释，不复制条件、方法名或代码字面行为。
- 注释不得描述尚未实现的功能，不得与实际实现不一致。
