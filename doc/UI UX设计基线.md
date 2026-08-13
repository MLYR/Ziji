# 资迹 Ziji — UI/UX 设计基线

**文档版本：** V0.1  
**对应产品版本：** V0.1  
**原型入口：** `prototypes/open-design/ziji-v1/index.html`  
**适用范围：** Web 1440px、Mobile 390px、深色与浅色主题  
**状态：** 已确认的生产 UI 实现基线

## 1. 基线声明

用户于 2026-08-13 选定 OpenDesign 产出的资迹原型作为 V1 UI 评审基线。项目内副本是后续实现与评审的事实源，外部生成目录不再作为开发依赖。

后续新建或迭代原型必须遵守 `AGENTS.md`§8.2 与 `prototypes/README.md`；可复用提示词以 `prototypes/prompts/` 为唯一项目事实源，外部工具工作区、skill、插件和会话不属于本基线。

本基线包含：

- Web：登录、Dashboard、账户、流水、账单导入、投资、共享与设置。
- Mobile：登录、首页、账户与详情、流水与详情、投资、设置、离线、同步中、冲突和权限拒绝。
- 专项日历：总资产/净资产变化日历、投资收益日历，均覆盖 Web/Mobile 与深浅主题。
- 设计系统：颜色、字体、4px 间距、圆角、图标、图表、动效和可访问性原则。

高保真原型用于冻结信息架构、视觉语言、关键交互和状态语义，不是生产代码。React、React Native 和服务端实现必须遵守 PRD、领域、API 与数据库基线，不得从演示数据反推业务规则。

## 2. 设计语言与 Token

详细 Token 见：

- `prototypes/open-design/ziji-v1/design-system/ziji/MASTER.md`
- `prototypes/open-design/ziji-v1/brand-spec.md`
- `prototypes/open-design/ziji-v1/assets/ziji.css`

冻结规则：

| 维度 | 基线 |
| --- | --- |
| 主题 | 深色为主要展示主题，浅色提供等价信息与交互 |
| 品牌色 | 橙色用于主操作和关键焦点；洋红仅用于链接或少量图表序列 |
| 字体 | 中文使用 HarmonyOS Sans SC / PingFang SC / Noto Sans SC；金额和日期使用等宽数字 |
| 间距 | 4px 基础网格，主要级别 8/12/16/24/32px |
| 圆角 | 控件 8px，容器 10～12px，不使用大面积胶囊卡片 |
| 控件尺寸 | Web 最小高度 40px；Mobile 最小触控区域 44px |
| 图表 | ECharts 实现；必须同时提供单位、图例、提示和文字摘要 |
| 动效 | Web Motion Token 固定为 160/220/300ms，只表达状态或空间关系，并尊重 `prefers-reduced-motion` |

颜色是辅助编码。盈亏、风险、过期、同步、冲突和数据质量必须同时使用文字、图标或状态标签表达。

### 2.1 Web Motion 规则

- 复杂进入、退出和序列编排使用 GSAP 与 `@gsap/react`；React 组件只能从 `web/src/motion/` 使用项目封装，必须具备 scope 和卸载清理。
- 简单 hover、focus、颜色和单控件状态继续使用 CSS，不为单个淡入引入独立 timeline。
- 默认只动画 transform 与 `autoAlpha`；图表动画由 ECharts 管理，金额滚动不得阻碍用户读取最终值。
- `prefers-reduced-motion: reduce` 时跳过装饰动画并立即呈现最终内容；关闭动画不得导致内容隐藏、焦点丢失或交互延迟。
- Aurora Spire 等收费背景不纳入项目。Web 低信息密度页面采用 React Bits Aurora 的项目封装：只允许登录、注册、欢迎和少量空状态使用；Dashboard、账户/流水、投资收益日历、总资产日历、表格和表单内容区禁止使用。
- Aurora 是 `aria-hidden`、`pointer-events: none` 的纯装饰层；`prefers-reduced-motion` 使用 CSS 静态背景，离屏和后台暂停，WebGL 失败不得影响内容。真实内容须独立满足深浅主题 WCAG 2.2 AA 对比度。
- React Bits 授权为 MIT + Commons Clause；可以作为资迹产品的一部分使用和商用，不得出售、再许可或单独/打包/移植后再分发组件本身。授权归档见根目录 `THIRD_PARTY_NOTICES.md`。

## 3. 响应式与平台规则

### 3.1 Web

- 1440px 为主评审宽度，224px 固定侧栏、68px 顶栏。
- 数据密集区域优先使用表格、分隔线和留白；详情优先使用右侧 Sheet。
- 需要中断当前任务的操作使用 Dialog；不可逆或高风险确认使用 AlertDialog。
- 日历必须支持方向键、Home/End、Enter/Space、Escape 和可见焦点。

### 3.2 Mobile

- 390px 为设计基准，不允许页面整体横向滚动。
- 固定五项底部导航并预留安全区；主操作触控区域不小于 44px。
- 日期详情和轻量复杂操作使用底部 Drawer；长表单和完整任务使用全屏页。
- 离线、同步中、冲突和服务端拒绝必须提供明确下一步，不得只显示错误码。

## 4. shadcn/ui 与 Radix 组件映射

Web 正式实现使用 shadcn/ui 组件源码和 Radix primitives。下表是 `UI-DES-004` 的冻结映射；引入组件时仍须按项目 `AGENTS.md` 使用 `shadcn` skill 查询当前版本文档。

| 原型模式 | shadcn/ui / Radix 实现 | 必需行为 |
| --- | --- | --- |
| 固定侧栏与分组导航 | `Sidebar`、`SidebarMenu`、`SidebarMenuButton` | 当前路由、折叠、键盘导航、Mobile 替代入口 |
| 顶部路径与页标题 | `Breadcrumb`、语义化标题 | 当前页使用 `aria-current`，不得只靠颜色 |
| 主要/次要/危险操作 | `Button` variants | 默认、hover、focus-visible、pressed、disabled、loading |
| 表单布局 | `FieldGroup`、`Field`、`FieldLabel`、`FieldDescription`、`FieldError` | 错误紧邻字段；`data-invalid` 与 `aria-invalid` 同步 |
| 文本和金额输入 | `Input`、`InputGroup`、`Textarea` | 金额使用十进制文本输入；前后缀由 InputGroup 组合 |
| 下拉与搜索选择 | `Select`、`Command` + `Popover` | 空结果、加载、错误、禁用和键盘选择 |
| 2～7 项互斥切换 | `ToggleGroup`、`ToggleGroupItem` | 统计范围、金额/收益率等使用 `aria-pressed` 语义 |
| 开关与复选 | `Switch`、`Checkbox`、`RadioGroup` | 标签可点击，禁用原因可见，不用颜色表达状态 |
| 数据表 | `Table`、`ScrollArea` | 排序、筛选、空、加载、错误、部分数据和窄屏降级 |
| 指标与状态 | `Card`、`Badge`、`Separator` | Card 使用完整组合；Badge 文字化表达状态 |
| 用户与成员 | `Avatar` + `AvatarFallback` | 图片失败仍可识别，角色同时显示文字 |
| 页签 | `Tabs` + `TabsList` + `TabsTrigger` | Trigger 必须位于 TabsList，支持键盘切换 |
| 模态表单 | `Dialog` | 必须包含 DialogTitle；打开后聚焦、关闭后恢复焦点 |
| Web 右侧详情 | `Sheet` | 必须包含 SheetTitle；支持 Escape 和焦点陷阱 |
| Mobile 底部详情 | `Drawer` | 必须包含 DrawerTitle；处理安全区和可滚动内容 |
| 危险确认 | `AlertDialog` | 明确结果、主次操作与取消路径 |
| 全局搜索 | `Command` + `Dialog` | 搜索态、空结果、快捷键和读屏结果数 |
| 菜单 | `DropdownMenu` | Item 放在 Group 内；权限不足项禁用并说明原因 |
| 帮助与轻量说明 | `Tooltip`、`Popover` | Tooltip 不承载完成任务所必需的信息 |
| 通知与反馈 | `Alert`、`Progress`、`Spinner`、`Sonner` | 成功、警告、错误和后台进度具有可读文本 |
| 加载与空状态 | `Skeleton`、`Empty` | 保留布局；空、无权限、筛选无结果不得混用 |
| 图表 | shadcn `Chart` 语义容器 + ECharts | ECharts 是绘图引擎；提供文字摘要和数据表替代 |
| 日历网格 | 自有业务组件，组合 `Button`/`ToggleGroup`/`Sheet`/`Drawer` | 不使用日期选择器代替收益展示；支持网格键盘模型 |

Mobile 使用 React Native/NativeWind 的平台组件实现相同语义，不直接复用 Web DOM 组件；视觉 Token 和状态命名保持一致。

## 5. 统一交互状态矩阵

| 状态 | 视觉与文本 | 可操作性 | 无障碍要求 |
| --- | --- | --- | --- |
| Default | 标准表面、边框和标签 | 可操作 | 语义角色和名称完整 |
| Hover | 表面轻微提升，不改变布局 | 仅指针设备 | 不能作为唯一入口 |
| Focus | 2px 语义焦点环 | 键盘可继续操作 | `:focus-visible` 清晰可见 |
| Pressed/Selected | 明确选中表面与文字 | 可再次切换时保留语义 | `aria-pressed/checked/selected` 同步 |
| Disabled | 降低强调并显示原因 | 不响应输入 | 原因不能只藏在 Tooltip |
| Loading | Spinner 或 Skeleton，防重复提交 | 提交按钮禁用 | `aria-busy` 或状态播报 |
| Empty | 说明为何为空和可执行下一步 | 提供适当 CTA | 与加载、无权限严格区分 |
| Error | 原因、影响、重试或恢复路径 | 允许安全重试 | 错误与对应字段/区域关联 |
| No permission | 说明当前角色及需要的权限 | 隐藏或禁用危险操作 | 不泄漏无权访问的数据 |
| Offline | 说明本地可用范围和待同步数量 | 允许合规离线操作 | 同步恢复后播报结果 |
| Conflict | 并列版本、差异和选择后果 | 用户明确选择 | 焦点顺序覆盖两个版本 |
| Stale/Partial | 显示数据截至时间和缺失项 | 允许进入修复入口 | 不将部分结果标成完整值 |

## 6. 日历专项规则

### 6.1 投资收益日历

- 标题固定为“投资收益日历”，支持“全部投资”和“单一标的”。
- 支持收益金额和 Modified Dietz 收益率切换；分母无效时显示“收益率不可用”，不得显示 0%。
- `CALCULATED` 的正、负、真实零收益与 `NON_TRADING_DAY`、`NO_POSITION`、`PENDING_DATA`、`PARTIAL`、`UNPRICED` 必须可区分。
- 全部投资日期详情按标的、券商现金和未归因项展示贡献；贡献合计等于组合日收益。
- 单一标的日期详情展示日初/日终市值、净现金流、价格、汇率、分红、手续费和税费影响。

### 6.2 总资产变化日历

- 标题固定为“总资产日历”，不能命名为投资收益。
- 支持总资产/净资产、变化金额/变化率；内部转账贡献为 0。
- `CALCULATED`、`NO_ASSETS`、`PENDING_DATA`、`PARTIAL`、`UNAVAILABLE` 必须可区分。

## 7. 评审门禁

进入生产 UI 实现前必须完成：

1. 产品确认 Web/Mobile 关键流程和两类日历。
2. 使用实际 Token 执行 WCAG 2.2 AA 对比度检查。
3. 使用键盘和至少一种读屏方式验证登录、记账、账户、流水和日历。
4. 在 1440px Web 与 390px Mobile 检查无非预期溢出和导航遮挡。
5. shadcn 初始化时以本文件组件映射为范围，并通过 `shadcn info --json` 记录实际配置。

## 8. UI-DES-005 评审结论

2026-08-13 已正式确认本基线，可用于 Web/Mobile 生产 UI 实现。评审覆盖 Web 1440×900、Mobile 390×844、深浅主题、登录、总览、账户、流水、投资、两类日历，以及离线、同步冲突和权限拒绝恢复路径。

验证结论：

- 关键页面在目标视口无非预期横向溢出，Mobile 页面宽度保持 390px。
- 离线状态允许继续使用或查看待同步记录；冲突状态提供本地/云端版本选择；权限拒绝状态显示角色原因和返回/联系 OWNER 路径。
- 投资收益日历与总资产日历分别保留既定名称、范围切换、日期状态和明细下钻，不混淆投资收益与资产变化。
- 原型内按钮具有可读名称，Dialog/Sheet/Drawer 的生产实现必须继续使用标题、焦点陷阱、关闭后焦点恢复和状态播报。

原型用于确认设计语义，不作为无障碍代码验收证据。原型通用脚本的键盘焦点管理不稳定，正式实现必须通过 Radix/shadcn 原语、Vitest 组件测试和 Playwright 键盘流程闭合；在这些测试完成前不得把生产页面标记为已验收。
