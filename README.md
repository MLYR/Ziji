# 资迹 Ziji

资迹是面向个人与家庭的资产、负债、投资、共享账户和账单导入应用。当前项目版本为 V0.1，产品、领域、数据库、API、测试、UI 和三端工程基座已经建立；B1～B4 业务功能尚未开始实现。

## 当前可查看内容

- 产品与技术基线：`doc/`
- OpenAPI 3.1 契约：`openapi/ziji-v1.yaml`
- Flyway V001～V012：`backend/src/main/resources/db/migration/`
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

认证密钥不提供仓库默认值。启动 `local` 后端前，在同一终端生成三把相互独立的本地临时 32 字节密钥；不要把命令输出提交到仓库，也不要在限流 HMAC、匿名幂等 HMAC 与 KEK 之间复用：

```bash
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

`local` 是默认 profile，测试使用 Testcontainers 注入连接；`staging`/`production` 必须显式设置 `SPRING_PROFILES_ACTIVE`，并由部署环境或密钥管理系统注入数据库、邮件、对象存储、既有 HMAC/KEK 变量以及 `ZIJI_AUTH_ACCESS_TOKEN_CURRENT_KID`、`ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PRIVATE_KEY_PKCS8_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_CURRENT_PUBLIC_KEY_X509_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_KID`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_PUBLIC_KEY_X509_BASE64`、`ZIJI_AUTH_ACCESS_TOKEN_PREVIOUS_PUBLIC_KEY_RETENTION`。RSA 测试密钥只允许 test profile 使用，不能用于 `local`、`staging` 或 `production`；BE-AUTH-004 将绑定并校验这些会话密钥配置。

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

生成文件位于 `packages/api-types/generated/ziji-v1.d.ts`，只能由 `openapi-typescript` 更新，不得手工编辑。

## 4. Flyway 与 jOOQ

```bash
# 查看本地数据库迁移状态
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  ./backend/mvnw -f backend/pom.xml -Pdb-codegen flyway:info

# 执行 V001～V012 并从真实 PostgreSQL schema 生成 jOOQ 类型
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  ./backend/mvnw -f backend/pom.xml -Pdb-codegen generate-sources
```

jOOQ 生成物位于 `backend/target/generated-sources/jooq`，属于可重建产物，不提交 Git。

## 5. 最小验证

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
- shadcn/ui 的初始化、组件引入和修改必须使用已安装的 `shadcn` skill。
