# 资迹 Ziji — 活动看板

**更新日期：** 2026-09-08  
**状态源：** 自归档台账 `archive/ledger-v1-full.md` 抽取；日常只维护本文件与 `STATUS.md`。  
**范围：** 全部非 `DONE` / `DEFERRED` / `CANCELLED` 叶子任务（当前焦点批次 E20/E30/E40/E90）。

## B3 收口近况（`BUG-B3-001`）

- **状态：** `IN_PROGRESS`（P0 / B3 / 父任务 E30-F03）
- **目标：** 核对并补齐 B3 验收证据、三端流程覆盖和基线冲突；不回写原任务历史 `DONE`，不机械提升 RTM。
- **已通过证据（2026-09-07）：** Backend B3 定向 55 例（0 失败/错误、1 skip）；真实同花顺三类冒烟 1/1；Web `check`/`build` + 真实 Backend proxy E2E 5/5；Mobile `check` + 20 suites/174 tests。
- **仍缺：** 原生 Mobile Maestro 投资 E2E（`adb devices` = 0）；独立 R3 复核。
- **已闭合（2026-09-18）：** `T-GATE-001` 书面授权门禁——用户出具书面风险接受记录，见 `doc/同花顺数据源合规评估.md` §6 与 `doc/测试与验收方案.md` T-GATE-001 行。
- **详核：** [`doc/B3验收收口核对.md`](../B3验收收口核对.md) · 一页状态见 [`STATUS.md`](STATUS.md)

## 活动区（`IN_PROGRESS` / `BLOCKED` / `REVIEW` / `VERIFYING` / `READY`）

| 任务 ID | 父任务 | 任务 | 状态 | 优先级 | 批次 | EPIC |
| --- | --- | --- | --- | --- | --- | --- |
| BUG-B3-001 | E30-F03 | 核对并补齐 B3 验收证据、三端流程覆盖和基线冲突 | `IN_PROGRESS` | P0 | B3 | E30 |

## 未完成任务（共 99 项）

下列按 EPIC 分组；组内活动状态优先，其余为 `BACKLOG`，按优先级与任务 ID 排序。

### E30 — B3 投资、产品与市场数据（1）

| 任务 ID | 父任务 | 任务 | 状态 | 优先级 | 批次 |
| --- | --- | --- | --- | --- | --- |
| BUG-B3-001 | E30-F03 | 核对并补齐 B3 验收证据、三端流程覆盖和基线冲突 | `IN_PROGRESS` | P0 | B3 |

### E20 — B2 导入与共享（29）

| 任务 ID | 父任务 | 任务 | 状态 | 优先级 | 批次 |
| --- | --- | --- | --- | --- | --- |
| BE-IMP-001 | E20-F02 | 实现 multipart 流式上传、摘要、类型/签名/大小校验和私有存储 | `BACKLOG` | P0 | B2 |
| BE-IMP-004 | E20-F02 | 建立受限解析工作进程和公式/宏/恶意输入防护 | `BACKLOG` | P0 | B2 |
| BE-IMP-007 | E20-F03 | 实现文件摘要、外部流水号和幂等键精确去重 | `BACKLOG` | P0 | B2 |
| BE-IMP-009 | E20-F03 | 实现导入状态机、预览、逐行入账和批次结果 | `BACKLOG` | P0 | B2 |
| BE-IMP-010 | E20-F03 | 实现整批撤销、逐交易冲正和重复撤销幂等 | `BACKLOG` | P0 | B2 |
| BE-SHR-001 | E20-F01 | 实现邀请创建、接受、拒绝、撤销、过期和重复控制 | `BACKLOG` | P0 | B2 |
| BE-SHR-002 | E20-F01 | 实现 OWNER/EDITOR/VIEWER 对象级授权策略并覆盖全部账户资源 | `BACKLOG` | P0 | B2 |
| BE-SHR-003 | E20-F01 | 实现唯一 OWNER、所有权转让、退出和移除成员事务 | `BACKLOG` | P0 | B2 |
| BE-SHR-004 | E20-F01 | 实现 membership_no 历史周期和再次加入新行 | `BACKLOG` | P0 | B2 |
| BE-SHR-005 | E20-F01 | 实现本人计入开关、0%～100% 比例和历史生效区间 | `BACKLOG` | P0 | B2 |
| BE-SHR-006 | E20-F01 | 为成员加入生成 bootstrap，为移除生成定向 ACCESS_REVOKED | `BACKLOG` | P0 | B2 |
| EXT-S3-001 | E20-F02 | 选定 S3 兼容对象存储并实现私有对象、生命周期和下载授权适配器 | `BACKLOG` | P0 | B2 |
| QA-B2-001 | E20-F03 | 完成 B2 共享和导入 Web/Mobile 端到端回归 | `BACKLOG` | P0 | B2 |
| QA-IMP-002 | E20-F02 | 完成上传安全、解析、映射和行级错误自动测试 | `BACKLOG` | P0 | B2 |
| QA-IMP-003 | E20-F03 | 完成精确/疑似去重、并发确认、批次撤销和余额恢复测试 | `BACKLOG` | P0 | B2 |
| QA-SHR-001 | E20-F01 | 完成成员状态机、角色矩阵、唯一 OWNER 和再次加入测试 | `BACKLOG` | P0 | B2 |
| QA-SHR-002 | E20-F01 | 完成移除后访问、离线待提交拒绝和定向同步安全测试 | `BACKLOG` | P0 | B2 |
| BE-IMP-002 | E20-F02 | 实现微信和支付宝账单格式识别及解析适配器 | `BACKLOG` | P1 | B2 |
| BE-IMP-003 | E20-F02 | 实现银行 Excel/CSV 和通用 CSV 解析及字段映射 | `BACKLOG` | P1 | B2 |
| BE-IMP-005 | E20-F02 | 实现行级错误定位、修正、排除和映射版本 | `BACKLOG` | P1 | B2 |
| BE-IMP-008 | E20-F03 | 实现金额/时间/商户疑似重复评分和用户决策 | `BACKLOG` | P1 | B2 |
| DOC-B2-001 | E20-F03 | 同步 B2 实现状态、适配格式、权限和验收证据 | `BACKLOG` | P1 | B2 |
| QA-IMP-001 | E20-F02 | 建立五类账单金标准文件和编码/日期/负数/隐藏列样例 | `BACKLOG` | P1 | B2 |
| WEB-IMP-001 | E20-F03 | 实现 Web 上传、解析进度、映射和行级修正流程 | `BACKLOG` | P1 | B2 |
| WEB-IMP-002 | E20-F03 | 实现 Web 重复处理、预览确认、批次结果和整批撤销 | `BACKLOG` | P1 | B2 |
| WEB-SHR-001 | E20-F01 | 实现 Web 成员列表、邮箱邀请、角色修改和所有权转让 | `BACKLOG` | P1 | B2 |
| WEB-SHR-002 | E20-F01 | 实现 Web 本人计入开关、比例和只影响未来数据的说明 | `BACKLOG` | P1 | B2 |
| BE-IMP-006 | E20-F03 | 实现导入规则分类和用户映射复用 | `BACKLOG` | P2 | B2 |
| MOB-SHR-001 | E20-F01 | 实现 Mobile 邀请处理、成员查看和本人计入设置 | `BACKLOG` | P2 | B2 |

### E40 — B4 多币种与增强能力（46）

| 任务 ID | 父任务 | 任务 | 状态 | 优先级 | 批次 |
| --- | --- | --- | --- | --- | --- |
| BE-DATA-001 | E40-F03 | 实现个人数据导出异步任务、授权下载和到期清理 | `BACKLOG` | P0 | B4 |
| BE-DATA-002 | E40-F03 | 实现账号注销申请、取消、执行和共享数据保护 | `BACKLOG` | P0 | B4 |
| BE-FX-001 | E40-F01 | 实现 CNY/USD/HKD/JPY/EUR 币种和方向统一模型 | `BACKLOG` | P0 | B4 |
| BE-FX-002 | E40-F01 | 实现自动汇率适配器、最新有效汇率和历史日期查询 | `BACKLOG` | P0 | B4 |
| BE-FX-003 | E40-F01 | 实现汇率新鲜度、缺失/过期降级且禁止静默使用 0 或 1 | `BACKLOG` | P0 | B4 |
| BE-FX-004 | E40-F01 | 实现手工汇率、历史修正、revision 和审计优先级 | `BACKLOG` | P0 | B4 |
| BE-FX-005 | E40-F01 | 实现跨币种转账两端金额、实际汇率、费用和分币种平衡 | `BACKLOG` | P0 | B4 |
| BE-FX-006 | E40-F01 | 实现当前基准币种估值和按历史日期汇率的历史折算 | `BACKLOG` | P0 | B4 |
| BE-STAT-001 | E40-F02 | 实现 valuationRevision、日/月/年快照和修订链 | `BACKLOG` | P0 | B4 |
| BE-STAT-002 | E40-F02 | 实现价格、净值或汇率修订影响范围识别与幂等重算任务 | `BACKLOG` | P0 | B4 |
| BE-STAT-003 | E40-F02 | 实现收支、价格、汇率、调整和统计范围变化归因 | `BACKLOG` | P0 | B4 |
| BE-STAT-004 | E40-F02 | 实现基准币种变更后历史默认重算且原币事实不变 | `BACKLOG` | P0 | B4 |
| BE-STAT-005 | E40-F02 | 实现总资产/净资产月历、相邻日变化和日期归因 API | `BACKLOG` | P0 | B4 |
| EXT-FX-001 | E40-F01 | 选定自动汇率供应商、授权、支持币对、刷新频率和配额 | `BACKLOG` | P0 | B4 |
| OPS-BKP-001 | E40-F04 | 配置 PostgreSQL、对象存储和密钥设施备份策略 | `BACKLOG` | P0 | B4 |
| OPS-BKP-002 | E40-F04 | 在隔离环境执行恢复演练和账务/持仓/权限一致性校验 | `BACKLOG` | P0 | B4 |
| OPS-OBS-001 | E40-F04 | 补齐同步、导入、行情、安全、outbox 和投影业务指标 | `BACKLOG` | P0 | B4 |
| OPS-OBS-002 | E40-F04 | 配置关键错误率、积压、过期、恢复和异常登录告警 | `BACKLOG` | P0 | B4 |
| OPS-REL-001 | E40-F04 | 验证提交前/提交后崩溃、outbox 重试和外部源持续失败恢复 | `BACKLOG` | P0 | B4 |
| PM-LEGAL-001 | E40-F03 | 确定隐私主体、数据保留期、注销规则和用户协议 | `BACKLOG` | P0 | B4 |
| QA-B4-001 | E40-F04 | 完成 B4 Web/Mobile 端到端和全部增强能力回归 | `BACKLOG` | P0 | B4 |
| QA-FX-001 | E40-F01 | 完成币种精度、方向、跨币转账和汇率选择金标准测试 | `BACKLOG` | P0 | B4 |
| QA-SEC-001 | E40-F03 | 完成认证、对象级授权、输入攻击和敏感数据泄露测试 | `BACKLOG` | P0 | B4 |
| QA-SEC-002 | E40-F03 | 完成数据导出、注销、共享事实保留和对象清理测试 | `BACKLOG` | P0 | B4 |
| QA-STAT-001 | E40-F02 | 完成修订链、历史自动重算、旧版审计和变化归因测试 | `BACKLOG` | P0 | B4 |
| QA-STAT-002 | E40-F02 | 完成总资产日历金标准、状态、修订和投影重建测试 | `BACKLOG` | P0 | B4 |
| SEC-SRV-001 | E40-F03 | 配置生产 HTTPS、代理信任边界、安全 Header 和传输策略 | `BACKLOG` | P0 | B4 |
| SEC-SRV-002 | E40-F03 | 实施敏感字段加密、密钥分离、轮换和最小访问权限 | `BACKLOG` | P0 | B4 |
| SEC-SRV-003 | E40-F03 | 强化审计日志数据库只读权限、追加写入和保留策略 | `BACKLOG` | P0 | B4 |
| BE-FX-007 | E40-F01 | 实现汇率查询、手工值、修正和状态 API | `BACKLOG` | P1 | B4 |
| BE-REC-001 | E40-F02 | 实现日/周/月/年周期规则、模板、结束日期和唯一发生项 | `BACKLOG` | P1 | B4 |
| BE-REC-002 | E40-F02 | 实现到期只生成待确认项、确认入账和跳过 | `BACKLOG` | P1 | B4 |
| DOC-B4-001 | E40-F04 | 同步 B4 实现状态、外部服务、运维手册和验收证据 | `BACKLOG` | P1 | B4 |
| MOB-DATA-001 | E40-F03 | 实现 Mobile 数据导出状态和注销流程 | `BACKLOG` | P1 | B4 |
| MOB-FX-001 | E40-F01 | 实现 Mobile 跨币种记账、汇率说明和过期提示 | `BACKLOG` | P1 | B4 |
| MOB-STAT-002 | E40-F02 | 实现 Mobile 总资产/净资产变化日历和日期明细 | `BACKLOG` | P1 | B4 |
| QA-A11Y-001 | E40-F03 | 完成 Web/iOS/Android 自动扫描、键盘和读屏可访问性验收 | `BACKLOG` | P1 | B4 |
| QA-REC-001 | E40-F02 | 完成跨月末/闰年/时区/重复调度和确认幂等测试 | `BACKLOG` | P1 | B4 |
| WEB-DATA-001 | E40-F03 | 实现 Web 数据导出、注销申请/取消和风险确认 | `BACKLOG` | P1 | B4 |
| WEB-FX-001 | E40-F01 | 实现 Web 跨币种转账、汇率说明和手工汇率管理 | `BACKLOG` | P1 | B4 |
| WEB-STAT-001 | E40-F02 | 实现 Web 日/月/年趋势、变化归因和 revision/质量提示 | `BACKLOG` | P1 | B4 |
| WEB-STAT-002 | E40-F02 | 实现 Web 总资产/净资产变化日历和日期归因明细 | `BACKLOG` | P1 | B4 |
| BE-IMP-011 | E40-F04 | 基于已确认映射与分类记录优化智能分类规则 | `BACKLOG` | P2 | B4 |
| MOB-REC-001 | E40-F02 | 实现 Mobile 待确认周期项查看、确认和跳过 | `BACKLOG` | P2 | B4 |
| MOB-STAT-001 | E40-F02 | 实现 Mobile 趋势和变化归因摘要 | `BACKLOG` | P2 | B4 |
| WEB-REC-001 | E40-F02 | 实现 Web 周期规则管理和待确认项处理 | `BACKLOG` | P2 | B4 |

### E90 — 全量测试、验收与发布（23）

| 任务 ID | 父任务 | 任务 | 状态 | 优先级 | 批次 |
| --- | --- | --- | --- | --- | --- |
| QA-DAST-001 | E90-F02 | 完成 API/Web 动态安全和全资源对象级越权测试 | `BACKLOG` | P0 | ALL |
| QA-FAIL-001 | E90-F02 | 执行网络、进程、数据库和外部供应商故障注入 | `BACKLOG` | P0 | ALL |
| QA-GATE-001 | E90-F01 | 验证 96 个 HTTP operationId 的正向、校验、401、403/404 和错误契约 | `BACKLOG` | P0 | ALL |
| QA-GATE-002 | E90-F01 | 验证幂等、If-Match/ETag、游标分页和财务字符串精度 | `BACKLOG` | P0 | ALL |
| QA-GATE-003 | E90-F01 | 从空库和上一迁移版本执行 Flyway 升级及全部数据库约束测试 | `BACKLOG` | P0 | ALL |
| QA-GATE-004 | E90-F01 | 删除投影后重建余额、持仓、统计并比对摘要 | `BACKLOG` | P0 | ALL |
| QA-GATE-005 | E90-F01 | 审核每条 V1 RTM 需求的实现、测试和证据链接 | `BACKLOG` | P0 | ALL |
| QA-PERF-002 | E90-F02 | 验证 Dashboard、流水、导入、同步和持仓性能阈值 | `BACKLOG` | P0 | ALL |
| QA-SAST-001 | E90-F02 | 完成 SAST、依赖、镜像、密钥和许可证扫描并清零高危 | `BACKLOG` | P0 | ALL |
| REL-DATA-001 | E90-F03 | 在生产等价副本演练数据库迁移、校验和恢复 | `BACKLOG` | P0 | ALL |
| REL-EXT-001 | E90-F03 | 汇总同花顺、邮件、汇率、对象存储授权和配额证据 | `BACKLOG` | P0 | ALL |
| REL-GO-001 | E90-F03 | 执行 V1 最终验收评审并记录签字和证据链接 | `BACKLOG` | P0 | ALL |
| REL-GO-002 | E90-F03 | 发布 V1 并验证登录、记账、查询、同步和监控冒烟 | `BACKLOG` | P0 | ALL |
| REL-INF-001 | E90-F03 | 建立 staging 和 production 部署配置、HTTPS、数据库和对象存储 | `BACKLOG` | P0 | ALL |
| REL-INF-002 | E90-F03 | 建立应用、迁移和静态 Web 的可回滚部署流水线 | `BACKLOG` | P0 | ALL |
| REL-MOB-001 | E90-F03 | 配置 iOS/Android 标识、签名、权限、隐私清单和构建渠道 | `BACKLOG` | P0 | ALL |
| QA-ACC-003 | E90-F02 | 完成 WCAG 2.2 AA 未达项清单与风险审批 | `BACKLOG` | P1 | ALL |
| QA-COMP-001 | E90-F02 | 完成受支持浏览器、iOS、Android 版本和时区/小数兼容测试 | `BACKLOG` | P1 | ALL |
| QA-PERF-001 | E90-F02 | 构建标准性能数据集和可复现随机种子 | `BACKLOG` | P1 | ALL |
| QA-STAB-001 | E90-F02 | 执行 2 小时持续写入查询和连接/内存泄漏检查 | `BACKLOG` | P1 | ALL |
| REL-GO-003 | E90-F03 | 完成发布后账务摘要校验和回顾记录 | `BACKLOG` | P1 | ALL |
| REL-NOTE-001 | E90-F03 | 编写 V1 发布说明、已知限制、隐私入口和支持说明 | `BACKLOG` | P1 | ALL |
| REL-OPS-001 | E90-F03 | 编写故障处理、数据修复、回滚、恢复和供应商降级手册 | `BACKLOG` | P1 | ALL |

## 维护说明

- 状态变更时同步更新本文件与 `STATUS.md` 的执行面板 / EPIC 汇总。
- 新建叶子任务先写入本看板对应 EPIC 表，并在需要时回填归档台账以外的追踪字段到任务产出说明。
- 已完成任务从本文件移除（或移入完成备注），勿再改归档全量台账正文。
