# Ziji Mobile — Expo 与客户端局部规范

<!-- 项目已冻结 Expo SDK 56；升级 SDK 时必须同步更新本规则。 -->
任务涉及 Expo API、Expo Router、原生配置、权限、SecureStore、SQLite、构建、配置插件或 SDK 行为时，必须先查询冻结版本的官方文档：https://docs.expo.dev/versions/v56.0.0/ 。纯 TypeScript、UI 文案、样式或不触及 Expo 行为的业务状态修改不要求重复查询。

- SQLite 只保存缓存、待同步队列、游标和冲突；不得创建客户端账务事实或复制服务端余额、持仓、收益与权限结论。
- Mobile 使用响应体刷新凭据和系统安全存储，不得照搬 Web 的 HttpOnly Cookie 传输方式；Mobile 不引入 GSAP。
- 实际命令以 `mobile/package.json` 为准：定向检查使用 `pnpm --filter mobile check`，相关单测使用 `pnpm --filter mobile test`；Maestro 仅在批次、发布或明确 E2E 验证时执行。
