# ADR-025：Web Motion 与 GSAP

**状态：** 已采纳  
**日期：** 2026-08-13  
**关联任务：** CHG-UI-002

## 背景

资迹需要统一页面进入、状态切换和复杂序列动画，同时必须保持财务数据可读、React 生命周期安全、低端设备性能和 reduced-motion 可访问性。CSS 继续承担简单 hover/focus 状态；复杂编排需要统一运行时工具，避免页面各自选择动画库。

## 决策

- Web 使用精确版本 `gsap 3.15.0` 与 `@gsap/react 2.1.2`；Mobile 不引入 GSAP，继续使用 React Native/Reanimated 能力。
- 所有 React GSAP 代码通过 `web/src/motion/gsap.ts` 的 `useGSAP()` 运行，必须提供 scope 并自动清理。
- Motion Token 位于 `web/src/motion/tokens.ts`，持续时间限制为 160/220/300ms；业务组件不得自行创建另一套全局动画时长和缓动。
- 默认只动画 transform 与 `autoAlpha`，避免 width/height/top/left 等布局属性；ECharts 管理自己的图表动画。
- `prefers-reduced-motion: reduce` 不创建装饰 tween，内容以最终静态状态立即可读。
- 背景视觉库单独选型，GSAP 决策不授权加入 WebGL/Canvas 背景依赖。

## 结果

页面可以复用 `MotionGroup` 等项目封装获得一致动效；新增动画仍需通过静态检查、Vitest、Playwright 和 reduced-motion 验证。不得用动画掩盖加载、错误、冲突或数据质量状态。
