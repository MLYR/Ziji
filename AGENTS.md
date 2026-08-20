# 资迹 Ziji — 项目级 AI 开发规范

本文件只补充资迹项目特有规则。通用的工具使用、文件编辑、Git、安全审批、沟通和验证要求继承 Codex 全局规范，不在此重复。

@/Users/zreo/CODE/Ziji/doc/Project Shepherd项目管理Prompt规范.md

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

## 2. 任务台账是强制入口

任何代码、迁移、契约、测试、脚本、原型、文档、缺陷修复或运维工作，都必须关联 `doc/开发进度与任务跟踪.md` 中的叶子任务：

1. 开始前确认任务 ID、验收依据和前置依赖；没有任务时先登记。
2. 依赖未完成时不得绕过依赖实施；用户明确调整顺序时，同步修改依赖和状态。
3. 开始时标记 `IN_PROGRESS` 并填写负责人；产出完成后按 `REVIEW → VERIFYING → DONE` 推进。
4. 只有满足台账中的 DoD 并填写产出物和验证证据后才能标记 `DONE`。
5. 每次状态变化同步更新进度总览、最后更新时间和变更记录。
6. 需求达到“已实现”或“已验收”时，同步更新 RTM；代码完成但尚未验证不得提前更新。

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

新增或修改接口时，在同一个任务中完成：

1. 更新 `doc/API契约.md` 与 `openapi/ziji-v1.yaml`。
2. 保持稳定且唯一的 `operationId`，补全认证、权限、幂等、ETag、分页和 Problem Details。
3. 重新生成 Web/Mobile 的 openapi-typescript 类型；生成文件不得手工修改。
4. 更新对应 RTM 链接、`T-*` 用例和契约测试。

数据库变更必须以 Flyway 表达，并同步 `doc/数据库设计.md`：

- V001～V007 是 V1 初始机器基线。在尚未被任何持久环境采用前，修正它们也必须关联任务并重新执行完整空库验证。
- 一旦迁移已用于共享、staging 或 production 环境，只能新增更高版本迁移，不得重写已执行迁移。
- 数据库约束用于兜底领域不变量，不能代替 application/domain 校验和可理解的 API 错误。
- 不得为缓存或性能优化创建第二套不可重建的财务事实。

## 8. 客户端职责

- TanStack Query 管理服务端状态与缓存；Zustand 只保存界面、草稿和流程状态，不复制余额、持仓、收益或权限事实。
- openapi-typescript 生成类型是两端 API 类型来源，不维护平行的手写响应模型。
- Web/Mobile 不实现独立账务算法；金额、收益、权限和数据质量结论以服务端结果为准。
- shadcn/ui 组件源码属于项目代码，可以按冻结设计系统修改；优先组合 Radix 可访问性原语，不另造平行组件体系。
- 关键金额、盈亏、过期、冲突和风险状态不得只依赖颜色表达。
- UI 原型与设计 Token 未冻结前，不得把临时视觉稿当作生产设计基线。
- Web 复杂动画使用 `web/src/motion/` 的 GSAP 封装和统一 Token；必须使用已安装的 GSAP skills，限定 React scope、自动清理并提供 reduced-motion 静态路径。简单控件过渡仍用 CSS，Mobile 不引入 GSAP。
- Web 动态背景只能复用 `web/src/components/Aurora.tsx`；仅允许登录、注册、欢迎和低信息密度空状态使用，禁止用于 Dashboard、账户/流水、表格、表单内容区、投资收益日历和总资产日历。不得直接使用 OGL、重新引入 registry 原版或新增第二套背景库；必须保留 reduced-motion/CSS fallback、离屏/后台暂停和第三方授权声明。

### 8.1 shadcn/ui 专项工作流

任何 shadcn/ui 的初始化、组件搜索、引入、组合、样式调整、升级、修复或调试，都必须使用已安装的 `shadcn` skill 执行，不得仅凭记忆手写组件 API 或从 GitHub 复制原始组件文件。

- Web 工程、包管理器、路径别名和设计 Token 未确定前，不执行 `shadcn init`；正式初始化由任务 `ENG-WEB-002` 负责。
- 初始化固定使用 Vite SPA 与 Radix primitives；具体 preset/style 必须依据已确认的 UI 原型和 `UI-DES-003～004`，不得自行选择。
- CLI 必须使用项目 `packageManager` 对应的 runner。存在 `components.json` 后，操作前先读取 `shadcn info --json`，以实际 alias、Tailwind 版本、icon library、base 和 resolved paths 为准。
- 引入组件前先搜索已安装组件和 `@shadcn` registry，再读取对应组件 docs/examples；需要其他 registry 时必须先获得用户明确指定。
- 更新已有组件必须先执行 dry-run/diff 并保留本地修改；未经用户明确同意不得 overwrite。
- CLI 添加的源码仍属于需评审代码：检查依赖、导入路径、组件组合、可访问性、语义 Token 和项目图标库后才能使用。

### 8.2 原型与视觉生成专项工作流

任何 Web/Mobile 原型的新建、AI 生成、视觉迭代或交互调整，都必须先读取 `prototypes/README.md`，按其中的工具选择、提示词入口、落点和评审清单执行；不得仅依赖聊天记录、外部工具历史或临时提示词。

- 开始前关联已有 `UI-DES-*`、`CHG-*` 或 `BUG-UI-*` 叶子任务；需求或交互语义变化必须先同步 PRD/RTM/API 等受影响基线。
- Codex 内新建或重做可点击 HTML 原型默认使用 `huashu-design` skill，设计规范检索可辅以 `ui-ux-pro-max`；必须遵守 skill 自身的方向提案与评审流程。用户明确指定 OpenDesign 等外部工具时，使用 `prototypes/prompts/` 中的项目提示词。对应 skill/工具不可用时只输出可复用提示词，不得冒充已生成原型。
- 项目内原型、资源和生成元数据只落在 `prototypes/`；提示词事实源只落在 `prototypes/prompts/`。外部工作区不是项目事实源，外部工具自己的 `.od-skills`、缓存、会话和插件文件不得复制进仓库。
- 新稿默认放在独立候选目录或文件名中，不得静默覆盖 `prototypes/open-design/ziji-v1/` 已确认基线；只有产品评审通过并更新 `doc/UI UX设计基线.md` 与任务证据后，才能替换或并入基线。
- AI 生成稿是评审材料，不是生产代码、最终无障碍证据或业务规则事实源；不得直接复制生成 HTML/CSS/JS 到 `web/` 或 `mobile/`，不得用演示数据反推金额、权限、同步或账务规则。
- 原型评审至少覆盖目标视口、深浅主题、关键主流程、加载/空/错误/无权限/离线/冲突状态、键盘可达性和非颜色状态表达；未通过 `doc/UI UX设计基线.md`§7 门禁不得作为生产实现依据。

## 9. 项目验收要求

- 每个实现任务必须关联 RTM 需求 ID 和 `T-*`，或写明可执行的独立验收条件。
- 财务规则改动至少覆盖领域单元/属性测试和 PostgreSQL 集成测试；保存属性测试随机种子和最小反例。
- 权限改动覆盖 OWNER、EDITOR、VIEWER、已移除成员和无关用户，不以“路由需要登录”代替对象级授权测试。
- API 改动覆盖正向、校验失败、未认证、未授权、幂等重试和版本冲突。
- 同步改动覆盖离线重启、重复提交、断网重试、权限撤销和服务端拒绝。
- 投影改动必须执行删除后重建比对；余额、持仓、统计和数据质量摘要差异必须为 0。
- V1 只有 B1～B4 全部完成、RTM 全部 V1 需求达到“已验收”且发布门禁通过后才算完成。

## 9.1 本地提交与 CI 验证流程

测试严格度按改动风险匹配，不要求每次变更在本地把完整测试套件重复执行两遍：

1. 开发过程中先执行贴近改动点的快速检查；普通 Web/Mobile 改动至少做类型检查和相关单测，契约改动执行契约与生成类型检查，文档/注释改动执行 `git diff --check`。
2. 提交前检查暂存区（`git diff --cached --check`、`git diff --cached`），确认生成物、迁移、契约和任务证据已同步。高风险改动还必须执行对应完整测试；未通过不得提交。
3. 提交后由 CI 对精确 commit 执行完整门禁。CI 通过是合并条件，本地不默认重复执行同一套完整测试。
4. 以下高风险改动可要求提交后本地在干净提交快照复测：账务事实、余额/持仓/收益、权限/认证/幂等、同步冲突、Flyway 迁移、OpenAPI 契约和工具链/锁文件升级。复测范围写入任务证据。
5. 测试失败时先修复或记录阻塞原因；不得以“本地之前通过”替代当前提交的 CI 结果，也不得把 CI 的 `skipped` 步骤记为通过。

## 10. 注释要求
- 所有新增 SQL 表必须使用中文 COMMENT ON TABLE。
- 重要字段、状态、约束、索引、视图和权限边界按需使用中文 COMMENT。
- 所有新增或修改的非显然代码逻辑必须写简洁中文注释，说明原因和不变量。
- 不写无意义的逐行注释，不复制代码字面含义。
- 注释不得描述尚未实现的功能，不得与实际实现不一致。
