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

LiquidityHold 的四个公开 operation 继续复用上述统一幂等服务。创建、修订和释放的 request Hash 必须包含路由实际解析后的 `accountId`，修订/释放还必须包含实际 `holdId`；请求体按类型化载荷规范化，修订和释放把 `If-Match` 放入 Hash，创建使用显式的 If-Match 缺失标记。格式校验、未认证、权限失败和资源不可见在幂等服务之前结束，不创建幂等记录。

LiquidityHold 的 `status` 由查询时点 `asOf` 从 `effectiveAt`、`expiresAt` 和终止事实推导，不是幂等安全引用中的持久化字段；若重放时已跨过尚未物化版本的生效或过期边界，服务端无法精确重建首次响应，必须返回 `500 INTERNAL_ERROR`，不得以当前状态伪装重放或重复执行业务写入。

创建和修订请求的公共机器形状固定为 `type`、`amount`、`currency`、`effectiveAt`、`expiresAt`、`reason`：`amount` 是 `PositiveMoney` 十进制字符串，`currency` 是顶层 `Currency` 字段，二者独立提交；`expiresAt` 可为 `null`，其余必填字段按 OpenAPI 的类型和长度约束校验。嵌套 `amount` 对象、缺失或非字符串 `currency`、未知币种和额外字段均返回 `400 VALIDATION_ERROR`；格式正确但与账户事实币种不一致返回 `422 BUSINESS_RULE_VIOLATION`。服务端只以账户事实和 application 层校验结果决定写入币种，不直接信任客户端值。

公共人工 API 不接收 `source`。服务端为这三个写 operation 固定写入 `MANUAL`，客户端不能伪造 `IMPORT` 或 `SYSTEM`；后两者只保留给未来受控的内部导入或系统任务。API 的 `reason` 逐字映射数据库 `liquidity_holds.note`。修订没有独立持久化的 `revisionReason` 字段，因此公共修订载荷不接收该字段；修订理由使用新版本的 `reason`/`note`，并由追加式审计 action `LIQUIDITY_HOLD_REVISED` 表示修订行为，禁止接收后静默丢弃。

### 2.5 乐观锁

可修改资源响应包含 `version`，并返回：

```text
ETag: "7"
```

更新时必须携带强 `If-Match: "7"`。其唯一合法形态是双引号包围的正整数；缺失、重复 header、弱 ETag（`W/`）、`*`、未加双引号、零、负数、非数字或溢出（超出服务端整数范围）均按 `400 VALIDATION_ERROR` 处理。格式正确但版本已过期或不一致时返回 `409 VERSION_CONFLICT`。修订和释放都必须执行这一校验，不能用最后写入覆盖冲突。

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

`GET /accounts/{accountId}/liquidity-holds` 返回完整修订历史，而不是每条根链只返回当前版本；结果包含 `PENDING`、`ACTIVE`、`RELEASED`、`SUPERSEDED` 和 `EXPIRED`。固定排序为 `created_at DESC, id DESC`。cursor 是服务端生成并认证的不透明 keyset 游标，绑定实际 `accountId`、全部过滤条件、排序定义和 API 主版本；篡改、边界错误、排序不匹配或条件不匹配均返回 `400 VALIDATION_ERROR`，错误内容不得包含 SQL、表名或资源内部信息。

流水列表 `GET /transactions` 固定使用以下语义：

- 查询参数为 `accountId`、`type`、`dateFrom`、`dateTo`、`categoryId`、`limit` 和 `cursor`；除 `limit`/`cursor` 外均为可选筛选。
- `type` 只能取 V1 `TransactionType` 已冻结值：`OPENING`、`INCOME`、`EXPENSE`、`REFUND`、`TRANSFER`、`FX_TRANSFER`、`ADJUSTMENT`、`INVESTMENT`、`REPAYMENT`、`INTEREST`、`REVERSAL`。`categoryId` 只能引用 `categories.id`，其分类类型仍以既有 `INCOME`/`EXPENSE` 事实为准，不新增第二套分类枚举。
- `dateFrom` 与 `dateTo` 均为用户提交的 ISO 日期，并且边界包含在内；缺失表示不限制该侧边界，`dateFrom > dateTo` 返回 `400 VALIDATION_ERROR`。
- `limit` 默认 `50`，合法范围为 `1～200`；非法、重复或超范围值返回 `400 VALIDATION_ERROR`。
- 结果固定按 `businessDate DESC, transactionId DESC` 排序。该键以 `ledger_entries(ledger_account_id, business_date, transaction_id)` 支持带 `accountId` 的 keyset 查询；不带 `accountId` 时先限定当前用户可见账户，再以同一排序键合并结果，不改变契约语义。
- `cursor` 是服务端生成的不透明 keyset 游标，绑定当前认证 `userId`、API 主版本、`listTransactions`、全部筛选条件和排序定义；客户端不得解析或修改。篡改、跨用户复用、与筛选/排序不匹配、非法边界或无法验证的游标均返回 `400 VALIDATION_ERROR`，且不得回显游标内容。

流水可见性固定为当前用户在账户上的 `ACTIVE` membership；`LEFT`、`REMOVED`、已结束 membership 和无关用户均不可见。显式指定不可见 `accountId` 时列表返回 `404 RESOURCE_NOT_FOUND`；不指定账户时只返回当前用户可见事实，不泄漏其他账户。`GET /transactions/{transactionId}` 对不可见交易同样统一返回 `404 RESOURCE_NOT_FOUND`。已认证但被对象策略拒绝时返回 `403 PERMISSION_DENIED`；未认证返回 `401 AUTHENTICATION_REQUIRED`。列表的非法 UUID、日期、类型、分类、limit 或 cursor，以及详情的非法 `transactionId`，均返回 `400 VALIDATION_ERROR`；成功分别返回 `200` 的 `TransactionEnvelope` 或 `TransactionListEnvelope`，错误体均为 `Problem`。交易详情响应包含资源版本和强 ETag；列表响应使用交易专用 `TransactionPageMeta`（不改变其他列表的全局 `PageMeta`），不承诺资源 ETag。

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
| 500 | INTERNAL_ERROR | 无法安全重放的历史幂等结果或未预期内部失败；不得重新执行业务，也不泄漏 SQL、堆栈或敏感输入 |
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

注册成功响应返回安全 `UserEnvelope`、`ETag` 和 `Location: /api/v1/users/me`；同 Key 同 Hash 重放从安全资源引用重建同一版本的公开资料，无法精确重建时按 `500 INTERNAL_ERROR` fail closed。

密码不写日志或审计 metadata。登录失败的 `LOCKED`、`CLOSED`、邮箱不存在、密码错误、损坏或不支持的 Hash 均返回 `401 INVALID_CREDENTIALS`，不得区分账号存在、状态、密码版本或内部错误。`ACTIVE`、`CLOSING` 可以完成凭据认证；`CLOSING` 下普通业务写入仍由后续用例单独限制。

两个登录端点对所有语法合法请求在 PostgreSQL 固定窗口内计数：先 IP `10m/30`、`24h/300`，再 EMAIL `15m/10`、`24h/50`；成功、失败、状态不允许和已超限请求都计数。任一窗口超限返回 `429 RATE_LIMITED`，`Retry-After` 为所有超限窗口的最长剩余秒数。登录限流不使用 `deviceId`；`deviceName/deviceId` 是后续会话建立的输入，不能改变登录限流主体。BE-AUTH-003 只负责认证、限流和统一失败，稳定会话、Token、Cookie 与 Mobile 刷新凭据分别由 BE-AUTH-004/007 负责。

`POST /auth/register` 与 `POST /auth/password-reset` 虽然未认证，仍必须携带 `Idempotency-Key`；它们使用 §2.4 的版本化匿名主体，而不是要求不存在的当前用户。两个端点都可能返回 `409 IDEMPOTENCY_KEY_REUSED` 或 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`，后者带 `Retry-After: 5`；响应不得借幂等状态泄露邮箱是否存在。

若同 Key 的历史幂等记录无法提供 V009/V016 允许的安全重放引用，服务端必须 fail closed 返回 `500 INTERNAL_ERROR`，不得伪造成功、暴露内部状态或重新执行业务写入。`VERSION_CONFLICT` 不能作为普通 Problem 错误码；其同 Key/Hash 重放只返回首次已保存的 `currentVersion`、由版本派生的 `currentEtag` 与相对 `resourceLocation`，不得查询当前资源重新生成摘要。

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

Web 登录/刷新响应不在 JSON 中返回 refreshToken，而是同时设置以下 host-only Cookie（不设置 `Domain`）：`ziji_refresh` 固定为 `Secure`、`HttpOnly`、`SameSite=Strict`、`Path=/api/v1`；`ziji_csrf` 固定为 `Secure`、非 `HttpOnly`、`SameSite=Strict`、`Path=/api/v1`。CSRF Header 固定为 `X-CSRF-Token`，不得使用 Spring 默认 `X-XSRF-TOKEN`。两枚 Cookie 的 `Max-Age` 不得超过稳定会话剩余绝对期限；本机 Web 会话退出、撤销全部设备、刷新 Token 无效或确认重用时以相同属性和 `Max-Age=0` 清理。Web 登录和刷新均返回 `Cache-Control: no-store`。携带 `ziji_refresh` 的不安全 Web 请求必须校验 CSRF；无该 Cookie 的 Mobile Bearer 请求不要求 CSRF。移动端在响应体返回刷新 Token 且绝不设置上述认证或 CSRF Cookie，并存入系统安全存储。两套 operationId 分离，底层复用同一认证服务。刷新 Token 正常轮换必须在同一数据库事务锁定当前 Token、消费旧 Token、插入新 Token、设置 `replacedById` 并更新 `lastSeenAt`；失败整体回滚且并发最多一个成功。已消费 Token 保留可识别状态，后续重用攻击撤销整个设备会话。

### 4.2 当前用户

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/users/me` | 查询用户资料和设置 | AUTH-006 |
| PATCH | `/users/me` | 修改昵称、时区、基准币种、语言和金额格式 | AUTH-006 |
| POST | `/users/me/password-change` | 使用当前密码修改密码 | AUTH-003 |

V1 用户状态 `User.status` 固定为 `ACTIVE`、`LOCKED`、`CLOSING`、`CLOSED`。其中 `LOCKED` 表示认证安全锁定，不表示账户归档或注销完成。

已登录改密缺少有效 Bearer 会话时返回 `401 AUTHENTICATION_REQUIRED`；当前密码错误、密码 Hash 不支持或用户状态不允许改密时返回 `401 INVALID_CREDENTIALS`。两类响应均不得泄露用户状态、Hash 版本或内部失败原因，并设置 `Cache-Control: no-store`。

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
| GET | `/accounts/{accountId}/liability-details` | 查询独立负债详情或稳定空详情 | LIA-001、005 |
| PUT | `/accounts/{accountId}/liability-details` | 首次创建或完整替换负债详情 | LIA-001、005 |
| PATCH | `/accounts/{accountId}/liability-details` | 局部修改负债详情 | LIA-001、005 |
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

账户 ID 由服务端在原子创建事务内生成并在响应中返回；客户端不得提交 `id`。由于请求 schema 禁止额外字段，提交 `id` 返回 `400 VALIDATION_ERROR`，服务端不得静默接受或忽略客户端账户 ID。V1 不支持离线创建账户或客户端指定账户 UUID。

`openingBalance` 只能在 `POST /accounts` 的原子创建事务中生成内部 `OPENING` 交易；`POST /transactions` 不接受 `OPENING`。字段缺失或为 `null` 时不创建期初交易；存在时 `amount` 必须为账户币种入账精度内的正十进制字符串，零、负数、额外字段以及不合法 class/type 组合均返回 `400 VALIDATION_ERROR`。服务端使用 `businessAt` 按当前用户 IANA 时区派生并固化 `businessDate` 和交易时区。

期初分录固定为：

| accountClass | 借方 | 贷方 | 边界 |
| --- | --- | --- | --- |
| ASSET | 账户 `PRIMARY` | `EQUITY_OPENING_BALANCE` | 不计收入或支出 |
| INVESTMENT | 账户 `PRIMARY` | `EQUITY_OPENING_BALANCE` | 仅券商现金；不写 `POSITION_COST`，不创建持仓 |
| LIABILITY | `EQUITY_OPENING_BALANCE` | 账户 `PRIMARY` | 正数表示已有债务；不计收入或支出 |

`openingTransactionId` 仅在期初交易已随账户创建成功入账时返回 UUID；没有期初余额时返回 `null`。当前 V1 不接收 `creditLimit`：它没有可审计的持久化事实落点，且不得映射为 `current_amount_due`、余额或其他负债事实。

`accountClass` 与 `accountType` 必须符合冻结矩阵：

| accountClass | accountType |
| --- | --- |
| ASSET | BANK、WECHAT、ALIPAY、CASH、OTHER |
| INVESTMENT | BROKERAGE、FUND、OTHER |
| LIABILITY | CREDIT_CARD、LOAN、CONSUMER_LOAN、OTHER |

基金账户使用 `FUND`，消费贷款使用 `CONSUMER_LOAN`。`OTHER` 只表示对应大类的其他账户，不得代替这两类。

创建账户时服务端原子创建账户、创建者的 ACTIVE OWNER membership、`included=true/ratio=1.000000` 计入设置和所需账务科目。所有账户都通过 ACTIVE AccountMember 授权，创建者字段不构成权限捷径。

账户资料 PATCH 必须同时提交 `Idempotency-Key` 与强 `If-Match`。服务端先检查当前 ACTIVE membership、账户可见性和 OWNER 写权限，再校验条件头、merge-patch 载荷和幂等键；不可见账户即使携带语法正确的陈旧 ETag 也统一返回 `404 RESOURCE_NOT_FOUND`，不创建幂等记录且不携带 `versionConflict`。规范化 request Hash 包含实际 `accountId`、类型化 merge-patch 和规范化 If-Match；同 Key/同 Hash 的成功或 `VERSION_CONFLICT` 均精确重放首次安全响应，同 Key/异 Hash 返回 `409 IDEMPOTENCY_KEY_REUSED`。可见账户的 `VERSION_CONFLICT` 使用 §2.5 三字段，`resourceLocation` 固定为 `/api/v1/accounts/{accountId}`。

负债详情是独立资源，不嵌入 `Account`，也不扩展 `POST /accounts`。负债账户尚无持久化详情行时，GET 仍返回 `200`：六个业务字段均为 `null`、`version=0`，并返回强 ETag `"0"`；持久化详情从 version 1 开始。空详情的 `"0"` 只表示稳定读取投影，首次创建必须使用 `PUT + If-None-Match: *`，不得使用 `If-Match: "0"`。V1 不提供 DELETE；清空字段使用 PATCH 显式提交 `null`。

PUT 是完整替换，六个业务字段必须全部出现并可为 `null`。首次 PUT 只接受 `If-None-Match: *`，已有持久化行时返回 `409 VERSION_CONFLICT`；已有详情的完整替换只接受强 `If-Match: "<正整数>"`。PUT 必须在 `If-Match` 与 `If-None-Match` 中恰好提交一个，缺失、同时提交、重复、弱 ETag、未加引号、`*` 用作 If-Match、零、负数、非数字或溢出均返回 `400 VALIDATION_ERROR`。PATCH 至少提交一个字段，必须携带强 If-Match；尚无持久化行时返回 `404 RESOURCE_NOT_FOUND`。成功 GET/PUT/PATCH 返回详情自身的强 ETag，PUT/PATCH 不推进 Account.version，失败响应不带成功 ETag。

字段契约为：

| 字段 | 类型与语义 |
| --- | --- |
| interestRate | `null` 或 0～1 的十进制字符串，最多 8 位小数；单位是年化比例，`0.045` 表示 4.5% |
| loanDate / dueDate | `null` 或 ISO date；同时存在时 `dueDate >= loanDate` |
| billingDay / repaymentDay | `null` 或整数 1～31；短月取月末，仅用于提醒，不改变账务日期 |
| currentAmountDue | `null` 或账户币种精度内的非负十进制字符串；仅提醒，不是余额事实 |

字段矩阵固定为：CREDIT_CARD 允许 `interestRate/billingDay/repaymentDay/currentAmountDue`，禁止 `loanDate/dueDate`；LOAN、CONSUMER_LOAN 允许 `interestRate/loanDate/dueDate/repaymentDay/currentAmountDue`，禁止 `billingDay`；OTHER 允许全部字段。请求均禁止额外字段。类型、格式、范围错误返回 `400 VALIDATION_ERROR`；字段不适用、日期关系或提醒金额币种精度不符合账户事实返回 `422 BUSINESS_RULE_VIOLATION`。

权限顺序固定为：未认证先返回 `401 AUTHENTICATION_REQUIRED`；随后按当前 ACTIVE membership 和账户类型判断可见性，无 ACTIVE membership、LEFT、REMOVED、已结束 membership、无关用户、仅 created_by 命中或非 LIABILITY 账户统一返回 `404 RESOURCE_NOT_FOUND`；OWNER/EDITOR 可写，VIEWER 写入返回 `403 PERMISSION_DENIED`。可见性和账户类型优先于条件头及业务字段，写权限优先于条件头；合法可见且可写后依次校验条件头格式、字段组合/日期/币种业务规则和当前持久版本。上述 400/403/404/409/422 均在创建新幂等记录前结束。

PUT/PATCH 复用 §2.4 的统一幂等作用域与规范化请求 Hash：实际 accountId、类型化载荷以及实际提交的 If-Match 或 If-None-Match 前置进入 Hash；同 Key/同 Hash 精确重放首次结果，同 Key/异 Hash 返回 `409 IDEMPOTENCY_KEY_REUSED`。为兼顾安全重放与无条件覆盖禁止，服务端在可见性、权限、头格式和业务字段校验后先识别已有的同 Key/Hash 安全终态；命中时重放首次响应，不重新执行写入。没有可重放终态时，If-None-Match 与当前持久行存在性、If-Match 与当前详情版本必须先校验，冲突返回 `409 VERSION_CONFLICT` 且不创建幂等记录；通过后才取得统一幂等记录并执行写入。

三条 operation 的错误体均为 Problem Details。GET 显式声明 `400/401/403/404/200`；PUT/PATCH 显式声明 `400/401/403/404/409/422` 以及成功响应。详情 version conflict 的 `resourceLocation` 固定为 `/api/v1/accounts/{accountId}/liability-details`，不得嵌入当前详情对象或泄漏不可见账户事实。

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

修订或释放当前未结束且尚未逻辑过期的 LiquidityHold 必须携带 `If-Match`。修订在同一事务关闭旧记录并创建修正版；释放同时写入 `releasedAt`、`endedAt` 和 `endReason=RELEASED`。新增、修订和释放都不产生 LedgerEntry，并保留操作者、时间、来源及版本关系。

LiquidityHold 的公共机器字段和数据库事实映射如下：

| API 字段 | 数据库事实 | 规则 |
| --- | --- | --- |
| `type` | `hold_type` | `FROZEN`、`IN_TRANSIT`、`RESERVED`；创建和修订均必填，修订允许改变类型 |
| `amount` / `currency` | `amount` / `currency` | 请求顶层独立提交；`amount` 为 `PositiveMoney` 十进制字符串，`currency` 使用现有 `Currency` schema，币种必须匹配账户 |
| `reason` | `note` | 创建/修订请求必填；响应可为 null 以容纳内部历史记录 |
| `source` | `source` | 响应返回；公共人工写入固定为 `MANUAL` |
| `supersedesId` | `previous_revision_id` | 当前版本关闭并替代的上一版本 ID |
| `createdBy`、`createdAt`、`updatedAt` | `created_by`、`created_at`、`updated_at` | 只读审计字段，响应返回 |
| `releasedAt`、`endedAt`、`endReason` | `released_at`、`ended_at`、`end_reason` | 只读生命周期事实，响应返回；`endReason` 为 `RELEASED`、`SUPERSEDED` 或 `EXPIRED` |

生命周期按每次请求的 `asOf` 时点计算，不把“存在当前修订”误当作“当前时点有效”：

```text
effective_at <= asOf
AND (ended_at IS NULL OR ended_at > asOf)
AND (expires_at IS NULL OR expires_at > asOf)
```

状态映射固定为：`PENDING` 表示 `effectiveAt > asOf` 且尚未终止；`ACTIVE` 表示已生效且满足上述有效条件；`RELEASED`、`SUPERSEDED` 分别由 `endReason` 映射；`EXPIRED` 表示已写入 `endReason=EXPIRED`，或尚未最终化但 `expiresAt <= asOf`。到达 `expiresAt` 即逻辑过期，查询和任何写入前置校验都必须先按时点判断。过期最终化写入 `endedAt=expiresAt`、`endReason=EXPIRED` 并递增版本；最终化尚未运行时，不能释放或修订该记录。

自动过期使用现有 PostgreSQL advisory-lock 调度机制执行任务 `LIQUIDITY_HOLD_EXPIRY_FINALIZER`；任务按可重试批次扫描到期且未结束版本，幂等地物化过期事实并写入审计。手工释放/修订与最终化在同一事务按当前版本串行化：先成功关闭当前版本的一方获胜；另一方使用旧 ETag 返回 `VERSION_CONFLICT`，重新读取后发现已过期则返回 `BUSINESS_RULE_VIOLATION`。本任务不新增调度器、迁移或任务实现；若 BE-ACC-006 需要扩展现有调度架构，必须另登记任务后实施。

权限和不可枚举语义固定为：OWNER、EDITOR、VIEWER 的 ACTIVE membership 均可查询；OWNER、EDITOR 可创建、修订和释放；VIEWER 写入返回 `403 PERMISSION_DENIED`。LEFT、REMOVED、已结束 membership 周期和无关用户不能访问，账户不存在、账户不可见或 hold 不属于该账户统一返回 `404 RESOURCE_NOT_FOUND`，不得以 `accounts.created_by` 替代 membership 授权。

四个 LiquidityHold operation 的错误契约为：列表允许 `400 VALIDATION_ERROR`、`401 AUTHENTICATION_REQUIRED`、`403 PERMISSION_DENIED`、`404 RESOURCE_NOT_FOUND`；创建额外允许 `409 IDEMPOTENCY_KEY_REUSED`/`IDEMPOTENCY_REQUEST_IN_PROGRESS`、必要的 `422 BUSINESS_RULE_VIOLATION` 和无法安全重建历史幂等响应时的 `500 INTERNAL_ERROR`；修订、释放额外允许严格 If-Match 下的 `409 VERSION_CONFLICT`、两类幂等冲突、必要的 `422 BUSINESS_RULE_VIOLATION` 和无法安全重建历史幂等响应时的 `500 INTERNAL_ERROR`。资源不可见永远优先按 404 返回，不能创建幂等记录，也不能通过 versionConflict 泄露资源存在性。

### 4.4 交易与流水

| 方法 | 路径 | 说明 | 需求 |
| --- | --- | --- | --- |
| GET | `/transactions` | 按账户、类型、日期、分类查询流水 | LED-* |
| POST | `/transactions` | 创建并确认交易 | LED-001～008、LIA-* |
| GET | `/transactions/{transactionId}` | 查询交易、分录和版本关系 | LED-001、011 |
| POST | `/transactions/{transactionId}/revisions` | 冲正旧版本并创建新版本 | LED-009 |
| POST | `/transactions/{transactionId}/reversal` | 作废并冲正交易 | LED-010 |
| POST | `/accounts/{accountId}/balance-adjustments` | 创建余额调整 | ACC-005 |

`listTransactions` 与 `getTransaction` 的 `operationId` 固定不变。列表筛选、排序、游标绑定和 ACTIVE membership 可见性遵循 §2.6；两条读取接口的 `400/401/403/404/200` 响应分别对应 `Problem` 或相应的交易 envelope。交易类型筛选只复用领域 `TransactionType`，分类筛选只接受分类 UUID；客户端不得提交内部 `LedgerAccount`、`LedgerEntry` 或系统科目标识。

`postTransaction` 不接受 `OPENING`；期初余额只能通过 `createAccount` 原子创建。`LIABILITY_REPAYMENT` 是公共请求 discriminator，Ledger 与数据库固定持久化为内部 `TransactionType.REPAYMENT`，不增加第二种交易事实。请求联合、类型、金额正负和可由 schema 表达的 class/type 组合违反时返回 `400 VALIDATION_ERROR`；通过 schema 后的资源可见性、成员权限和业务状态仍分别遵循既有 `404`、`403`、`422` 语义。

`postTransaction` 在无法安全重建历史幂等响应或未预期基础设施失败时返回 `500 INTERNAL_ERROR`。`reviseTransaction` 与 `reverseTransaction` 的路径、请求体、`If-Match`、`Idempotency-Key` 错误返回 `400 VALIDATION_ERROR`，交易或关联账户不可见返回 `404 RESOURCE_NOT_FOUND`，业务规则违反返回 `422 BUSINESS_RULE_VIOLATION`，幂等安全重建或基础设施失败返回 `500 INTERNAL_ERROR`。`createBalanceAdjustment` 的账户不可见、业务规则违反和幂等安全重建或基础设施失败分别对应 `404`、`422`、`500`，请求与 `Idempotency-Key` 错误仍返回 `400`。

BE-LIA-002 的公共命令边界固定为：

- 信用卡消费复用 `EXPENSE` 分支；`accountId` 指向 CREDIT_CARD 时，Ledger 借费用/分类科目、贷信用卡 PRIMARY，增加负债并计入支出。其他负债类型不能以该分支伪装信用卡消费。
- 借款到账新增 `LIABILITY_BORROWING` 分支，字段为 `assetAccountId`、`liabilityAccountId`、`currency`、`amount` 和公共时间/备注。资产账户与负债账户必须可写、ACTIVE、币种等于请求币种；Ledger 借资产 PRIMARY、贷负债 PRIMARY，内部持久化为 `TransactionType.TRANSFER`，不计收入。
- 负债还款使用 `LIABILITY_REPAYMENT` 并持久化为 `REPAYMENT`。本金借负债 PRIMARY、贷付款资产 PRIMARY且不计支出；利息和手续费分别借费用科目、贷付款资产 PRIMARY并计入支出。同币种内平衡并原子提交。

上述命令均只接收语义字段，不接收 LedgerAccountId、LedgerEntry、借贷方向或系统科目 code。账户类型、可见性、币种一致性和费用分类组合属于业务校验，违反时按既有 404/403/422 语义处理。

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

退款必须提交非空 `originalTransactionId`，且服务端从该原支出继承分类、支出对方科目和可退款余额；请求不得提交 `categoryId`。客户端和普通 HTTP 均不得提交 `LedgerAccountId`、`LedgerEntry`、借贷方向或系统科目 code。收入/支出及有手续费转账的系统对方科目由 Ledger 在同一账务事务内按当前用户、分类 UUID、科目性质和币种惰性读取或确保，code 固定为 `INCOME_CATEGORY_<categoryUuid>` / `EXPENSE_CATEGORY_<categoryUuid>`。

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

借款到账：

```json
{
  "type": "LIABILITY_BORROWING",
  "businessAt": "2026-08-12T13:30:00+08:00",
  "assetAccountId": "0191...",
  "liabilityAccountId": "0192...",
  "currency": "CNY",
  "amount": "10000.00",
  "note": "借款到账"
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

`TransactionTemplate.command` 使用周期规则专用联合体，只允许 `INCOME`、`EXPENSE`、`TRANSFER`；不得接受公共交易创建支持的 `REFUND`、`LIABILITY_BORROWING`、`LIABILITY_REPAYMENT`，也不得重新开放已移除的 `OPENING`。

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

失败语义：

- `limit` 缺省时默认 50，合法范围为 1～200；非整数、超范围或格式非法的 `limit` 返回 `400 VALIDATION_ERROR`。
- 空、篡改、跨用户、无法验证或不属于当前用户已投递边界的 `cursor` 返回 `400 VALIDATION_ERROR`。
- 上述错误响应不得回显原始游标、recipient、sequence、SQL、表名或 payload。

### 5.2 上传操作

```text
POST /sync/operations
```

同步载荷中的 `operationId` 是客户端操作 ID（`SyncOperation.operationId`），用于识别该离线操作；它不同于统一幂等作用域中的 OpenAPI operationId，后者仍固定为实际 `applySyncOperations`。

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

V1 此接口只接受 `entityType=TRANSACTION`，且操作矩阵固定为：

| operationType | baseVersion | entityId | payload |
| --- | --- | --- | --- |
| `CREATE` | 显式 `null` | 客户端生成并最终持久化的 Transaction UUID | 仅 `INCOME`、`EXPENSE`、`REFUND`、`TRANSFER` 语义交易；不得携带 `id` |
| `UPDATE` | 必填正整数 | 被修订的已入账 Transaction UUID | `reason + replacement`；replacement 仅上述四类且不得携带 `id`，运行时必须与原交易类型一致 |
| `REVERSE` | 必填正整数 | 被作废的已入账 Transaction UUID | `ReasonRequest` |

`ACCOUNT`、`CATEGORY`、`TAG`、`RECURRING_RULE` 和 `ARCHIVE` 不属于 V1 `applySyncOperations` 的机器契约。未来离线上传必须先具备对应 application port、账务/审计/outbox 事实链和独立任务，不得由 Sync 直接写表或构造分录。

Sync CREATE 和 UPDATE replacement 的 `businessAt`、`businessDate`、`timezone` 均为必填非空：它们是客户端入队时已确认的历史业务归属，服务端仅校验时区和日期规则，不能在稍后上传时按用户当前时区重新划分。Sync 在 BE-CAT-003 闭合交易标签事实链前不接受 `tagIds`，避免静默丢弃。退款必须提交非空 `originalTransactionId`，不接受 `categoryId`；分类、原支出费用对方科目和可退款余额从原支出事实继承。B1 Sync 转账只接受同币种、同金额普通转账：`fromAmount.currency`、`toAmount.currency`、`fee.currency` 必须一致，`fromAmount.amount=toAmount.amount`，且不接受 `exchangeRate`；`fee=0` 时 `feeCategoryId=null`，`fee>0` 时必须提供费用分类。上述跨字段条件由运行时逐项 `REJECTED`，不得转换为 FX_TRANSFER。

```json
{
  "data": {
    "results": [
      {
        "operationId": "0191...",
        "status": "APPLIED",
        "entityId": "0193...",
        "entityVersion": 1
      }
    ]
  },
  "meta": { "requestId": "req_01" }
}
```

结果状态：

- `APPLIED`：成功应用
- `DUPLICATE`：幂等重复，安全重放首次的资源标识/版本或 Problem，不重新执行业务写入
- `CONFLICT`：`error.code=VERSION_CONFLICT`，只返回 §2.5 规定的 `versionConflict` 摘要和 `resourceLocation`，不嵌入当前资源
- `REJECTED`：权限、业务规则或同键异参等不可自动重试拒绝；不得泄漏不可见资源
- `RETRYABLE`：`IDEMPOTENCY_REQUEST_IN_PROGRESS` 固定为 HTTP `409`，`INTERNAL_ERROR` 固定为 HTTP `500`；必须带 `retryAfterSeconds=5`，不携带 `versionConflict`

上传响应不返回 `changeSequence` 或 `serverCursor`。`change_log` 由既有 outbox consumer 异步生成；客户端保留原同步游标并继续调用 `GET /sync/changes` 获取已投递变更。

### 5.3 同步安全

- 同步 payload 使用冻结的 Transaction 语义命令 schema 和校验规则；外层 `entityId` 是唯一业务实体 ID，payload 不得再携带第二个 ID
- 客户端不能通过同步接口提交 LedgerAccountId、任意 LedgerEntry、借贷方向或系统科目 code；系统对方科目只由 Ledger 在单项账务事务内派生
- 每个操作重新校验当前成员权限
- 相同 idempotencyKey 不同 Hash 返回 `REJECTED`，其中 `error.code=IDEMPOTENCY_KEY_REUSED`
- `CONFLICT` 只使用 §2.5 的安全 `versionConflict` 摘要；资源不可见时改为 `REJECTED`，不得通过结果泄漏资源存在性
- 每个操作独立复用 `UnifiedIdempotencyService`。作用域固定为当前用户 + API v1 + 实际 OpenAPI operationId `applySyncOperations` + 该操作 `idempotencyKey`；客户端操作 ID（`SyncOperation.operationId`）、`entityType`、`operationType`、实际 `entityId`、`baseVersion` 的显式值或 `NULL`、`payloadVersion` 和规范化 payload 进入 requestHash。故退款的 `originalTransactionId`、Sync 的 `businessAt/businessDate/timezone`、转账金额/币种/手续费分类等冻结字段均由规范化 payload 覆盖；`deviceId` 和 `createdAt` 不进入 Hash，不另造派生 operationId。
- 同 Key 同 Hash 只安全重放首次结果并返回 `DUPLICATE`；同 Key 异 Hash 不改写既有幂等记录。单个 Transaction、LedgerEntry、audit、outbox 和幂等终态仍必须在既有事务中原子提交；批内其他操作不随该操作失败回滚。
- `deviceId` 只用于经 schema 校验的客户端设备标识，不参与身份、权限或 requestHash；`createdAt` 是本地队列元数据，不替代 payload 内业务时间，也不参与 requestHash。
- `REJECTED` 是终态，客户端不得自动重试。`RETRYABLE` 必须保留同一 `operationId`、`idempotencyKey` 和 requestHash，等待 5 秒后串行重试；后续 Mobile 编排将其回到 `PENDING`，不得误标为 `REJECTED`。

### 5.4 Mobile 当前主体与冲突处置

Mobile 在登录或刷新接口成功后，必须使用新 access token 的 Bearer 请求调用既有 `getCurrentUser`，只以响应 `User.id` 确认当前主体；未确认前不得发布 `AUTHENTICATED`、打开 SQLite user scope 或开始同步。不得解析 JWT claims，也不得从 SecureStore、演示数据或 SQLite 推断主体。

`CONFLICT` 的 `resourceLocation` 仅可精确为 `/api/v1/transactions/{UUID}`。Mobile 必须拒绝绝对 URL、`//`、query、fragment、路径穿越和其他资源；只提取 transactionId 并调用类型化 `getTransaction`，不得向任意 resourceLocation 发起请求。接受云端与放弃本地共用 `discardLocal(userId, operationId)`，在一个 SQLite transaction 删除同用户的 conflict 与旧 pending，不写服务器。编辑或作废后的重试使用 conflict `currentVersion` 作为 baseVersion，创建新的 operationId、Idempotency-Key 和规范化 Hash，并在一个 SQLite transaction 插入新 pending、删除旧 pending/conflict；同 Key 异 Hash 仍由既有协议拒绝，`REJECTED` 不自动重试。

`getCurrentUser` 的 `401` 或 `403` 均不得让 Mobile 进入 `AUTHENTICATED`、打开或继续使用任一 SQLite user scope、开始同步；它们按不可恢复的当前会话拒绝处理，立即清除内存 access token/session/userId，并按既有无效凭据/安全退出规则删除 refresh credential。删除失败进入 `RECOVERABLE_ERROR`，但不得恢复旧/新主体或 scope。刷新确认到不同 `User.id` 时同样立即关闭旧 scope、清除内存主体，并安全删除本轮已轮换且已持久化的 refresh credential；删除失败仍只处于 `RECOVERABLE_ERROR`。按 userId 隔离的缓存、游标、pending、conflict 不删除，只在没有已确认主体时不可访问。网络、`5xx` 或可恢复 SecureStore 错误不得伪造主体。此处同步载荷的 `operationId` 均指客户端操作 ID（`SyncOperation.operationId`）；统一幂等作用域中的 OpenAPI operationId 仍指实际 `applySyncOperations`。

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

### 8.1 预发布契约纠错

只有无已发布服务端实现、无已发布客户端或外部消费者的端点，才允许在同一 API 主版本内纠错；还必须同时存在关联 `CHG-*` 任务、同步更新 OpenAPI/人类契约/生成类型/RTM/测试、保留 `oasdiff` 原始结果，且不得修改检查器、新增隐式豁免、关闭或放宽 `api:breaking`。其他删除字段、收紧输入或改变语义的变更仍按破坏性变更处理并需要新 API 版本。

### 8.2 财务精度

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
