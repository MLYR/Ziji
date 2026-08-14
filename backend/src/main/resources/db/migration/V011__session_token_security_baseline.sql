-- Ziji V1：稳定设备会话、访问 Token 与刷新 Token 轮换安全基线。
-- 历史会话/Token 不伪造生命周期、Hash 或撤销原因；NULL 基线版本仅允许不可逆安全处置，迁移后 INSERT 严格进入 V011。

ALTER TABLE user_sessions
    ADD COLUMN security_baseline_version smallint;

ALTER TABLE session_refresh_tokens
    ADD COLUMN security_baseline_version smallint;

-- 历史行可能不满足固定 30 天或撤销原因等新语义。安全处置只能改变终态，不能借此改写身份、期限或设备事实。
CREATE OR REPLACE FUNCTION validate_user_session_security_baseline()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.security_baseline_version := 1;
    ELSE
        IF NEW.security_baseline_version IS DISTINCT FROM OLD.security_baseline_version
            OR NEW.user_id IS DISTINCT FROM OLD.user_id
            OR NEW.device_id IS DISTINCT FROM OLD.device_id
            OR NEW.device_name IS DISTINCT FROM OLD.device_name
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
            RAISE EXCEPTION 'session identity and lifetime facts are immutable' USING ERRCODE = '23514';
        END IF;

        IF OLD.security_baseline_version IS NULL THEN
            IF NEW.last_seen_at IS DISTINCT FROM OLD.last_seen_at THEN
                RAISE EXCEPTION 'legacy session updates may only apply an irreversible security disposition' USING ERRCODE = '23514';
            END IF;
            IF OLD.revoked_at IS NOT NULL THEN
                IF NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                    OR NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason THEN
                    RAISE EXCEPTION 'legacy session revocation history is immutable' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;
            IF NEW.revoked_at IS NULL AND NEW.revoke_reason IS NULL THEN
                RETURN NEW;
            END IF;
            IF NEW.revoked_at IS NULL
                OR NEW.revoke_reason NOT IN (
                    'REPLACED_BY_LOGIN', 'CURRENT_DEVICE', 'SELECTED_DEVICE', 'ALL_DEVICES',
                    'PASSWORD_RESET', 'REFRESH_TOKEN_REUSE', 'SESSION_EXPIRED', 'SECURITY_ADMIN'
                )
                OR NEW.revoked_at < NEW.issued_at THEN
                RAISE EXCEPTION 'legacy session security disposition is invalid' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
    END IF;

    IF NEW.security_baseline_version IS DISTINCT FROM 1
        OR NEW.expires_at <> NEW.issued_at + interval '720 hours'
        OR NEW.device_name IS NULL
        OR char_length(NEW.device_name) NOT BETWEEN 1 AND 100
        OR NEW.device_name <> btrim(NEW.device_name)
        OR (
            NEW.device_id IS NOT NULL
            AND (
                char_length(NEW.device_id) NOT BETWEEN 1 AND 200
                OR NEW.device_id !~ '[^[:space:]]'
            )
        )
        OR NEW.last_seen_at < NEW.issued_at
        OR NEW.last_seen_at > NEW.expires_at
        OR ((NEW.revoked_at IS NULL) <> (NEW.revoke_reason IS NULL))
        OR (
            NEW.revoked_at IS NOT NULL
            AND (
                NEW.revoked_at < NEW.issued_at
                OR NEW.revoke_reason NOT IN (
                    'REPLACED_BY_LOGIN', 'CURRENT_DEVICE', 'SELECTED_DEVICE', 'ALL_DEVICES',
                    'PASSWORD_RESET', 'REFRESH_TOKEN_REUSE', 'SESSION_EXPIRED', 'SECURITY_ADMIN'
                )
            )
        ) THEN
        RAISE EXCEPTION 'session does not satisfy V011 security baseline' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.last_seen_at < OLD.last_seen_at THEN
            RAISE EXCEPTION 'session last_seen_at cannot move backwards' USING ERRCODE = '23514';
        END IF;
        IF OLD.revoked_at IS NOT NULL
            AND (
                NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
                OR NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason
            ) THEN
            RAISE EXCEPTION 'session revocation history is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_user_session_security_baseline
BEFORE INSERT OR UPDATE ON user_sessions
FOR EACH ROW EXECUTE FUNCTION validate_user_session_security_baseline();

-- 新 Token 固定 created_at = issued_at；历史 Token 只可消费、撤销或建立已消费后的 replacement，不能改写凭据事实。
CREATE OR REPLACE FUNCTION validate_refresh_token_session_window()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    session_issued_at timestamptz;
    session_expires_at timestamptz;
    session_baseline_version smallint;
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.security_baseline_version := 1;
    ELSE
        IF NEW.security_baseline_version IS DISTINCT FROM OLD.security_baseline_version
            OR NEW.session_id IS DISTINCT FROM OLD.session_id
            OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'refresh token identity and lifetime facts are immutable' USING ERRCODE = '23514';
        END IF;

        IF OLD.security_baseline_version IS NULL THEN
            IF (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
                OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
                OR (OLD.replaced_by_id IS NOT NULL AND NEW.replaced_by_id IS DISTINCT FROM OLD.replaced_by_id)
                OR (NEW.consumed_at IS NOT NULL AND NEW.consumed_at < NEW.issued_at)
                OR (NEW.revoked_at IS NOT NULL AND NEW.revoked_at < NEW.issued_at) THEN
                RAISE EXCEPTION 'legacy refresh token security disposition is invalid' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
    END IF;

    IF NEW.security_baseline_version IS DISTINCT FROM 1
        OR NEW.token_hash !~ '^v1:[0-9a-f]{64}$'
        OR NEW.created_at <> NEW.issued_at
        OR NEW.created_at < NEW.issued_at
        OR NEW.created_at >= NEW.expires_at THEN
        RAISE EXCEPTION 'refresh token does not satisfy V011 security baseline' USING ERRCODE = '23514';
    END IF;

    SELECT issued_at, expires_at, security_baseline_version
    INTO session_issued_at, session_expires_at, session_baseline_version
    FROM user_sessions
    WHERE id = NEW.session_id;

    IF NOT FOUND
        OR session_baseline_version IS DISTINCT FROM 1
        OR NEW.expires_at <> session_expires_at
        OR NEW.issued_at < session_issued_at
        OR NEW.issued_at >= session_expires_at THEN
        RAISE EXCEPTION 'refresh token must remain within a V011 stable session lifetime' USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
            OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at)
            OR (OLD.replaced_by_id IS NOT NULL AND NEW.replaced_by_id IS DISTINCT FROM OLD.replaced_by_id) THEN
            RAISE EXCEPTION 'refresh token security history is immutable' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_refresh_token_session_window
BEFORE INSERT OR UPDATE ON session_refresh_tokens
FOR EACH ROW EXECUTE FUNCTION validate_refresh_token_session_window();

-- 延迟到事务提交校验，允许“消费旧 Token、插入新 Token、建立 replaced_by”按安全轮换顺序原子完成。
CREATE OR REPLACE FUNCTION validate_refresh_token_replacement()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    replacement_session_id uuid;
    replacement_issued_at timestamptz;
BEGIN
    IF NEW.replaced_by_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT session_id, issued_at
    INTO replacement_session_id, replacement_issued_at
    FROM session_refresh_tokens
    WHERE id = NEW.replaced_by_id;

    IF NOT FOUND
        OR NEW.consumed_at IS NULL
        OR replacement_session_id <> NEW.session_id
        OR replacement_issued_at < NEW.consumed_at THEN
        RAISE EXCEPTION 'refresh token replacement must stay in session and follow consumption' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER trg_validate_refresh_token_replacement
AFTER INSERT OR UPDATE ON session_refresh_tokens
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_refresh_token_replacement();

-- 会话查找、到期处置和刷新历史均需索引，但刷新 Token 原文只可由唯一摘要索引定位。
CREATE INDEX idx_user_sessions_active_expiry
    ON user_sessions (expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_session_refresh_tokens_session_issued
    ON session_refresh_tokens (session_id, issued_at DESC);

-- 应用角色只通过状态转换保留安全历史，不能删除会话或已消费刷新 Token 规避重用检测。
REVOKE DELETE ON user_sessions, session_refresh_tokens FROM ziji_app;
REVOKE ALL ON FUNCTION validate_user_session_security_baseline() FROM PUBLIC;
REVOKE ALL ON FUNCTION validate_refresh_token_session_window() FROM PUBLIC;
REVOKE ALL ON FUNCTION validate_refresh_token_replacement() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION validate_user_session_security_baseline() TO ziji_app;
GRANT EXECUTE ON FUNCTION validate_refresh_token_session_window() TO ziji_app;
GRANT EXECUTE ON FUNCTION validate_refresh_token_replacement() TO ziji_app;

COMMENT ON TABLE user_sessions IS
    '稳定设备会话；V011 新行固定 30 天绝对有效期，历史行仅允许不可逆安全处置，刷新轮换不改变会话 ID 或到期时间';
COMMENT ON COLUMN user_sessions.security_baseline_version IS
    'NULL 仅表示 V011 前历史行，不能升级伪造；触发器为迁移后 INSERT 设为 1 并严格执行会话安全基线';
COMMENT ON COLUMN user_sessions.device_id IS
    '客户端不透明稳定设备标识；可空，非空时保留原值且长度 1 至 200、不得全空白，仅用于同用户重复登录替换旧活动会话';
COMMENT ON COLUMN user_sessions.device_name IS
    '展示设备名称；应用负责 NFKC/trim，数据库强制 1 至 100 字符与无首尾空格';
COMMENT ON COLUMN user_sessions.last_seen_at IS
    'V011 会话仅在创建和成功刷新时单调更新，且必须位于稳定会话绝对有效期内';
COMMENT ON COLUMN user_sessions.revoke_reason IS
    '会话撤销原因；V011 新行与 revoked_at 成对，取值受安全枚举约束，历史空原因不伪造';
COMMENT ON TABLE session_refresh_tokens IS
    '刷新 Token 历史；V011 新行固定同会话到期和凭据事实，历史行仅允许不可逆安全处置，不保存原文';
COMMENT ON COLUMN session_refresh_tokens.security_baseline_version IS
    'NULL 仅表示 V011 前历史 Token，不能升级伪造；触发器为迁移后 INSERT 设为 1 并严格执行刷新 Token 基线';
COMMENT ON COLUMN session_refresh_tokens.token_hash IS
    'V011 刷新 Token 域分离 SHA-256 摘要，固定 v1: 加 64 位小写十六进制；绝不保存原文，历史格式不改写';
COMMENT ON COLUMN session_refresh_tokens.replaced_by_id IS
    '正常轮换的新 Token；同会话、晚于旧 Token 消费，旧 Token 保留以支持后续重用攻击识别';
