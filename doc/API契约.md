# 资迹 Ziji — API 契约

**文档版本：** V0.1  
**对应产品版本：** V0.1  
**需求基线：** PRD V0.1、RTM V0.1  
**协议：** REST / HTTPS / JSON / OpenAPI 3.1  
**文档状态：** V1 正式契约基线

## 1. 契约目标

本文定义 V1 服务端与 Web、iOS、Android 之间的稳定接口规则，包括资源、命令、认证、幂等、版本冲突、分页、错误和离线同步。

实现阶段必须依据本文生成并维护机器可读的：

```text
openapi/ziji-v1.yaml
```

机器契约必须通过 lint，并由服务端契约测试确认实现没有偏离。本文与 OpenAPI 不一致时，在设计阶段先评审并同步修改两者；不得默认以某一份为准掩盖差异。

## 2. 全局约定

### 2.1 基础信息

```text
Base URL: /api/v1
Content-Type: application/json
Error Content-Type: application/problem+json
Authentication: Authorization: Bearer <access-token>
```

所有请求使用 HTTPS。开发环境可在本机使用受控 HTTP，不能复制到测试或生产配置。

### 2.2 字段格式

| 数据 | JSON 表达 | 示例 |
| --- | --- | --- |
| UUID | string | `0191f42a-...` |
| 时间 | ISO 8601 UTC string | `2026-08-12T08:30:00Z` |
| 业务日期 | `YYYY-MM-DD` | `2026-08-12` |
| 时区 | IANA string | `Asia/Shanghai` |
| 金额 | 十进制 string | `"1000.00"` |
| 数量 | 十进制 string | `"12.345600"` |
| 汇率 | 十进制 string | `"7.123400000000"` |
| 比例 | 十进制 string，0～1 | `"0.700000"` |
| 币种 | ISO 4217 string | `CNY` |

服务端不得在 JSON 中以 number 返回财务数值，避免 JavaScript 二进制浮点损失。

### 2.3 响应结构

单资源：

```json
{
  "data": {
    "id": "0191f42a-0000-7000-8000-000000000001",
    "version": 1
  },
  "meta": {
    "requestId": "req_01"
  }
}
```

集合：

```json
{
  "data": [],
  "meta": {
    "requestId": "req_01",
    "nextCursor": null,
    "hasMore": false
  }
}
```

删除/撤销类命令有结果资源时返回结果；真正无响应体时使用 `204 No Content`。

### 2.4 请求头

| 请求头 | 用途 | 规则 |
| --- | --- | --- |
| Authorization | 身份认证 | 除注册、登录和验证码外必填 |
| X-Request-Id | 请求追踪 | 客户端可传；缺失时服务端生成 |
| Idempotency-Key | 写操作幂等 | 财务、导入确认、成员邀请等命令必填 |
| If-Match | 乐观锁 | 修改资源时传当前版本，例如 `"7"` |
| Accept-Language | 展示语言提示 | 不影响账务数据 |

幂等键作用域固定为：

```text
认证写操作：当前用户 + API 主版本 + OpenAPI operationId + Idempotency-Key
公开 registerUser/resetPassword：匿名主体 + API 主版本 + OpenAPI operationId + Idempotency-Key
```

`operationId` 是接口语义的唯一稳定标识，例如 `postTransaction` 和 `reverseTransaction` 属于不同作用域。公开写接口的匿名主体不是伪造系统用户，也不是所有 `user_id IS NULL` 请求共用的范围；它是 `HMAC-SHA-256(K_idempotency, frame("ZIJI-IDEMPOTENCY-ANONYMOUS-EMAIL-V1", normalizedEmail))` 的 32 字节结果与密钥版本。`normalizedEmail` 使用认证基线的 NFKC、trim、`Locale.ROOT` 小写，`frame` 对每段 UTF-8 字节使用 4 字节大端长度前缀。HMAC 密钥只由外部密钥配置提供，和验证码限流密钥分离；数据库、日志、错误和幂等响应不保存原始邮箱、验证码、密码或 Token。轮换期同时查询当前和上一版本，上一版本至少保留 7 天，且切换前必须排空或围栏旧配置写入实例。

V1 保留公开接口的 `Idempotency-Key`，不以移除请求头规避没有当前用户的问题：注册或密码重置已经提交、但响应在网络中丢失时，客户端必须能以同一请求安全恢复结果，不能重复创建用户、重复撤销会话或只得到不可解释的验证码失效。版本化匿名主体在同请求 Hash 命中前不重放结果，并配合既有统一认证响应，避免由幂等机制枚举邮箱。

`requestHash` 固定为以下长度前缀帧的 SHA-256，输出为 64 位小写十六进制。API 主版本和 operationId 已在作用域中，故不重复放入 Hash：

```text
frame("ZIJI-IDEMPOTENCY-REQUEST-V1")
+ frame(HTTP method upper-case)
+ frame(normalized media type)
+ frame(canonical actual resource identifier)
+ frame(canonical typed business payload)
+ frame(canonical If-Match precondition or explicit absent marker)
```

实际资源标识使用路由完成一次解码和类型校验后的路径变量重建，并包含改变写入语义的 query 参数；UUID 小写、日期为 ISO `YYYY-MM-DD`、时间统一为 UTC RFC 3339，歧义的编码斜杠、路径穿越和重复语义 query 参数必须在路由校验阶段拒绝。JSON 对象键按 UTF-8 字节字典序递归排序，数组保持原序，字段缺失与 `null` 分别编码。Decimal 仅由类型化 DTO/业务载荷以无指数、无多余尾零的十进制字符串规范化，普通字符串（包括账号、编号、文本）的内容和前导零不得猜测或改写。`If-Match` 属于业务前置条件并进入 Hash；Authorization、X-Request-Id、Accept-Language、CSRF 传输值和 Idempotency-Key 本身不进入 Hash。二进制或 multipart 请求把已验证的非文件字段、每个业务文件分片的规范化媒体类型、字节长度和 SHA-256 放入载荷帧，数组式文件分片保留顺序。

相同作用域、相同 Key、相同 requestHash 返回首次响应；相同作用域和 Key 但 requestHash 不同，返回 `409 IDEMPOTENCY_KEY_REUSED`。业务结果和幂等记录在同一数据库事务提交。

请求仍在处理时，数据库唯一索引/行锁最多等待 5 秒；若取得终态则安全重放，否则返回 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` 和 `Retry-After: 5`，不得应用层轮询或并行执行第二次业务写入。`PROCESSING` 的 30 秒租约仅用于异常遗留行恢复，正常路径不能单独提交它；`FAILED_RETRYABLE` 在首次 5xx 后 5 秒才可由同 Key/Hash 串行接管，`FAILED_FINAL` 重放首次稳定 4xx。请求格式/头校验、未认证、CSRF、权限/不可见资源和限流不创建幂等记录；同 Key 异 Hash和处理中响应也不覆盖原记录。

V1 客户端最大重试窗口为 24 小时，幂等记录最短保留 7 天。`expires_at` 是最早清理候选时间和重放保护下限，不是业务资源失效时间；到期且未被交易或同步操作引用的终态记录才可删除。清理后相同 Key 视为新请求，客户端不得依赖旧响应重放。

### 2.5 乐观锁

可修改资源响应包含 `version`，并返回：

```text
ETag: "7"
```

更新时必须携带 `If-Match: "7"`。版本不一致返回 `409 VERSION_CONFLICT`，响应通过有界的版本冲突字段指向当前可见资源；不得直接携带当前资源对象。
缺失或格式不是双引号包围的正整数时按 `400 VALIDATION_ERROR` 处理；格式正确但版本已过期或不一致时返回 `409 VERSION_CONFLICT`。

`VERSION_CONFLICT` 的 Problem Details 必须包含有界的 `versionConflict`，且只包含以下字段：

- `currentVersion`：当前用户可见资源的整数版本，最小为 `1`。
- `currentEtag`：双引号包围的当前版本，例如 `"7"`。
- `resourceLocation`：以单个 `/` 开头的相对 API 地址，禁止以 `//` 开头。

服务端不得在冲突响应中直接嵌入 `currentResource` 或任意当前资源对象。客户端应使用 `resourceLocation` 重新 GET 当前可见资源后再决定是否重试。资源不存在或对当前用户不可见时仍按 `404 RESOURCE_NOT_FOUND` 返回，不得通过 `versionConflict` 泄漏资源存在性。其他 `409`（幂等、唯一约束或业务冲突）不得携带 `versionConflict`。

### 2.6 分页和排序

- 列表使用不透明游标，不使用大偏移分页
- `limit` 默认 50，最大 200
- 默认排序必须稳定，并包含 ID 作为最终排序键
- 客户端不得解析游标内容

示例：

```text
GET /transactions?accountId=...&limit=50&cursor=opaque
```

### 2.7 空值和字段兼容

- 缺失字段表示未提供
- `null` 只用于契约明确允许清空的字段
- 新增响应字段属于向后兼容变化，客户端必须忽略未知字段
- 删除或改变字段语义属于破坏性变化，需要新 API 版本
- 枚举新增可能影响客户端，必须通过契约版本和兼容策略发布

## 3. 错误契约

### 3.1 Problem Details

错误采用 RFC 9457 风格：

```json
{
  "type": "https://ziji.app/problems/version-conflict",
  "title": "资源版本冲突",
  "status": 409,
  "code": "VERSION_CONFLICT",
  "detail": "账户已经被其他设备修改",
  "instance": "/api/v1/accounts/0191...",
  "requestId": "req_01",
  "errors": [
    {
      "field": "version",
      "code": "STALE_VERSION",
      "message": "提交版本为 3，当前版本为 4"
    }
  ],
  "versionConflict": {
    "currentVersion": 4,
    "currentEtag": "\"4\"",
    "resourceLocation": "/api/v1/accounts/0191..."
  }
}
```

生产环境 `detail` 不包含堆栈、SQL、Token、文件绝对路径或第三方密钥。

### 3.2 通用错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 字段或业务输入无效 |
| 400 | UNSUPPORTED_CURRENCY | 不支持的币种 |
| 400 | UNBALANCED_TRANSACTION | 分录不平衡 |
| 401 | AUTHENTICATION_REQUIRED | 未认证或访问 Token 失效 |
| 401 | INVALID_CREDENTIALS | 邮箱或密码错误，响应不泄露账号是否存在 |
| 403 | PERMISSION_DENIED | 已认证但无资源操作权限 |
| 404 | RESOURCE_NOT_FOUND | 资源不存在或用户不可见 |
| 409 | VERSION_CONFLICT | 乐观锁冲突；携带 `versionConflict` 三字段，资源不可见时按 404 返回 |
| 409 | IDEMPOTENCY_KEY_REUSED | 相同幂等键载荷不同 |
| 409 | IDEMPOTENCY_REQUEST_IN_PROGRESS | 相同幂等请求仍在处理中；返回 Retry-After: 5 |
| 409 | DUPLICATE_RESOURCE | 唯一资源重复 |
| 409 | TRANSACTION_HAS_DEPENDENCIES | 修改/撤销存在依赖 |
| 409 | LAST_OWNER_REQUIRED | 操作会导致账户没有 OWNER |
| 413 | FILE_TOO_LARGE | 导入文件超过限制 |
| 415 | UNSUPPORTED_FILE_TYPE | 不支持的文件类型 |
| 422 | BUSINESS_RULE_VIOLATION | 格式正确但违反领域规则 |
| 422 | INSUFFICIENT_POSITION | 卖出数量超过持仓 |
| 429 | RATE_LIMITED | 触发限流，返回 Retry-After |
| 502 | EXTERNAL_PROVIDER_ERROR | 外部服务失败 |
| 503 | TEMPORARILY_UNAVAILABLE | 暂时不可用，可重试 |

不可见资源统一返回 404，避免通过 403 枚举其他用户的资源。

## 4. 资源与接口

### 4.1 注册、认证和会话

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| POST | `/auth/registration-challenges` | 发送注册邮箱验证码 | AUTH-001 |
| POST | `/auth/register` | 验证邮箱验证码并创建用户 | AUTH-001 |
| POST | `/auth/web/sessions` | Web 邮箱密码登录；凭据认证通过后由会话用例设置刷新 Cookie | AUTH-002 |
| POST | `/auth/web/sessions/refresh` | Web 通过刷新 Cookie 轮换会话 | AUTH-002、SEC-001 |
| POST | `/auth/mobile/sessions` | Mobile 邮箱密码登录；凭据认证通过后由会话用例在响应体返回刷新 Token | AUTH-002 |
| POST | `/auth/mobile/sessions/refresh` | Mobile 通过请求体刷新 Token 轮换会话 | AUTH-002、SEC-001 |
| DELETE | `/auth/sessions/current` | 退出当前设备 | AUTH-004 |
| POST | `/auth/password-reset-challenges` | 发送密码重置验证码 | AUTH-003 |
| POST | `/auth/password-reset` | 验证验证码并重置密码 | AUTH-003 |
| GET | `/users/me/sessions` | 查询当前用户设备会话 | AUTH-004 |
| DELETE | `/users/me/sessions/{sessionId}` | 撤销指定设备 | AUTH-004 |
| DELETE | `/users/me/sessions` | 撤销全部设备 | AUTH-004 |

注册请求：

```json
{
  "email": "user@example.com",
  "verificationCode": "123456",
  "password": "user supplied secret",
  "nickname": "小资",
  "timezone": "Asia/Shanghai",
  "baseCurrency": "CNY",
  "locale": "zh-CN"
}
```

密码不写日志或审计 metadata。登录失败的 `LOCKED`、`CLOSED`、邮箱不存在、密码错误、损坏或不支持的 Hash 均返回 `401 INVALID_CREDENTIALS`，不得区分账号存在、状态、密码版本或内部错误。`ACTIVE`、`CLOSING` 可以完成凭据认证；`CLOSING` 下普通业务写入仍由后续用例单独限制。

两个登录端点对所有语法合法请求在 PostgreSQL 固定窗口内计数：先 IP `10m/30`、`24h/300`，再 EMAIL `15m/10`、`24h/50`；成功、失败、状态不允许和已超限请求都计数。任一窗口超限返回 `429 RATE_LIMITED`，`Retry-After` 为所有超限窗口的最长剩余秒数。登录限流不使用 `deviceId`；`deviceName/deviceId` 是后续会话建立的输入，不能改变登录限流主体。BE-AUTH-003 只负责认证、限流和统一失败，稳定会话、Token、Cookie 与 Mobile 刷新凭据分别由 BE-AUTH-004/007 负责。

`POST /auth/register` 与 `POST /auth/password-reset` 虽然未认证，仍必须携带 `Idempotency-Key`；它们使用 §2.4 的版本化匿名主体，而不是要求不存在的当前用户。两个端点都可能返回 `409 IDEMPOTENCY_KEY_REUSED` 或 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`，后者带 `Retry-After: 5`；响应不得借幂等状态泄露邮箱是否存在。

发送注册或密码重置验证码请求使用同一 `EmailChallengeRequest`：`email` 必填，`deviceId` 可选，长度 1～200。`deviceId` 只是防滥用信号，不是可信身份凭据；缺失时服务端按来源 IP 和 `MISSING_DEVICE` 域标记计算设备限流摘要。来源 IP 默认取连接对端；只有受信反向代理已显式配置并覆盖客户端转发头时才接受代理地址，客户端自行传入的 `Forwarded`/`X-Forwarded-For` 不得改变限流主体。语法合法请求不因邮箱是否存在而返回不同结果。

稳定设备会话、刷新 Token 与 Access Token 的生命周期固定如下：每次成功登录创建新的 `sessionId`；同一用户使用相同非空 `deviceId` 登录时，同一事务撤销旧活动会话和刷新 Token（`REPLACED_BY_LOGIN`）后创建新会话，缺失 `deviceId` 时创建独立会话。`deviceName` 经 NFKC、trim 后为 1～100 字符；`deviceId` 是保留客户端原值的不透明稳定标识，不做 NFKC/trim，非空时为 1～200 字符且不得全空白。会话和刷新 Token 以首次签发起固定 30 天绝对到期，正常轮换不改变 `sessionId` 或延长到期；`lastSeenAt` 仅在创建和成功刷新时更新。新刷新 Token 固定 `createdAt = issuedAt < expiresAt`，格式为 `rt1_` 加 32 字节 SecureRandom 的无填充 Base64URL，原文只交付客户端一次，服务端仅保存 `v1:` 加域分离 SHA-256 的 64 位小写十六进制摘要。

Access Token 固定为至少 2048 位 RSA 签发的 RS256 JWT，header 必须为 `alg=RS256`、`typ=at+jwt` 和受控 `kid`，claims 必须包含 `iss=ziji-backend`、`aud=ziji-api`、`sub`、`sid`、`jti`、`iat`、`nbf`、`exp`。其有效期最长 30 分钟，允许 60 秒时钟偏差且不得超过稳定会话到期；验证只接受当前或上一受信公钥和明确 `kid`，拒绝未知 `kid`、其他算法、错误 typ/issuer/audience 或非法时间。私钥、公钥、Access Token 和原始刷新 Token 不得进入数据库、日志、异常、审计 metadata、outbox 或幂等记录；当前私钥签发，当前/上一公钥验证，上一公钥停止签发后至少保留 24 小时。

移动端登录成功：

```json
{
  "data": {
    "session": {
      "id": "0191...",
      "deviceName": "iPhone",
      "deviceId": "opaque-device-id",
      "createdAt": "2026-08-12T08:30:00Z",
      "lastSeenAt": "2026-08-12T08:30:00Z",
      "status": "ACTIVE"
    },
    "tokens": {
      "accessToken": "short-lived-rs256-jwt",
      "refreshToken": "rt1_43Base64UrlCharactersFor32RandomBytes",
      "expiresIn": 1800
    }
  },
  "meta": { "requestId": "req_01" }
}
```

Web 登录/刷新响应不在 JSON 中返回 refreshToken，而是设置 `Secure`、`HttpOnly`、`SameSite` Cookie；所有 Web 状态变更请求同时校验 CSRF Token。移动端在响应体返回刷新 Token并存入系统安全存储。两套 operationId 分离，底层复用同一认证服务。刷新 Token 正常轮换必须在同一数据库事务锁定当前 Token、消费旧 Token、插入新 Token、设置 `replacedById` 并更新 `lastSeenAt`；失败整体回滚且并发最多一个成功。已消费 Token 保留可识别状态，后续重用攻击撤销整个设备会话。

### 4.2 当前用户

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/users/me` | 查询用户资料和设置 | AUTH-006 |
| PATCH | `/users/me` | 修改昵称、时区、基准币种、语言和金额格式 | AUTH-006 |
| POST | `/users/me/password-change` | 使用当前密码修改密码 | AUTH-003 |

V1 用户状态 `User.status` 固定为 `ACTIVE`、`LOCKED`、`CLOSING`、`CLOSED`。其中 `LOCKED` 表示认证安全锁定，不表示账户归档或注销完成。

修改用户使用 `If-Match`。修改时区或基准币种不能改写历史原币账务。

修改时区不重新划分已入账交易的 `businessDate`。修改基准币种后，历史统计默认按新的当前基准币种展示，但每个历史日期仍使用对应日期的历史汇率。

### 4.3 账户

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/accounts` | 查询当前用户可见账户 | ACC-001、SHR-002 |
| POST | `/accounts` | 创建账户，可同时录入期初余额 | ACC-001～003 |
| GET | `/accounts/{accountId}` | 查询账户详情和当前余额 | ACC-002、004 |
| PATCH | `/accounts/{accountId}` | OWNER 修改账户资料 | ACC-002、SHR-002 |
| POST | `/accounts/{accountId}/archive` | 归档账户 | ACC-006、007 |
| GET | `/accounts/{accountId}/balance` | 查询指定时点余额 | ACC-004 |
| GET | `/accounts/{accountId}/balance-history` | 查询历史余额 | DASH-006、007 |
| GET | `/accounts/{accountId}/liquidity-holds` | 查询冻结、在途和预留记录 | ACC-009 |
| POST | `/accounts/{accountId}/liquidity-holds` | 新增流动性占用记录 | ACC-009 |
| POST | `/accounts/{accountId}/liquidity-holds/{holdId}/revisions` | 关闭旧占用并创建修正版 | ACC-009 |
| POST | `/accounts/{accountId}/liquidity-holds/{holdId}/release` | 释放流动性占用 | ACC-009 |

创建账户：

```json
{
  "accountClass": "ASSET",
  "accountType": "BANK",
  "name": "工资卡",
  "institution": "示例银行",
  "currency": "CNY",
  "note": null,
  "openingBalance": {
    "amount": "10000.00",
    "businessAt": "2026-08-12T08:00:00+08:00",
    "note": "首次录入"
  }
}
```

创建成功返回账户以及 `openingTransactionId`。没有期初余额时该字段为 null。

创建账户时服务端原子创建账户、创建者的 ACTIVE OWNER membership、`included=true/ratio=1.000000` 计入设置和所需账务科目。所有账户都通过 ACTIVE AccountMember 授权，创建者字段不构成权限捷径。

余额响应：

```json
{
  "data": {
    "accountId": "0191...",
    "currency": "CNY",
    "ledgerBalance": "10000.00",
    "availableBalance": "9800.00",
    "unavailableAmount": "200.00",
    "unavailableBreakdown": {
      "frozen": "200.00",
      "inTransit": "0.00",
      "reserved": "0.00"
    },
    "liquidityStatus": "NORMAL",
    "asOf": "2026-08-12T08:30:00Z",
    "asOfSequence": 1250
  },
  "meta": { "requestId": "req_01" }
}
```

`availableBalance` 不是客户端可写字段：

```text
availableBalance = ledgerBalance - unavailableAmount
```

没有有效流动性占用时 `unavailableAmount=0`。若占用金额超过账面余额，返回负数 availableBalance 和 `liquidityStatus=HOLDS_EXCEED_BALANCE`，不得静默截断为 0。

修改已生效的 LiquidityHold 时必须携带 `If-Match`，服务端在同一事务关闭旧记录并创建修正版；释放只设置 `releasedAt`。新增、修正和释放都不产生 LedgerEntry，并保留操作者、时间、来源及版本关系。

### 4.4 交易与流水

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/transactions` | 按账户、类型、日期、分类查询流水 | LED-* |
| POST | `/transactions` | 创建并确认交易 | LED-001～008、LIA-* |
| GET | `/transactions/{transactionId}` | 查询交易、分录和版本关系 | LED-001、011 |
| POST | `/transactions/{transactionId}/revisions` | 冲正旧版本并创建新版本 | LED-009 |
| POST | `/transactions/{transactionId}/reversal` | 作废并冲正交易 | LED-010 |
| POST | `/accounts/{accountId}/balance-adjustments` | 创建余额调整 | ACC-005 |

创建交易使用判别联合体：

```json
{
  "type": "EXPENSE",
  "businessAt": "2026-08-12T12:30:00+08:00",
  "currency": "CNY",
  "amount": "50.00",
  "accountId": "0191...",
  "categoryId": "0192...",
  "merchant": "示例餐厅",
  "tagIds": [],
  "note": null
}
```

转账：

```json
{
  "type": "TRANSFER",
  "businessAt": "2026-08-12T13:00:00+08:00",
  "fromAccountId": "0191...",
  "toAccountId": "0192...",
  "fromAmount": { "amount": "1000.00", "currency": "CNY" },
  "toAmount": { "amount": "1000.00", "currency": "CNY" },
  "fee": { "amount": "0.00", "currency": "CNY" },
  "exchangeRate": null,
  "note": null
}
```

信用卡还款：

```json
{
  "type": "LIABILITY_REPAYMENT",
  "businessAt": "2026-08-12T14:00:00+08:00",
  "cashAccountId": "0191...",
  "liabilityAccountId": "0192...",
  "currency": "CNY",
  "principalAmount": "1000.00",
  "interestAmount": "50.00",
  "feeAmount": "0.00",
  "interestCategoryId": "0193..."
}
```

服务端根据类型生成 LedgerEntry。普通客户端不得直接提交任意 ledger account 和借贷分录；管理修复工具需要独立的高权限契约和审计，不属于普通 V1 API。

### 4.5 分类和标签

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/categories` | 查询默认和用户/账户分类 | CAT-001 |
| POST | `/categories` | 新建分类 | CAT-001、002 |
| PATCH | `/categories/{categoryId}` | 修改或停用分类 | CAT-002、003 |
| POST | `/categories/{categoryId}/merge` | 合并到另一分类 | CAT-003 |
| GET | `/tags` | 查询标签 | CAT-004 |
| POST | `/tags` | 新建标签 | CAT-004 |
| PATCH | `/tags/{tagId}` | 修改或停用标签 | CAT-004 |

分类最大两级由服务端校验。合并命令需要 `targetCategoryId`，不物理更新历史流水的原分类证据。

### 4.6 共享账户

| 方法 | 路径 | OWNER | EDITOR | VIEWER | 需求 |
| --- | --- | --- | --- | --- | --- |
| GET `/accounts/{id}/members` | 查询成员 | 是 | 是 | 是 | SHR-002 |
| POST `/accounts/{id}/invitations` | 邀请成员 | 是 | 否 | 否 | SHR-001 |
| DELETE `/accounts/{id}/invitations/{inviteId}` | 撤销邀请 | 是 | 否 | 否 | SHR-001 |
| POST `/account-invitations/{token}/accept` | 接受邀请 | 被邀请人 | - | - | SHR-001 |
| POST `/account-invitations/{token}/reject` | 拒绝邀请 | 被邀请人 | - | - | SHR-001 |
| PATCH `/accounts/{id}/members/{memberId}` | 修改角色 | 是 | 否 | 否 | SHR-002、003 |
| DELETE `/accounts/{id}/members/{memberId}` | 移除成员 | 是 | 否 | 否 | SHR-004 |
| POST `/accounts/{id}/ownership-transfer` | 转让 OWNER | 是 | 否 | 否 | SHR-003 |
| POST `/accounts/{id}/leave` | 主动退出 | 是* | 是 | 是 | SHR-003 |
| GET `/accounts/{id}/inclusion-settings/me` | 查询自己的计入设置 | 是 | 是 | 是 | SHR-005～007 |
| PUT `/accounts/{id}/inclusion-settings/me` | 创建新生效设置 | 是 | 是 | 是 | SHR-005～007 |

`*` 唯一 OWNER 退出前必须转让所有权，否则返回 `LAST_OWNER_REQUIRED`。

计入设置请求：

```json
{
  "included": true,
  "ratio": "0.700000",
  "validFrom": "2026-08-13T00:00:00+08:00"
}
```

服务端禁止 `validFrom` 早于当前允许的业务时点，以避免通过普通接口回改历史。

### 4.7 账单导入

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| POST | `/import-batches` | 以 multipart/form-data 流式上传文件并创建批次 | IMP-001、007 |
| POST | `/import-batches/{batchId}/parse` | 开始解析 | IMP-002 |
| GET | `/import-batches/{batchId}` | 查询批次状态和统计 | IMP-002、003 |
| GET | `/import-batches/{batchId}/rows` | 分页查询解析行和重复建议 | IMP-003～005 |
| PUT | `/import-batches/{batchId}/mapping` | 保存字段映射并重新解析 | IMP-002、003 |
| PATCH | `/import-batches/{batchId}/rows/{rowId}` | 设置修正值或处理决定 | IMP-003、005 |
| POST | `/import-batches/{batchId}/confirm` | 确认导入 | IMP-004、005 |
| POST | `/import-batches/{batchId}/reversal` | 整批撤销 | IMP-006 |

V1 只在账单导入使用文件上传。客户端以 `multipart/form-data` 将微信、支付宝、银行 CSV/Excel 或通用 CSV 文件流式传给后端；后端限制大小、校验声明类型和文件签名、计算 SHA-256 并完成安全检查后写入私有对象存储。数据导出属于下载，不使用此上传接口；V1 不提供头像、票据图片或普通附件上传。

解析、确认和撤销可能异步执行，返回 `202 Accepted`：

```json
{
  "data": {
    "jobId": "0191...",
    "batchId": "0192...",
    "status": "PARSING"
  },
  "meta": { "requestId": "req_01" }
}
```

客户端通过批次查询接口轮询，不依赖固定处理时间。

### 4.8 金融产品、行情和汇率来源

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/instruments/search` | 搜索已缓存的 Tushare/手工产品 | INV-001～003 |
| POST | `/instruments` | 创建手工金融产品 | INV-005 |
| GET | `/instruments/{instrumentId}` | 查询产品和外部映射 | INV-006 |
| GET | `/instruments/{instrumentId}/prices` | 查询收盘价或净值历史 | INV-003、010 |
| POST | `/instruments/{instrumentId}/manual-prices` | 创建手工价格或净值 | INV-005、010 |
| POST | `/instruments/{instrumentId}/price-corrections` | 修正指定业务日期价格 | INV-010 |
| GET | `/market-data/status` | 查询数据新鲜度和同步状态 | INV-003～005 |

普通客户端不提供“立即拉取全市场”接口。产品搜索未命中时，服务端可以按限流策略触发单产品 Tushare 查询，或提示用户手工创建。

价格响应必须包含：

```json
{
  "instrumentId": "0191...",
  "priceType": "CLOSE",
  "price": "12.340000000000",
  "currency": "CNY",
  "businessDate": "2026-08-12",
  "source": "TUSHARE",
  "revision": 2,
  "sourceUpdatedAt": "2026-08-12T09:00:00Z",
  "fetchedAt": "2026-08-12T09:05:00Z",
  "freshness": "FRESH"
}
```

历史价格或净值修正后，默认查询返回最新 revision；旧 revision 只提供给具有审计权限的复核接口。

### 4.9 投资交易、持仓和收益

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| POST | `/investment-trades` | 创建买入、卖出或分红及其账务交易 | INV-007、012、015、016 |
| GET | `/investment-trades` | 查询投资交易 | INV-007 |
| GET | `/investment-accounts/{accountId}/positions` | 查询当前持仓、成本和估值 | INV-008、009、016 |
| GET | `/investment-accounts/{accountId}/performance` | 查询收益和 XIRR | INV-011、015 |
| GET | `/investments/overview` | 查询用户投资概览 | INV-009～011 |
| GET | `/investment-returns/calendar` | 查询全部投资或单一标的的月度每日收益 | INV-018 |
| GET | `/investment-returns/calendar/{businessDate}/details` | 查询选中日期的收益归因和标的贡献 | INV-018 |

买入请求：

```json
{
  "side": "BUY",
  "investmentAccountId": "0191...",
  "instrumentId": "0193...",
  "quantity": "100.000000000000",
  "unitPrice": "10.000000000000",
  "currency": "CNY",
  "feeAmount": "5.00",
  "taxAmount": "0.00",
  "tradeAt": "2026-08-12T14:30:00+08:00"
}
```

服务端计算并返回 `grossAmount`：

```text
grossAmount = HALF_UP(quantity × unitPrice, currencyMinorUnits)
```

CNY、USD、HKD、EUR 使用 2 位，JPY 使用 0 位。V1 不接受与该结果不同的 `actualGrossAmount`；基金申购费、交易手续费和税费使用独立字段，统一作为投资费用，不混入持仓成本。

买卖入账必须在同一 Transaction 中更新投资账户的 `PRIMARY` 券商现金科目、`POSITION_COST` 内部成本科目以及费用科目。投资成交只使用该投资账户的 `PRIMARY` 现金结算，不接受外部 `settlementAccountId`；银行卡与投资账户之间的入金或出金使用普通转账。持仓与成本接口使用 `POSITION_COST` 对账，但资产汇总只计算 `PRIMARY` 余额和持仓市值，不再次计入 `POSITION_COST`。

持仓响应不得把缺失价格当 0：

```json
{
  "instrumentId": "0193...",
  "quantity": "100.000000000000",
  "costBasis": "1000.00",
  "averageCost": "10.000000000000",
  "valuationStatus": "PRICED",
  "marketPrice": "12.000000000000",
  "marketValue": "1200.00",
  "unrealizedProfit": "200.00",
  "priceAsOf": "2026-08-12"
}
```

`valuationStatus=UNPRICED` 时 `marketPrice`、`marketValue` 和 `unrealizedProfit` 为 null。

收益日历查询参数：

```text
month=2026-07
scopeType=PORTFOLIO | INSTRUMENT
instrumentId=<uuid>   # scopeType=INSTRUMENT 时必填，PORTFOLIO 时禁止
```

服务端同时返回金额和收益率，客户端只负责切换展示，不得自行根据行情重新计算。金额使用用户当前基准币种；月份按用户时区解释。月历响应示例：

```json
{
  "data": {
    "scopeType": "PORTFOLIO",
    "instrumentId": null,
    "baseCurrency": "CNY",
    "month": "2026-07",
    "valuationRevision": 4,
    "asOf": "2026-08-01T01:05:00Z",
    "recalculatedAt": "2026-08-01T01:06:00Z",
    "summaryStatus": "COMPLETE",
    "monthlyProfit": "2840.12",
    "monthlyReturnRate": "0.0182450000",
    "profitDayCount": 12,
    "lossDayCount": 9,
    "zeroDayCount": 1,
    "days": [
      {
        "businessDate": "2026-07-31",
        "status": "CALCULATED",
        "dailyProfit": "849.70",
        "dailyReturnRate": "0.0042100000",
        "missingInstrumentCount": 0
      },
      {
        "businessDate": "2026-07-26",
        "status": "NON_TRADING_DAY",
        "dailyProfit": null,
        "dailyReturnRate": null,
        "missingInstrumentCount": 0
      }
    ],
    "dataQualityWarnings": []
  },
  "meta": { "requestId": "req_01" }
}
```

`monthlyProfit` 是完整 `CALCULATED` 日收益之和；`monthlyReturnRate` 使用每日收益率几何链接。只要月份内任何应计算日期为 `PENDING_DATA`、`PARTIAL` 或 `UNPRICED`，`summaryStatus` 必须相应为 `PENDING`、`PARTIAL` 或 `UNAVAILABLE`，月度金额和收益率返回 null，避免把不完整数据伪装成完整月度结果。

日期状态固定为 `CALCULATED | NON_TRADING_DAY | NO_POSITION | PENDING_DATA | PARTIAL | UNPRICED`。只有 `CALCULATED` 可以返回 `dailyProfit`；真实零收益通过 `status=CALCULATED` 且 `dailyProfit="0.00"` 表达。`NON_TRADING_DAY` 仅用于相关市场均休市且无交易、分红或其他估值事件的日期。

日明细响应包含日初/日终价值、净现金流、收益金额、收益率、价格影响、汇率影响、分红、手续费、税费和贡献明细。`scopeType=PORTFOLIO` 时贡献项为单一标的、券商现金或未归因项；完整计算时贡献金额合计必须等于日收益金额。所有结果返回 `valuationRevision`、数据截至时间、行情日期和数据质量状态。

### 4.10 Dashboard 和统计

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/dashboard` | 当前核心指标、分布、变化、投资和数据质量 | DASH-001～005 |
| GET | `/statistics/assets` | 资产和净资产趋势 | DASH-006、007 |
| GET | `/asset-changes/calendar` | 查询总资产或净资产的月度每日变化 | DASH-010 |
| GET | `/asset-changes/calendar/{businessDate}/details` | 查询选中日期的资产变化明细与归因 | DASH-010 |
| GET | `/statistics/cash-flow` | 收支和净现金流分析 | DASH-003、006 |
| GET | `/statistics/accounts` | 账户余额和占比趋势 | DASH-006、007 |

Dashboard 顶层字段：

```json
{
  "data": {
    "baseCurrency": "CNY",
    "asOf": "2026-08-12T09:05:00Z",
    "asOfSequence": 1250,
    "valuationRevision": 3,
    "recalculatedAt": "2026-08-12T09:06:00Z",
    "projectionStatus": "CURRENT",
    "summary": {
      "totalAssets": "120000.00",
      "availableFunds": "50000.00",
      "investmentAssets": "70000.00",
      "totalLiabilities": "20000.00",
      "netAssets": "100000.00"
    },
    "changeAttribution": {},
    "distribution": [],
    "investmentOverview": {},
    "dataQualityWarnings": []
  },
  "meta": { "requestId": "req_01" }
}
```

存在未估值资产时，不得把其作为 0 静默纳入 `totalAssets`；必须在 `dataQualityWarnings` 返回 `UNPRICED_INSTRUMENTS` 和受影响数量。

`totalAssets` 包含普通资产账户、投资账户 PRIMARY 券商现金和投资持仓市值；`investmentAssets` 包含券商现金和持仓市值；`availableFunds` 只包含普通 ASSET 账户的可用余额。

历史价格、净值或汇率修正会生成新的 valuationRevision，并重算受影响日期、相邻变化归因及月/年汇总。普通查询默认返回最新修订；旧修订仅供审计复核。

总资产日历查询使用 `month=YYYY-MM` 和 `metric=TOTAL_ASSETS | NET_ASSETS`。响应同时返回变化金额和变化率，客户端只负责展示。状态固定为 `CALCULATED | NO_ASSETS | PENDING_DATA | PARTIAL | UNAVAILABLE`；真实零变化使用 `CALCULATED` 和 `changeAmount="0.00"`，其他状态不能伪装成 0。`TOTAL_ASSETS` 没有计入资产时为 `NO_ASSETS`；`NET_ASSETS` 仅在资产和负债均未计入时为空。日初值小于或等于 0 时 `changeRate=null`，但完整计算的变化金额仍可返回。

日期明细返回日初和日终的总资产、负债、净资产，以及每个来源对三项指标的贡献。归因类型至少区分 `INCOME | EXPENSE | BORROWING_PRINCIPAL | REPAYMENT_PRINCIPAL | TRANSFER | MARKET | FX | ADJUSTMENT | INCLUSION | OTHER`。两个均计入统计的账户间转账可以作为解释项显示，但三项变化贡献均为 0。

任一应计算日期为 `PENDING_DATA/PARTIAL/UNAVAILABLE` 时，月度变化及变化率必须为 null。历史交易、价格、净值或汇率修正生成新 `valuationRevision` 并重算受影响日和相邻日；新的计入设置只影响生效时间之后的日期，不回改此前历史。

### 4.11 汇率

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/exchange-rates` | 查询币对当前或历史汇率 | FX-003、004 |
| POST | `/exchange-rates/manual` | 创建手工汇率 | FX-005 |
| POST | `/exchange-rates/{rateId}/corrections` | 创建汇率修正版 | FX-005 |
| GET | `/exchange-rates/status` | 查询数据新鲜度 | FX-004 |

汇率响应必须明确：`baseCurrency`、`quoteCurrency`、`rate`、`businessAt`、`source`、`freshness`、`revision` 和 `fetchedAt`。

历史汇率修正使用新 revision，不覆盖旧值，并触发对应 Dashboard 统计修订。

### 4.12 周期账单

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/recurring-rules` | 查询周期规则 | REC-001 |
| POST | `/recurring-rules` | 创建周期规则 | REC-001 |
| PATCH | `/recurring-rules/{ruleId}` | 修改、暂停或结束规则 | REC-001 |
| GET | `/recurring-occurrences` | 查询待确认发生项 | REC-002 |
| POST | `/recurring-occurrences/{id}/confirm` | 确认并生成正式交易 | REC-002 |
| POST | `/recurring-occurrences/{id}/skip` | 跳过本次 | REC-002 |

确认发生项需要 `Idempotency-Key`，重复确认不能生成重复交易。

### 4.13 数据导出和账号注销

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| POST | `/users/me/exports` | 创建数据导出任务 | SEC-007 |
| GET | `/users/me/exports/{exportId}` | 查询任务并获取短时下载地址 | SEC-007 |
| POST | `/users/me/account-closure` | 发起账号注销申请 | SEC-007 |
| GET | `/users/me/account-closure` | 查询注销状态和影响 | SEC-007 |
| DELETE | `/users/me/account-closure` | 冷静期内撤销申请 | SEC-007 |

发起注销前响应必须列出共享账户、唯一 OWNER 和未完成导出等阻塞项。

## 5. 离线同步契约

### 5.1 拉取增量

```text
GET /sync/changes?cursor=<opaque>&limit=200
```

响应：

```json
{
  "data": [
    {
      "sequence": 1251,
      "entityType": "TRANSACTION",
      "entityId": "0191...",
      "entityVersion": 2,
      "changeType": "UPSERT",
      "payloadVersion": 1,
      "payload": {}
    }
  ],
  "meta": {
    "requestId": "req_01",
    "nextCursor": "opaque-1251",
    "hasMore": false
  }
}
```

`TOMBSTONE` 和 `ACCESS_REVOKED` 可以只返回资源标识及最小处理信息。

变更由服务端按当前用户定向投递。成员被移除后仍可拉取专门发给自己的 `ACCESS_REVOKED`；新成员接受邀请后收到 bootstrap 标记并拉取账户当前可见快照。

### 5.2 上传操作

```text
POST /sync/operations
```

```json
{
  "deviceId": "ios-device-local-id",
  "operations": [
    {
      "operationId": "0191...",
      "idempotencyKey": "0192...",
      "entityType": "TRANSACTION",
      "entityId": "0193...",
      "operationType": "CREATE",
      "baseVersion": null,
      "payloadVersion": 1,
      "payload": {
        "type": "EXPENSE"
      },
      "createdAt": "2026-08-12T08:20:00Z"
    }
  ]
}
```

批量最大 100 个操作，按数组顺序处理。每个操作独立返回结果，但一个 Transaction 及其分录只作为一个操作提交。

```json
{
  "data": {
    "results": [
      {
        "operationId": "0191...",
        "status": "APPLIED",
        "entityId": "0193...",
        "entityVersion": 1,
        "changeSequence": 1252,
        "error": null
      }
    ],
    "serverCursor": "opaque-1252"
  },
  "meta": { "requestId": "req_01" }
}
```

结果状态：

- `APPLIED`：成功应用
- `DUPLICATE`：幂等重复，返回首次结果
- `CONFLICT`：版本冲突，返回当前服务端资源
- `REJECTED`：权限或业务规则拒绝，不自动重试

### 5.3 同步安全

- 同步 payload 使用与普通 API 相同的命令 schema 和校验规则
- 客户端不能通过同步接口提交任意 LedgerEntry
- 每个操作重新校验当前成员权限
- 相同 idempotencyKey 不同 payload 返回 REJECTED
- 服务端返回的冲突资源只包含用户仍有权限查看的字段
- `SyncOperation.idempotencyKey` 使用 §2.4 的认证主体、API 主版本和实际 OpenAPI `applySyncOperations`；客户端 `SyncOperation.operationId`、操作类型、实际 entityId 和规范化 payload 进入 requestHash，不另造派生 operationId；不同 entityId 即使复用 Key 也因 requestHash 不同稳定拒绝

## 6. 异步任务契约

导入、导出和大规模重建使用统一任务状态：

```text
QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED
```

任务资源至少包含：

```json
{
  "id": "0191...",
  "type": "IMPORT_PARSE",
  "status": "RUNNING",
  "progress": {
    "completed": 500,
    "total": 10000
  },
  "error": null,
  "createdAt": "2026-08-12T08:00:00Z",
  "updatedAt": "2026-08-12T08:01:00Z"
}
```

失败返回稳定 `error.code`，不向客户端暴露内部异常。重试是否允许由任务类型决定。

## 7. 限流

至少按下列维度配置：

| 接口 | 限流维度 |
| --- | --- |
| 发送邮箱验证码 | IP + 邮箱 + 设备 |
| 登录和密码重置 | IP + 邮箱 |
| 普通 API | 用户 + 会话 |
| 文件导入 | 用户 + 并发任务数 + 文件大小 |
| 手工行情/汇率 | 用户 + 产品/币对 |
| Tushare 调用 | 服务端供应商账户 + 接口权限 |

发送邮箱验证码的固定窗口配额按 `purpose=REGISTER` 与 `purpose=RESET_PASSWORD` 分开计算：

| 维度 | 窗口与配额 |
| --- | --- |
| 邮箱 | 60 秒 1 次、1 小时 5 次、24 小时 10 次 |
| 来源 IP | 10 分钟 20 次、24 小时 100 次 |
| 设备 | 1 小时 10 次、24 小时 30 次 |

任一窗口超限返回 `429 RATE_LIMITED`；`Retry-After` 使用所有超限窗口中的最长剩余时间。被拒绝请求仍提交所有相关桶的计数。认证限流由 PostgreSQL 作为跨实例权威，主体使用按用途、维度和规范化主体域分离的 HMAC-SHA-256 摘要；限流桶不保存原始 IP、邮箱或 `deviceId`。当前和上一版本密钥在轮换期间同时识别，上一版本至少保留 48 小时。

验证码有效期 10 分钟，单个挑战最多错误 5 次。新挑战与邮件投递事件成功写入后，才将同邮箱同用途旧活动挑战标记为 `REPLACED`；邮件投递失败不退还配额，outbox 重试同一挑战，不重新生成验证码。验证码如进入 outbox 载荷必须使用应用层信封加密。认证响应不得泄露邮箱存在性；窗口桶在窗口结束或 `blocked_until` 后保留 7 天再清理。

## 8. OpenAPI 实施规则

机器契约必须：

- 为每个操作定义稳定 `operationId`
- 为所有请求和响应定义 schema，禁止无边界 object
- Decimal 统一为 `type: string` + 十进制正则
- UUID、date、date-time 使用标准 format
- 枚举明确列出并说明兼容策略
- 声明所有响应码和 Problem schema
- 用 security scheme 标记公开和认证接口
- 用 header parameter 声明 Idempotency-Key 和 If-Match
- 为需要幂等的写操作声明稳定且不可复用的 operationId，作为数据库幂等作用域的一部分
- 通过 OpenAPI lint 和破坏性变更检查

### 8.1 财务精度

- 金额输入最多允许对应币种最小单位：CNY、USD、HKD、EUR 2 位，JPY 0 位
- 超出精度默认返回 `VALIDATION_ERROR`；只有契约明确提供确认舍入字段的命令才可使用 HALF_UP 入账
- 数量、价格和汇率最多 12 位小数，比例和收益率最多 6 位
- 估值、汇率折算和统计使用完整允许精度聚合，最终输出时按展示币种舍入一次
- API 金额字符串固定使用币种最小位数；数量、价格和汇率可去除不必要尾零，但不得改变数值

推荐 operationId 命名：

```text
createAccount
postTransaction
reverseTransaction
listSyncChanges
applySyncOperations
```

## 9. 契约验收门禁

1. RTM 中每个需要 API 的 V1 需求至少关联一个 operationId。
2. 所有财务写接口明确幂等和乐观锁规则。
3. 所有金额、价格、数量和汇率均为十进制字符串。
4. 所有资源接口执行对象级权限测试。
5. OpenAPI 示例可通过 schema 校验。
6. Web 和移动端客户端由 OpenAPI 生成或接受同一份契约校验。
7. 服务端运行时契约测试与 `openapi/ziji-v1.yaml` 一致。

## 10. 已冻结的实施决策

1. Web 使用 HttpOnly Cookie + CSRF，Mobile 使用响应体刷新 Token + 系统安全存储，operationId 分离。
2. 时区修改不改变既有业务日期；历史报表默认按当前基准币种和各日历史汇率展示。
3. V1 仅账单导入需要上传，并采用后端流式代理。
4. 汇率接口和存储保持供应商中立，手工汇率始终可用；自动供应商在批次四前确定，不阻塞工程启动。
