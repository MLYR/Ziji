# Ziji Mobile — Expo 与客户端局部规范

<!-- 项目已冻结 Expo SDK 56；升级 SDK 时必须同步更新本规则。 -->
涉及 Expo API、Expo Router、原生配置、权限、SecureStore、SQLite、构建、配置插件或 SDK 行为时，先查冻结版本官方文档：https://docs.expo.dev/versions/v56.0.0/ 。纯 TypeScript、UI 文案、样式或不触及 Expo 行为的改动不必重复查询。

- SQLite 只存缓存、待同步队列、游标和冲突，不是账务事实源。
- Mobile 用响应体刷新凭据 + 系统安全存储，不得照搬 Web 的 HttpOnly Cookie 传输方式；Mobile 不引入 GSAP。
- 命令以 `mobile/package.json` 为准：定向检查 `pnpm --filter mobile check`，相关单测 `pnpm --filter mobile test`；Maestro 仅批次、发布或明确 E2E 时跑。
