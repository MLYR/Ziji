-- Ziji V1: authentication challenge invalidation and PostgreSQL fixed-window rate limits.
-- CHG-AUTH-001 freezes the cross-instance abuse-control facts; business orchestration stays in auth.

ALTER TABLE email_challenges
    ADD COLUMN invalidated_at timestamptz,
    ADD COLUMN invalidation_reason varchar(32);

ALTER TABLE email_challenges
    ALTER COLUMN max_attempts SET DEFAULT 5,
    -- V1 固定单个验证码最多允许 5 次错误尝试，业务代码不得提高上限。
    ADD CONSTRAINT ck_email_challenges_max_attempts_v1
        CHECK (max_attempts = 5),
    ADD CONSTRAINT ck_email_challenges_expiry_v1
        CHECK (expires_at = created_at + interval '10 minutes'),
    ADD CONSTRAINT ck_email_challenges_attempt_count_limit
        CHECK (attempt_count <= max_attempts),
    ADD CONSTRAINT ck_email_challenges_invalidation_pair
        CHECK ((invalidated_at IS NULL AND invalidation_reason IS NULL)
            OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)),
    ADD CONSTRAINT ck_email_challenges_invalidation_time
        CHECK (invalidated_at IS NULL OR invalidated_at >= created_at),
    ADD CONSTRAINT ck_email_challenges_invalidation_reason
        CHECK (invalidation_reason IS NULL
            OR invalidation_reason IN ('REPLACED', 'EXPIRED', 'MAX_ATTEMPTS', 'SECURITY_REVOKED')),
    ADD CONSTRAINT ck_email_challenges_not_consumed_and_invalidated
        CHECK (NOT (consumed_at IS NOT NULL AND invalidated_at IS NOT NULL));

COMMENT ON COLUMN email_challenges.code_hash IS
    '仅保存验证码 Hash；验证码明文不得进入普通数据库字段、日志或未加密 outbox 载荷';
COMMENT ON COLUMN email_challenges.invalidated_at IS
    '验证码失效时间；与 invalidation_reason 必须成对出现，不能与 consumed_at 同时出现';
COMMENT ON COLUMN email_challenges.invalidation_reason IS
    '失效原因；新挑战成功入队后旧活动挑战使用 REPLACED';

-- 同邮箱、同用途至多一个活动挑战；应用在同一事务内失效旧挑战并写入新挑战和投递事件，失败时整体回滚。
CREATE UNIQUE INDEX uq_email_challenges_active
    ON email_challenges (email_normalized, purpose)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE auth_rate_limit_buckets (
    id uuid PRIMARY KEY,
    action varchar(40) NOT NULL
        CHECK (action IN ('SEND_EMAIL_CHALLENGE')),
    purpose varchar(20) NOT NULL
        CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD')),
    dimension varchar(12) NOT NULL
        CHECK (dimension IN ('IP', 'EMAIL', 'DEVICE')),
    subject_hash bytea NOT NULL
        CHECK (octet_length(subject_hash) = 32),
    hash_key_version smallint NOT NULL
        CHECK (hash_key_version > 0),
    policy_code varchar(40) NOT NULL
        CHECK (policy_code = 'AUTH_CHALLENGE_V1'),
    window_code varchar(32) NOT NULL,
    window_seconds integer NOT NULL
        CHECK (window_seconds > 0),
    limit_count integer NOT NULL
        CHECK (limit_count > 0),
    window_started_at timestamptz NOT NULL,
    window_ends_at timestamptz NOT NULL,
    request_count integer NOT NULL DEFAULT 0
        CHECK (request_count >= 0),
    blocked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_auth_rate_limit_bucket
        UNIQUE (
            action, purpose, dimension, subject_hash, hash_key_version,
            policy_code, window_code, window_started_at
        ),
    CONSTRAINT ck_auth_rate_limit_window_bounds
        CHECK (window_ends_at = window_started_at + window_seconds * interval '1 second'),
    -- 固定窗口起点按 UTC Unix epoch 对齐，确保多实例不会各自创建滑动窗口。
    CONSTRAINT ck_auth_rate_limit_window_alignment
        CHECK (
            window_started_at = date_trunc('second', window_started_at)
            AND mod(extract(epoch FROM window_started_at), window_seconds) = 0
        ),
    CONSTRAINT ck_auth_rate_limit_blocked_until
        CHECK (blocked_until IS NULL OR blocked_until = window_ends_at),
    CONSTRAINT ck_auth_rate_limit_blocked_state
        CHECK (
            (request_count <= limit_count AND blocked_until IS NULL)
            OR (request_count > limit_count AND blocked_until = window_ends_at)
        ),
    CONSTRAINT ck_auth_rate_limit_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_auth_rate_limit_fixed_policy
        CHECK (
            (dimension = 'EMAIL' AND window_code = 'EMAIL_60S'
                AND window_seconds = 60 AND limit_count = 1)
            OR (dimension = 'EMAIL' AND window_code = 'EMAIL_1H'
                AND window_seconds = 3600 AND limit_count = 5)
            OR (dimension = 'EMAIL' AND window_code = 'EMAIL_24H'
                AND window_seconds = 86400 AND limit_count = 10)
            OR (dimension = 'IP' AND window_code = 'IP_10M'
                AND window_seconds = 600 AND limit_count = 20)
            OR (dimension = 'IP' AND window_code = 'IP_24H'
                AND window_seconds = 86400 AND limit_count = 100)
            OR (dimension = 'DEVICE' AND window_code = 'DEVICE_1H'
                AND window_seconds = 3600 AND limit_count = 10)
            OR (dimension = 'DEVICE' AND window_code = 'DEVICE_24H'
                AND window_seconds = 86400 AND limit_count = 30)
        )
);

-- 主体列只保存 HMAC-SHA-256 输出，不保存 IP、邮箱或 deviceId 原值。
COMMENT ON TABLE auth_rate_limit_buckets IS
    '认证验证码固定窗口限流事实；跨实例以 PostgreSQL 原子 UPSERT/行锁为权威，原始 IP、邮箱和 deviceId 不持久化';
COMMENT ON COLUMN auth_rate_limit_buckets.subject_hash IS
    'HMAC-SHA-256 32 字节摘要；用途、维度和规范化主体必须做域分离';
COMMENT ON COLUMN auth_rate_limit_buckets.hash_key_version IS
    'HMAC 密钥版本；轮换期间当前与上一版本摘要都必须参与限流，上一版本至少保留 48 小时';
COMMENT ON COLUMN auth_rate_limit_buckets.request_count IS
    '固定窗口内累计请求数；包括被拒绝请求，拒绝路径不得回滚该计数';
COMMENT ON COLUMN auth_rate_limit_buckets.blocked_until IS
    '该窗口超限后的阻塞截止时间；Retry-After 取所有超限窗口剩余时间的最大值';
COMMENT ON COLUMN auth_rate_limit_buckets.window_ends_at IS
    '固定窗口结束时间；窗口结束或 blocked_until 后至少保留 7 天再清理';

-- 应用必须按固定顺序原子 UPSERT/锁定所有维度和窗口，禁止先查后写造成跨实例竞态。
CREATE INDEX idx_auth_rate_limit_buckets_blocked
    ON auth_rate_limit_buckets (blocked_until)
    WHERE blocked_until IS NOT NULL;

CREATE INDEX idx_auth_rate_limit_buckets_cleanup
    ON auth_rate_limit_buckets (window_ends_at, blocked_until, updated_at);

-- 数据库兜底最短保留期，防止应用缺陷提前删除仍用于攻击追踪和密钥轮换的窗口事实。
CREATE OR REPLACE FUNCTION enforce_auth_rate_limit_retention()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF CURRENT_TIMESTAMP < GREATEST(
        OLD.window_ends_at,
        COALESCE(OLD.blocked_until, OLD.window_ends_at)
    ) + interval '7 days' THEN
        RAISE EXCEPTION 'auth rate limit bucket must be retained for 7 days'
            USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_auth_rate_limit_retention
BEFORE DELETE ON auth_rate_limit_buckets
FOR EACH ROW EXECUTE FUNCTION enforce_auth_rate_limit_retention();

COMMENT ON COLUMN outbox_events.payload IS
    '版本化事件载荷；验证码等敏感值若必须进入 outbox，只能使用应用层信封加密，禁止明文';

-- 限流桶是运营事实，只有保留期任务在 7 天保留期结束后才能删除。
REVOKE ALL ON auth_rate_limit_buckets FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON auth_rate_limit_buckets TO ziji_app;
