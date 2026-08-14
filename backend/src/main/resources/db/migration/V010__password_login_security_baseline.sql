-- Ziji V1：密码登录固定窗口、防枚举和账号状态安全基线。
-- CHG-AUTH-002 扩展既有 PostgreSQL 限流事实，禁止创建平行计数存储。

-- V008 对这些值检查使用 PostgreSQL 自动生成名称；在引入 LOGIN_PASSWORD / LOGIN / AUTH_LOGIN_V1
-- 作用域前改为显式名称，确保后续迁移能安全引用。
ALTER TABLE auth_rate_limit_buckets
    DROP CONSTRAINT auth_rate_limit_buckets_action_check,
    DROP CONSTRAINT auth_rate_limit_buckets_purpose_check,
    DROP CONSTRAINT auth_rate_limit_buckets_policy_code_check,
    DROP CONSTRAINT ck_auth_rate_limit_fixed_policy;

ALTER TABLE auth_rate_limit_buckets
    ADD CONSTRAINT ck_auth_rate_limit_action_values
        CHECK (action IN ('SEND_EMAIL_CHALLENGE', 'LOGIN_PASSWORD')),
    ADD CONSTRAINT ck_auth_rate_limit_purpose_values
        CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD', 'LOGIN')),
    ADD CONSTRAINT ck_auth_rate_limit_policy_values
        CHECK (policy_code IN ('AUTH_CHALLENGE_V1', 'AUTH_LOGIN_V1')),
    -- action、purpose 和策略必须成对出现，避免登录与验证码共享或错误解释同一限流桶。
    ADD CONSTRAINT ck_auth_rate_limit_scope_pair
        CHECK (
            (action = 'SEND_EMAIL_CHALLENGE'
                AND purpose IN ('REGISTER', 'RESET_PASSWORD')
                AND policy_code = 'AUTH_CHALLENGE_V1')
            OR (action = 'LOGIN_PASSWORD'
                AND purpose = 'LOGIN'
                AND policy_code = 'AUTH_LOGIN_V1')
        ),
    -- 窗口、维度、配额是安全策略事实；应用不得自行写入任意秒数或放宽配额。
    ADD CONSTRAINT ck_auth_rate_limit_window_profile
        CHECK (
            (action = 'SEND_EMAIL_CHALLENGE' AND (
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
            ))
            OR (action = 'LOGIN_PASSWORD' AND (
                (dimension = 'IP' AND window_code = 'LOGIN_IP_10M'
                    AND window_seconds = 600 AND limit_count = 30)
                OR (dimension = 'IP' AND window_code = 'LOGIN_IP_24H'
                    AND window_seconds = 86400 AND limit_count = 300)
                OR (dimension = 'EMAIL' AND window_code = 'LOGIN_EMAIL_15M'
                    AND window_seconds = 900 AND limit_count = 10)
                OR (dimension = 'EMAIL' AND window_code = 'LOGIN_EMAIL_24H'
                    AND window_seconds = 86400 AND limit_count = 50)
            ))
        );

COMMENT ON TABLE auth_rate_limit_buckets IS
    '认证固定窗口限流事实，覆盖验证码发送和密码登录；跨实例以 PostgreSQL 原子 UPSERT/行锁为权威，原始 IP、邮箱和 deviceId 不持久化';
COMMENT ON COLUMN auth_rate_limit_buckets.action IS
    '安全操作类别：SEND_EMAIL_CHALLENGE 或 LOGIN_PASSWORD；必须与 purpose、policy_code 和窗口配置严格配对';
COMMENT ON COLUMN auth_rate_limit_buckets.purpose IS
    '验证码用途 REGISTER/RESET_PASSWORD，或密码登录用途 LOGIN；不得跨操作复用限流主体';
COMMENT ON COLUMN auth_rate_limit_buckets.policy_code IS
    '安全策略版本：AUTH_CHALLENGE_V1 或 AUTH_LOGIN_V1；登录 HMAC 域必须独立于验证码域';
COMMENT ON COLUMN auth_rate_limit_buckets.dimension IS
    '主体维度；LOGIN_PASSWORD 只允许 IP、EMAIL，验证码发送仍允许 IP、EMAIL、DEVICE';
COMMENT ON COLUMN auth_rate_limit_buckets.window_code IS
    '固定窗口标识；约束同时锁定合法维度、秒数和配额，防止错误写入削弱防暴力破解策略';
