# Ziji Web — React、shadcn/ui 与动效局部规范

- TanStack Query 管理服务端状态与缓存；Zustand 只保存界面、草稿和流程状态。API 类型只使用 `packages/api-types/generated/` 的生成结果，不维护平行响应模型。
- shadcn/ui 工作优先使用已安装的 `shadcn` skill。skill 不可用时先读取 `components.json`，再查当前官方文档并使用项目 pnpm CLI；更新已有组件先 dry-run/diff，未经用户同意不得 overwrite，不新增平行 UI 体系。
- Web 复杂动画使用 `src/motion/` 的 GSAP 封装和统一 Token，按已安装 GSAP skills 限定 React scope、自动清理并提供 reduced-motion 静态路径；简单控件过渡使用 CSS。
- 动态背景只复用 `src/components/Aurora.tsx`，仅用于登录、注册、欢迎和低信息密度空状态；禁止用于 Dashboard、账户/流水、表格、表单、投资收益日历和总资产日历，并保留 fallback、暂停策略与授权声明。
- 实际命令以 `web/package.json` 为准：定向检查使用 `pnpm --filter web check`，相关单测使用 `pnpm --filter web test`；Playwright 仅在批次、发布或明确 E2E 验证时执行。
