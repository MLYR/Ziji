# 资迹 Ziji

资迹是面向个人与家庭的资产、负债、投资、共享账户和账单导入应用。当前项目版本为 V0.1，产品、领域、数据库、API、测试、UI 和三端工程基座已经建立；B1～B4 业务功能尚未开始实现。

## 当前可查看内容

- 产品与技术基线：`doc/`
- OpenAPI 3.1 契约：`openapi/ziji-v1.yaml`
- Flyway V001～V013：`backend/src/main/resources/db/migration/`
- UI 原型入口：`prototypes/open-design/ziji-v1/index.html`
- 当前任务与依赖：`doc/开发进度与任务跟踪.md`

## 工具链

- Java 25
- Maven Wrapper 3.9.16
- Node.js 22.22.3
- pnpm 10.4.1 workspace
- Docker（当前本机使用 OrbStack）
- PostgreSQL 17.6、Mailpit 1.27.8、MinIO

## 仓库结构

```text
backend/              Spring Boot 模块化单体与 Flyway
web/                  React + TypeScript + Vite
mobile/               React Native + Expo
packages/api-types/   OpenAPI 生成的共享 TypeScript 类型
openapi/              机器可读 API 契约
prototypes/           高保真 UI 原型
scripts/              跨工程开发与校验脚本
doc/                  需求、设计、测试、任务和 ADR
```

## 1. 首次准备

```bash
# Java 25 已安装在本机；每个新的终端先固定本项目 JDK。
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
java -version

# 安装严格锁定的 Node workspace 依赖。
corepack enable
pnpm install --frozen-lockfile --strict-peer-dependencies

# 启动 PostgreSQL、邮件模拟器和私有对象存储。
docker compose up -d
docker compose ps
```

本地服务地址：PostgreSQL `localhost:5432/ziji`、Mailpit [http://localhost:8025](http://localhost:8025)、MinIO API `http://localhost:9000`、MinIO Console [http://localhost:9001](http://localhost:9001)。本地账号仅定义在 `compose.yaml`，不得复用到其他环境。

如需覆盖默认配置，复制 `.env.example` 为 `.env`，修改后在启动后端的同一终端加载：

```bash
set -a
source .env
set +a
```

认证与账户游标密钥不提供仓库默认值。启动 `local` 后端前，在同一终端生成四把相互独立的本地临时 32 字节密钥；不要把命令输出提交到仓库，也不要在账户游标、限流 HMAC、匿名幂等 HMAC 与 KEK 之间复用：

```bash
export ZIJI_ACCOUNT_CURSOR_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_LIQUIDITY_HOLD_CURSOR_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_HMAC_CURRENT_KEY_VERSION=2
export ZIJI_AUTH_HMAC_CURRENT_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_IDEMPOTENCY_CURRENT_KEY_VERSION=2
export ZIJI_AUTH_IDEMPOTENCY_CURRENT_KEY_BASE64="$(openssl rand -base64 32)"
export ZIJI_AUTH_ENVELOPE_KEK_VERSION=1
export ZIJI_AUTH_ENVELOPE_KEK_BASE64="$(openssl rand -base64 32)"
```

`ZIJI_AUTH_HMAC_PREVIOUS_KEY_VERSION` 与 `ZIJI_AUTH_HMAC_PREVIOUS_KEY_BASE64` 必须同时设置或同时留空；配置上一版本时，`ZIJI_AUTH_HMAC_PREVIOUS_KEY_RETENTION` 不得小于 `48h`。匿名幂等的 `ZIJI_AUTH_IDEMPOTENCY_PREVIOUS_KEY_VERSION` 与 `ZIJI_AUTH_IDEMPOTENCY_PREVIOUS_KEY_BASE64` 同样必须成对设置，且 `ZIJI_AUTH_IDEMPOTENCY_PREVIOUS_KEY_RETENTION` 不得小于 `168h`。`ZIJI_AUTH_TRUSTED_PROXY_ADDRESSES` 缺失或为空表示不信任任何代理。测试 profile 中的固定 HMAC/KEK 只用于自动测试，不能用于 `local`、`staging` 或 `production`。

Access Token 使用 RS256。启动本地会话实现前，生成至少 2048 位的临时 RSA 私钥与对应公钥，再将 PKCS#8 私钥和 X.509 公钥分别 Base64 为单行环境变量；`kid` 由部署受控分配。上一公钥与上一 `kid` 必须同时设置或同时留空，且 `ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_PUBLIC_KEY_RETENTION` 不得小于 `24h`。示例命令如下，生成结果与临时 PEM 文件都不得提交：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/ziji-access-token-private.pem
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_KID=local-rs256-1
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PRIVATE_KEY_PKCS8_BASE64="$(openssl pkcs8 -topk8 -nocrypt -in /tmp/ziji-access-token-private.pem -outform DER | base64 | tr -d '\n')"
export ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PUBLIC_KEY_X509_BASE64="$(openssl pkey -in /tmp/ziji-access-token-private.pem -pubout -outform DER | base64 | tr -d '\n')"
```

设置前在不输出密钥内容的前提下验证 DER 编码可读：

```bash
printf '%s' "$ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PRIVATE_KEY_PKCS8_BASE64" \
  | openssl base64 -d -A | openssl pkey -inform DER -noout
printf '%s' "$ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PUBLIC_KEY_X509_BASE64" \
  | openssl base64 -d -A | openssl pkey -pubin -inform DER -noout
```

`local` 是默认 profile，测试使用 Testcontainers 注入连接；`staging`/`production` 必须显式设置 `SPRING_PROFILES_ACTIVE`，并由部署环境或密钥管理系统注入数据库、邮件、对象存储、`ZIJI_ACCOUNT_CURSOR_KEY_BASE64`、`ZIJI_LIQUIDITY_HOLD_CURSOR_KEY_BASE64`、既有 HMAC/KEK 变量以及 `ZIJI_AUTH_ACCESS_TOKEN_CURRENT_KID`、`ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PRIVATE_KEY_PKCS8_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PUBLIC_KEY_X509_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_KID`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_PUBLIC_KEY_X509_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_PUBLIC_KEY_RETENTION`。RSA 测试密钥只允许 test profile 使用，不能用于 `local`、`staging` 或 `production`；BE-AUTH-004 将绑定并校验这些会话密钥配置。

## 2. 开发入口

```bash
# Backend；启动时会自动执行 Flyway。
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  ./backend/mvnw -f backend/pom.xml spring-boot:run

# Web
pnpm --filter web dev

# Mobile
pnpm --filter mobile start
```

后端健康检查为 `http://localhost:8080/actuator/health`。当前三个入口是生产技术栈的最小应用壳，不包含伪造余额或业务数据。

## 3. OpenAPI 与共享类型

```bash
# OpenAPI 3.1 lint、示例与 operationId 校验
pnpm api:check

# 与指定基线文件比较，发现不兼容变化时返回非零退出码
pnpm api:breaking -- --base-file /absolute/path/to/base-openapi.yaml

# 与 Git 提交比较；CI 使用 pull_request.base.sha，避免比较当前分支自身
pnpm api:breaking -- --base-ref "$(git rev-parse HEAD^)"

# 生成 Web/Mobile 共用类型
pnpm api:generate

# 检查已提交类型是否与契约漂移
pnpm api:types:check
```

CI 的 `pull_request` 使用真实的 `github.event.pull_request.base.sha` 执行 breaking comparison；`workflow_dispatch` 没有 PR base，该比较为 not applicable 并保持 skipped。skipped 不等于 passed，也不能作为 PR required CI 证据；手动运行仍会执行 `api:check`、`api:generate` 和 `api:types:check`。

生成文件位于 `packages/api-types/generated/ziji-v1.d.ts`，只能由 `openapi-typescript` 更新，不得手工编辑。

## 4. Flyway 与 jOOQ

```bash
# 查看本地数据库迁移状态
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  ./backend/mvnw -f backend/pom.xml -Pdb-codegen flyway:info

# 执行 V001～V013 并从真实 PostgreSQL schema 生成 jOOQ 类型
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  ./backend/mvnw -f backend/pom.xml -Pdb-codegen generate-sources
```

jOOQ 生成物位于 `backend/target/generated-sources/jooq`，属于可重建产物，不提交 Git。

## 5. 验证与提交流程

本地只执行与改动相关的验证，PR CI 在统一环境执行一次完整核心门禁；合并 main 后不再自动重复同一套 CI。L3 高风险任务才增加定向深度验证和独立审查。

### 5.1 提交前最小验证

- 文档、注释或原型：`git diff --check`。
- Web/Mobile：只执行对应的 `check` 和受影响测试，不连带运行其他端。
- 普通 Backend：只执行相关测试类，不默认运行完整 Maven。
- OpenAPI 发生变化时：`pnpm api:check && pnpm api:generate && pnpm api:types:check`。
- Migration 发生变化时：执行 PostgreSQL Testcontainers、Flyway 空库和上一版本升级验证。
- 账务、金额、余额、权限、认证、幂等、同步冲突、并发、迁移、breaking contract、删除、审计和安全属于 L3，补充相应深度验证。

提交前检查暂存区：

```bash
git diff --cached --check
git diff --cached --stat
```

### 5.2 提交后完整门禁

建立 PR 后等待 GitHub Actions required checks 全部成功再合并；合并后不重复执行同一套 CI。PR 中修改 `.github/workflows/**` 时，默认分支版本的 `Workflow security` 门禁只把 PR 文件作为数据交给固定版本 zizmor 分析，不 checkout 或执行 PR 代码。该 check 只有被活动 Ruleset 列入 `required_status_checks` 后才是合并阻断证据，workflow 文件存在本身不代表已经 required。L3 高风险改动按任务风险补充独立审查和专项证据，不机械重跑已成功且适用的门禁。

### 5.3 各工程验证命令

```bash
# Backend：JUnit、Modulith 边界、Testcontainers 空库 Flyway
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
DOCKER_HOST=unix:///Users/zreo/.orbstack/run/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ./backend/mvnw -f backend/pom.xml test

# Web：TypeScript/Oxlint、Vitest；本机可复用稳定 Chrome
pnpm --filter web check
pnpm --filter web test
ZIJI_PLAYWRIGHT_CHANNEL=chrome pnpm --filter web test:e2e

# CI 或没有 Chrome 的机器安装 Playwright 锁定版 Chromium 后执行
pnpm --filter web exec playwright install chromium
pnpm --filter web test:e2e

# Mobile：TypeScript/Expo 配置、Jest、依赖兼容性
pnpm --filter mobile check
pnpm --filter mobile test
pnpm --filter mobile exec expo install --check

# Maestro 需已安装 iOS/Android 原生模拟器和 Maestro CLI
pnpm --filter mobile test:e2e
```

若 Docker Desktop 而非 OrbStack 提供 Docker，后端测试通常不需要设置两个 Docker 环境变量。

## 关键规则

- 所有工作先关联 `doc/开发进度与任务跟踪.md` 中的叶子任务。
- 账务事实、权限、幂等、同步和客户端职责遵守根目录 `AGENTS.md`。
- shadcn/ui 的初始化、组件引入和修改优先使用已安装的 `shadcn` skill；skill 不可用时按 `web/AGENTS.md` 的 `components.json`、官方文档、项目 pnpm CLI fallback 执行。
