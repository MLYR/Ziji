# ADR-026：Web Aurora 动态背景

**状态：** 已采纳  
**日期：** 2026-08-13  
**关联任务：** CHG-UI-003

## 背景

资迹希望在低信息密度页面增加克制的科技氛围。动态背景属于纯装饰层，不能降低财务数据可读性、可访问性或低端设备响应，也不能成为登录等核心功能的运行前提。

## 决策

- Web 采用通过 shadcn `@react-bits/Aurora-TS-TW` registry 引入并经项目改造的 React Bits Aurora，运行依赖固定为 `ogl 1.0.11`；Mobile 不引入。
- 只允许用于登录、注册、欢迎页和低信息密度空状态。Dashboard、账户/流水列表、投资收益日历、总资产日历、表格和表单内容区禁止使用。
- 组件是 `aria-hidden`、`pointer-events: none` 的装饰层，真实内容必须独立存在并满足深浅主题对比度要求。
- `prefers-reduced-motion: reduce` 时不初始化 WebGL；WebGL、shader 或上下文初始化失败时保留 CSS 静态背景，不能阻断页面。
- 组件离开视口或页面进入后台时停止 RAF；分辨率限制为 1.5 DPR，默认最多 30fps，卸载时清理 RAF、Observer、监听器、Canvas 与 WebGL context。
- React Bits 使用 MIT + Commons Clause：可以作为资迹应用的一部分使用和商用，不得出售、再许可或单独/打包/移植后再分发组件本身。授权全文保存在根目录 `THIRD_PARTY_NOTICES.md`。

## 结果

业务页面只能使用 `web/src/components/Aurora.tsx` 的项目封装，不得直接调用 OGL、再次从 registry 引入原版或增加第二套动态背景库。背景的性能、可访问性和静态降级由 Vitest 与后续目标设备浏览器测试持续验证。
