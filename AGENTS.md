# 资迹 Ziji — 项目级 AI 开发规范

本文件只补充资迹项目特有规则。通用工具、Git、安全审批与沟通要求继承全局规范，不在此重复。

## 1. 事实源
按改动范围读对应基线，不凭聊天摘要或旧记忆实施；不需要一次全读。

| 事项 | 权威文件 |
| --- | --- |
| V1 范围、产品规则、批次 | `doc/产品需求文档.md` |
| 需求 ID 与追踪 | `doc/需求追踪矩阵.md` |
| 账务语义与领域事件 | `doc/核心领域与账务设计.md` |
| 架构、技术栈、同步与安全 | `doc/系统架构设计.md` |
| 表、约束、迁移 | `doc/数据库设计.md` |
| HTTP 语义 / 机器可读契约 | `doc/API契约.md` / `openapi/ziji-v1.yaml` |
| 自动化与发布验收 | `doc/测试与验收方案.md` |
| 当前进度与活动任务 | `doc/status/STATUS.md`、`doc/status/BOARD.md` |
| 数据库机器基线 | `backend/src/main/resources/db/migration/` |

跨文档冲突先登记 `CHG-*` / `BUG-*` 对齐基线，不默认择一实现。`API契约.md` 与 OpenAPI 互不自动覆盖。
模块实现前读目标目录 `AGENTS.md`（`backend` / `web` / `mobile` / `openapi` / `prototypes`）；根规则与模块规则同时生效。

## 2. 任务登记
- 新功能、产品行为、API/OpenAPI、迁移、账务、权限、幂等、安全改动：开工前在 `BOARD.md` 登记一条叶子任务，完成后移出并同步 `STATUS.md`。汇总百分比在批次收口时重算，不必每次改动都重算。
- typo、格式、注释、lint、小测试修正、CI 文案、局部重构及当前任务附带修复：挂靠当前任务，不新建。
- 状态只用 `IN_PROGRESS` / `DONE`；确需复核时临时用 `REVIEW` / `VERIFYING`。不得虚构任务、状态、证据或验收结论。
- 任务 ID 一经使用不复用：缺陷 `BUG-<领域>-NNN`，需求变更 `CHG-<领域>-NNN`。
- 未经用户授权不得改 STATUS/BOARD/RTM。

## 3. V1 不做清单
V1 必须完成 PRD 的 B1～B4。以下不得做占位入口或假数据页面：财务目标与预算类能力；微信登录/手机号注册登录；房产汽车等实物资产；银行/微信/支付宝/证券 API 自动同步；盘中实时行情。

同花顺公开接口是 V1 股票/ETF 盘后日线与基金历史净值的首选来源（CHG-MD-001）；手工产品/价格/净值为降级路径。不得把盘后数据写成「实时」。

## 4. 硬约束
### 4.1 技术栈与架构
已冻结：Java 25 / Spring Boot 4 / Modulith / jOOQ / Flyway / PostgreSQL；Web（React+Vite+TanStack Query+Zustand+ECharts）；Mobile（Expo+NativeWind+SQLite）；OpenAPI 3.1；JUnit/Testcontainers/Vitest/Playwright/Maestro。工具链见 `ADR-024`（Maven Wrapper、pnpm 10.4.1、Node 22.22.3）。未经新任务与 ADR，不得引入 WebFlux、微服务、Kafka、K8s、ES、默认 Redis、另一套 ORM/状态库/UI 体系。保持模块化单体与 `interfaces/application/domain/infrastructure` 分层：

- 模块不得直访另模块 Repository/表；跨模块用 port 或领域事件。jOOQ 类型不得泄漏到 domain/API DTO。
- 外部邮件/对象存储/同花顺/汇率须经端口适配器。事实写入与 outbox 同事务；消费者必须幂等。

### 4.2 账务（不可破坏）
1. `Transaction + LedgerEntry` 是资产和负债变化的唯一账务事实；不得直接改余额、持仓或统计投影来制造业务结果。
2. 余额重建汇总所有已入账的 `POSTED` 分录，包括原交易及其冲正分录。不得因原交易已被冲正或状态变化而排除原分录。
3. 修改已确认交易使用“原交易 + 冲正交易 + 新交易”；作废使用原交易及冲正交易。已确认事实不得物理删除或原地改写。
4. 每笔已入账交易必须在每个币种内借贷平衡，整笔交易和分录原子提交。
5. 客户端只提交语义命令，不得提交任意借贷分录或内部科目 ID。Java `BigDecimal` / 库 `NUMERIC` / API 十进制字符串；禁止二进制浮点。
6. 入账金额、手续费和税费在入账边界按币种精度 `HALF_UP`；中间计算不得提前舍入，统计只在最终展示时舍入。
7. `business_date` 入账后固定；用户修改时区不得改变历史日、月、年归属。
8. `availableBalance = ledgerBalance - unavailableAmount`。`LiquidityHold` 可审计但不产生 `LedgerEntry`。
9. 投资手续费和税费统一费用化，不进入持仓成本；`POSITION_COST` 只用于成本重建，不重复计入资产总额。
10. 计入开关和比例按 membership 周期保存，只影响生效时间之后的数据，不回改历史统计。
11. 余额、持仓和统计投影必须能从事实表重建；缺失价格或汇率不得静默按 0 或 1 处理。
12. 收益日历与总资产日历由服务端计算；本金进出不得误算为收益；零收益/非交易日/无持仓/无资产/待数据/缺估值用不同状态。

### 4.3 身份、幂等与同步
- 认证≠授权：账户相关用例须在 application 边界校验 `AccountMember` 周期与角色。
- Web：HttpOnly Cookie 刷新 + CSRF；Mobile：响应体刷新 + 系统安全存储。不得混用。
- 写操作幂等：用户 + API 主版本 + operationId + Idempotency-Key，并比较规范化请求 Hash；同键异参冲突。
- 更新用 `ETag/If-Match`；不得最后写入覆盖。服务端是权威；Mobile SQLite 只是缓存/队列/游标。
- 成员移除发 `ACCESS_REVOKED`；审计只追加，业务角色不得改删审计与已入账事实。

## 5. 同步与验证
改动落在哪就同步哪份基线，不机械全量同步：API 行为改 `API契约.md` + OpenAPI + 生成类型；结构改动走 Flyway 并同步 `数据库设计.md`（已进共享/staging/production 的迁移只能新增更高版本）。细节见 `openapi/AGENTS.md`、`backend/AGENTS.md`。

验证同样只覆盖受影响范围：

| 改动 | 验证 |
| --- | --- |
| 文档、文案、样式、非关键展示、测试自身 | `git diff --check` 或贴近的静态检查 |
| 普通 CRUD、查询、页面、非账务服务 | 受影响模块 check/编译/相关单测 |
| API、OpenAPI、迁移、跨模块、外部适配器 | 契约 + 集成/迁移验证，保留证据 |
| 账务/金额/冲正、权限、幂等、同步冲突、并发、删除、审计、安全 | 真实 PostgreSQL/事务/权限矩阵/重放/回滚验证，禁止纯 Mock 替代 |

- 未跑的检查记 `not applicable`；不得把未运行、失败、`skipped` 或他人提交的结果当作证据。
- E2E/Maestro/性能/恢复/真实冒烟/深度供应链扫描属批次、发布、夜间或显式手动，非普通 PR 要求。
- 扫描器失败、泄密、危险 workflow、HIGH/CRITICAL 必须 fail closed；`LOW`/`MODERATE` 进风险台账。

## 6. 注释
只为无法从代码理解的业务不变量、财务语义、安全边界、并发原因、兼容 workaround 写简洁中文注释。不写逐行复述；注释不得描述未实现功能或与实现不一致。
