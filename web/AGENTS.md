# Ziji Web — React、shadcn/ui 与动效局部规范

- TanStack Query 管服务端状态与缓存；Zustand 只存界面、草稿和流程状态。API 类型只用 `packages/api-types/generated/` 的生成结果，不维护平行响应模型。
- shadcn/ui 优先用已安装的 `shadcn` skill；skill 不可用时先读 `components.json`，再查当前官方文档并用项目 pnpm CLI。更新已有组件先 dry-run/diff，未经用户同意不 overwrite，不新增平行 UI 体系。
- 复杂动画用 `src/motion/` 的 GSAP 封装和统一 Token，限定 React scope、自动清理并提供 reduced-motion 静态路径；简单控件过渡用 CSS。
- 动态背景只复用 `src/components/Aurora.tsx`，仅用于登录、注册、欢迎和低信息密度空状态；不得用于 Dashboard、账户/流水、表格、表单和两个日历页，并保留 fallback、暂停策略与授权声明。
- 命令以 `web/package.json` 为准：定向检查 `pnpm --filter web check`，相关单测 `pnpm --filter web test`；Playwright 仅批次、发布或明确 E2E 时跑。
