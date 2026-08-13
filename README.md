# 资迹 Ziji

资迹是面向个人与家庭的资产、负债、投资、共享账户和账单导入应用。当前项目版本为 V0.1，产品、领域、数据库、API、测试、UI 和三端工程基座已经建立；B1～B4 业务功能尚未开始实现。

## 当前可查看内容

- 产品与技术基线：`doc/`
- OpenAPI 3.1 契约：`openapi/ziji-v1.yaml`
- Flyway V001～V007：`backend/src/main/resources/db/migration/`
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

`local` 是默认 profile，测试使用 Testcontainers 注入连接；`staging`/`production` 必须显式设置 `SPRING_PROFILES_ACTIVE` 并注入全部数据库、邮件和对象存储变量，缺失时启动失败。

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

# 执行 V001～V007 并从真实 PostgreSQL schema 生成 jOOQ 类型
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
