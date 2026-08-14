-- Ziji V1: unified authenticated/anonymous idempotency scope and replay lifecycle.
-- CHG-SYNC-001 keeps public write idempotency without persisting raw anonymous email subjects.

ALTER TABLE idempotency_records
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN anonymous_subject_hash bytea,
    ADD COLUMN anonymous_subject_hash_key_version smallint,
    ADD COLUMN processing_started_at timestamptz,
    ADD COLUMN processing_lease_expires_at timestamptz,
    ADD COLUMN retry_after_at timestamptz;

-- 已认证主体与匿名主体互斥；匿名主体只保存外部 HMAC-SHA-256 的 32 字节输出和版本，不保存邮箱原文。
ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_scope_subject
        CHECK (
            (user_id IS NOT NULL
                AND anonymous_subject_hash IS NULL
                AND anonymous_subject_hash_key_version IS NULL)
            OR (user_id IS NULL
                AND anonymous_subject_hash IS NOT NULL
                AND octet_length(anonymous_subject_hash) = 32
                AND anonymous_subject_hash_key_version IS NOT NULL
                AND anonymous_subject_hash_key_version > 0)
        );

-- V1 的客户端最大重试窗口为 24 小时；保留 7 天是最短安全重放保护期而不是业务事实的失效时间。
UPDATE idempotency_records
SET expires_at = created_at + interval '7 days'
WHERE expires_at < created_at + interval '7 days';

ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_minimum_retention
        CHECK (expires_at >= created_at + interval '7 days');

ALTER TABLE idempotency_records
    DROP CONSTRAINT uq_idempotency_scope;

-- 不使用 nullable user_id 的默认 NULL 作用域；两类主体分别建立唯一作用域，避免公开接口互相碰撞。
CREATE UNIQUE INDEX uq_idempotency_authenticated_scope
    ON idempotency_records (user_id, api_major_version, operation_id, idempotency_key)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_idempotency_anonymous_scope
    ON idempotency_records (
        anonymous_subject_hash,
        anonymous_subject_hash_key_version,
        api_major_version,
        operation_id,
        idempotency_key
    )
    WHERE anonymous_subject_hash IS NOT NULL;

-- PROCESSING 不单独提交；租约只用于崩溃/遗留行恢复。新写入必须在同一事务内连同业务事实、审计和 outbox 进入终态。
ALTER TABLE idempotency_records
    ADD CONSTRAINT ck_idempotency_lifecycle_v1
        CHECK (
            (
                (status = 'PROCESSING'
                    AND completed_at IS NULL
                    AND response_status IS NULL
                    AND response_reference IS NULL
                    AND resource_type IS NULL
                    AND resource_id IS NULL
                    AND processing_started_at IS NOT NULL
                    AND processing_started_at >= created_at
                    AND processing_lease_expires_at = processing_started_at + interval '30 seconds'
                    AND retry_after_at IS NULL)
                OR (status = 'SUCCEEDED'
                    AND completed_at IS NOT NULL
                    AND response_status BETWEEN 200 AND 299
                    AND response_reference IS NOT NULL
                    AND processing_started_at IS NULL
                    AND processing_lease_expires_at IS NULL
                    AND retry_after_at IS NULL
                    AND (
                        (response_reference = '{"kind":"EMPTY"}'::jsonb
                            AND resource_type IS NULL
                            AND resource_id IS NULL)
                        OR (response_reference ->> 'kind' = 'RESOURCE'
                            AND response_reference - ARRAY['kind', 'location', 'etag', 'resourceVersion'] = '{}'::jsonb
                            AND resource_type IS NOT NULL
                            AND resource_id IS NOT NULL)
                    ))
                OR (status = 'FAILED_FINAL'
                    AND completed_at IS NOT NULL
                    AND response_status BETWEEN 400 AND 499
                    AND response_reference ->> 'kind' = 'PROBLEM'
                    AND response_reference - ARRAY['kind', 'errorCode'] = '{}'::jsonb
                    AND response_reference ? 'errorCode'
                    AND resource_type IS NULL
                    AND resource_id IS NULL
                    AND processing_started_at IS NULL
                    AND processing_lease_expires_at IS NULL
                    AND retry_after_at IS NULL)
                OR (status = 'FAILED_RETRYABLE'
                    AND completed_at IS NOT NULL
                    AND response_status BETWEEN 500 AND 599
                    AND response_reference ->> 'kind' = 'PROBLEM'
                    AND response_reference - ARRAY['kind', 'errorCode', 'retryAfterSeconds'] = '{}'::jsonb
                    AND response_reference ? 'errorCode'
                    AND response_reference ->> 'retryAfterSeconds' = '5'
                    AND resource_type IS NULL
                    AND resource_id IS NULL
                    AND processing_started_at IS NULL
                    AND processing_lease_expires_at IS NULL
                    AND retry_after_at = completed_at + interval '5 seconds')
            ) IS TRUE
        ) NOT VALID,
    -- V001 已上线；两项 NOT VALID 约束不伪造历史响应，仍对 V009 后的 INSERT/UPDATE 强制执行。
    ADD CONSTRAINT ck_idempotency_response_reference_safe
        CHECK (
            (
                response_reference IS NULL
                OR (
                    jsonb_typeof(response_reference) = 'object'
                    AND response_reference ? 'kind'
                    AND response_reference - ARRAY[
                        'kind', 'location', 'etag', 'resourceVersion', 'errorCode', 'retryAfterSeconds'
                    ] = '{}'::jsonb
                    AND response_reference ->> 'kind' IN ('RESOURCE', 'EMPTY', 'PROBLEM')
                    AND octet_length(response_reference::text) <= 8192
                    AND (
                        NOT response_reference ? 'location'
                        OR (
                            jsonb_typeof(response_reference -> 'location') = 'string'
                            AND char_length(response_reference ->> 'location') <= 512
                            AND left(response_reference ->> 'location', 1) = '/'
                            AND left(response_reference ->> 'location', 2) <> '//'
                        )
                    )
                    AND (
                        NOT response_reference ? 'etag'
                        OR (
                            jsonb_typeof(response_reference -> 'etag') = 'string'
                            AND char_length(response_reference ->> 'etag') BETWEEN 1 AND 80
                        )
                    )
                    AND (
                        NOT response_reference ? 'resourceVersion'
                        OR (
                            jsonb_typeof(response_reference -> 'resourceVersion') = 'number'
                            AND response_reference ->> 'resourceVersion' ~ '^[1-9][0-9]{0,9}$'
                        )
                    )
                    AND (
                        NOT response_reference ? 'errorCode'
                        OR (
                            jsonb_typeof(response_reference -> 'errorCode') = 'string'
                            AND response_reference ->> 'errorCode' ~ '^[A-Z][A-Z0-9_]{0,79}$'
                        )
                    )
                    AND (
                        NOT response_reference ? 'retryAfterSeconds'
                        OR (
                            jsonb_typeof(response_reference -> 'retryAfterSeconds') = 'number'
                            AND response_reference ->> 'retryAfterSeconds' = '5'
                        )
                    )
                )
            ) IS TRUE
        ) NOT VALID;

COMMENT ON TABLE idempotency_records IS
    '统一幂等记录：认证用户使用 user_id，公开写接口使用版本化匿名 HMAC 主体；不得保存邮箱、密码、验证码、Token 或完整敏感响应';
COMMENT ON COLUMN idempotency_records.anonymous_subject_hash IS
    '公开写接口匿名主体的 HMAC-SHA-256 32 字节摘要；输入按域分离和无歧义编码，不保存原始邮箱';
COMMENT ON COLUMN idempotency_records.anonymous_subject_hash_key_version IS
    '匿名主体 HMAC 密钥版本；轮换期应用必须同时查询当前和上一版本，且上一版本至少保留 7 天';
COMMENT ON COLUMN idempotency_records.processing_started_at IS
    'PROCESSING 租约起点；同 Key/Hash 不并行执行业务写入';
COMMENT ON COLUMN idempotency_records.processing_lease_expires_at IS
    'PROCESSING 30 秒恢复租约；仅在同一事务未提交终态的崩溃/遗留情形下允许同 Hash 请求原子接管';
COMMENT ON COLUMN idempotency_records.retry_after_at IS
    'FAILED_RETRYABLE 的下一次允许接管时间；V1 固定为完成后 5 秒';
COMMENT ON COLUMN idempotency_records.response_reference IS
    '最多 8 KiB 的安全重放引用；仅允许 kind、相对 location、ETag、资源版本、错误码和 Retry-After，不保存 SQL、堆栈、密码、验证码或 Token';
COMMENT ON COLUMN idempotency_records.expires_at IS
    '最早清理候选时间和最短重放保护期；不是业务事实失效时间，受业务外键引用时不得删除';

-- 当前租约恢复和到期清理各自使用部分索引；匿名查询复用唯一索引并带上密钥版本。
CREATE INDEX idx_idempotency_processing_lease
    ON idempotency_records (processing_lease_expires_at)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_idempotency_cleanup_candidate
    ON idempotency_records (expires_at)
    WHERE status <> 'PROCESSING';

CREATE OR REPLACE FUNCTION enforce_idempotency_record_retention()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- 清理器只能删除终态且到达最短保护期的记录；PROCESSING 必须先按同 Hash 恢复或转终态。
    IF OLD.status = 'PROCESSING' OR CURRENT_TIMESTAMP < OLD.expires_at THEN
        RAISE EXCEPTION 'idempotency record is not eligible for cleanup'
            USING ERRCODE = '23514';
    END IF;
    -- 外键默认 RESTRICT 也会阻止删除；显式检查使交易/同步事实的保留边界可读且稳定。
    IF EXISTS (SELECT 1 FROM transactions WHERE idempotency_record_id = OLD.id)
        OR EXISTS (SELECT 1 FROM sync_operations WHERE idempotency_record_id = OLD.id) THEN
        RAISE EXCEPTION 'idempotency record is referenced by business facts'
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_idempotency_record_retention
BEFORE DELETE ON idempotency_records
FOR EACH ROW EXECUTE FUNCTION enforce_idempotency_record_retention();

-- V007 的通用授权不含 DELETE；后续受控清理任务依赖本触发器和现有外键安全删除，不开放给 PUBLIC。
REVOKE ALL ON idempotency_records FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_records TO ziji_app;
