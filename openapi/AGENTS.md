# Ziji OpenAPI — 契约与生成类型局部规范

- API 行为变化在同一任务中同步 `doc/API契约.md` 与 `openapi/ziji-v1.yaml`；两者不一致时不得让其中一方静默覆盖另一方。
- `operationId` 必须稳定且唯一，并按实际语义补全认证、对象权限、幂等、ETag/If-Match、分页和 Problem Details；同步对应 RTM、`T-*` 用例和契约测试。
- `packages/api-types/generated/ziji-v1.d.ts` 只能由 openapi-typescript 生成，不得手工修改。实际脚本以根 `package.json` 为准：`pnpm api:check`、`pnpm api:generate`、`pnpm api:types:check`。
- breaking change 仅在契约变化或发布门禁需要时运行 `pnpm api:breaking`，基线必须显式可追溯；命令因 Docker/环境不可用时如实记录，不能写成通过。
