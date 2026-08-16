-- Ziji V1：CHG-SYNC-004 使 VERSION_CONFLICT 摘要可安全重放，且不持久化完整资源。

ALTER TABLE idempotency_records
    DROP CONSTRAINT ck_idempotency_lifecycle_v1,
    DROP CONSTRAINT ck_idempotency_response_reference_safe,
    -- 保持 V009 的历史 NOT VALID 纪律；V016 后的新写入必须只有受控终态形状。
    ADD CONSTRAINT ck_idempotency_lifecycle_v2
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
                    AND response_reference IS NOT NULL
                    AND resource_type IS NULL
                    AND resource_id IS NULL
                    AND processing_started_at IS NULL
                    AND processing_lease_expires_at IS NULL
                    AND retry_after_at IS NULL
                    AND (
                        -- 普通问题只保留稳定错误码，不能夹带冲突摘要或任意载荷。
                        (response_reference ->> 'kind' = 'PROBLEM'
                            AND response_reference - ARRAY['kind', 'errorCode'] = '{}'::jsonb
                            AND response_reference ? 'errorCode'
                            AND response_reference ->> 'errorCode' <> 'VERSION_CONFLICT')
                        OR (
                            -- 冲突只重放首次版本、由版本派生的 ETag 和相对资源路径。
                            response_status = 409
                            AND response_reference ->> 'kind' = 'VERSION_CONFLICT'
                            AND response_reference ->> 'errorCode' = 'VERSION_CONFLICT'
                            AND response_reference ?& ARRAY[
                                'kind', 'errorCode', 'currentVersion', 'currentEtag', 'resourceLocation'
                            ]
                            AND response_reference - ARRAY[
                                'kind', 'errorCode', 'currentVersion', 'currentEtag', 'resourceLocation'
                            ] = '{}'::jsonb
                            AND jsonb_typeof(response_reference -> 'currentVersion') = 'number'
                            AND response_reference ->> 'currentVersion' ~ '^[1-9][0-9]{0,9}$'
                            AND (response_reference ->> 'currentVersion')::numeric <= 9999999999
                            AND (response_reference ->> 'currentEtag')
                                = ('"' || (response_reference ->> 'currentVersion') || '"')
                        )
                    ))
                OR (status = 'FAILED_RETRYABLE'
                    AND completed_at IS NOT NULL
                    AND response_status BETWEEN 500 AND 599
                    AND response_reference ->> 'kind' = 'PROBLEM'
                    AND response_reference - ARRAY['kind', 'errorCode', 'retryAfterSeconds'] = '{}'::jsonb
                    AND response_reference ? 'errorCode'
                    AND response_reference ->> 'errorCode' <> 'VERSION_CONFLICT'
                    AND response_reference ->> 'retryAfterSeconds' = '5'
                    AND resource_type IS NULL
                    AND resource_id IS NULL
                    AND processing_started_at IS NULL
                    AND processing_lease_expires_at IS NULL
                    AND retry_after_at = completed_at + interval '5 seconds')
            ) IS TRUE
        ) NOT VALID,
    -- 仅允许已声明的安全字段；路径和版本边界阻止把完整响应、外链或控制字符写入幂等记录。
    ADD CONSTRAINT ck_idempotency_response_reference_safe_v2
        CHECK (
            (
                response_reference IS NULL
                OR (
                    jsonb_typeof(response_reference) = 'object'
                    AND response_reference ? 'kind'
                    AND response_reference - ARRAY[
                        'kind', 'location', 'etag', 'resourceVersion', 'errorCode', 'retryAfterSeconds',
                        'currentVersion', 'currentEtag', 'resourceLocation'
                    ] = '{}'::jsonb
                    AND response_reference ->> 'kind' IN ('RESOURCE', 'EMPTY', 'PROBLEM', 'VERSION_CONFLICT')
                    AND octet_length(response_reference::text) <= 8192
                    AND (
                        NOT response_reference ? 'location'
                        OR (
                            jsonb_typeof(response_reference -> 'location') = 'string'
                            AND char_length(response_reference ->> 'location') <= 512
                            AND left(response_reference ->> 'location', 1) = '/'
                            AND left(response_reference ->> 'location', 2) <> '//'
                            AND response_reference ->> 'location' !~ '[[:cntrl:]]'
                        )
                    )
                    AND (
                        NOT response_reference ? 'etag'
                        OR (
                            jsonb_typeof(response_reference -> 'etag') = 'string'
                            AND char_length(response_reference ->> 'etag') BETWEEN 1 AND 80
                            AND response_reference ->> 'etag' !~ '[[:cntrl:]]'
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
                    AND (
                        NOT response_reference ? 'currentVersion'
                        OR (
                            jsonb_typeof(response_reference -> 'currentVersion') = 'number'
                            AND response_reference ->> 'currentVersion' ~ '^[1-9][0-9]{0,9}$'
                            AND (response_reference ->> 'currentVersion')::numeric <= 9999999999
                        )
                    )
                    AND (
                        NOT response_reference ? 'currentEtag'
                        OR (
                            jsonb_typeof(response_reference -> 'currentEtag') = 'string'
                            AND char_length(response_reference ->> 'currentEtag') BETWEEN 1 AND 80
                            AND response_reference ->> 'currentEtag' !~ '[[:cntrl:]]'
                        )
                    )
                    AND (
                        NOT response_reference ? 'resourceLocation'
                        OR (
                            jsonb_typeof(response_reference -> 'resourceLocation') = 'string'
                            AND char_length(response_reference ->> 'resourceLocation') BETWEEN 1 AND 512
                            AND left(response_reference ->> 'resourceLocation', 1) = '/'
                            AND left(response_reference ->> 'resourceLocation', 2) <> '//'
                            AND position('//' IN response_reference ->> 'resourceLocation') = 0
                            AND response_reference ->> 'resourceLocation' !~ '[[:cntrl:]]'
                        )
                    )
                )
            ) IS TRUE
        ) NOT VALID;

COMMENT ON CONSTRAINT ck_idempotency_lifecycle_v2 ON idempotency_records IS
    'V016 终态约束：FAILED_FINAL 仅允许普通错误码或可重放的 VERSION_CONFLICT 最小摘要，不保存完整资源';
COMMENT ON CONSTRAINT ck_idempotency_response_reference_safe_v2 ON idempotency_records IS
    'V016 安全引用白名单：限制 JSON 字段、8KiB 上限、版本与由版本派生的 ETag、相对资源路径';
COMMENT ON COLUMN idempotency_records.response_reference IS
    '最多 8 KiB 的安全重放引用；普通问题仅存错误码，VERSION_CONFLICT 仅存首次版本、派生 ETag 和相对路径；不得保存完整资源、SQL、堆栈、密码、验证码或 Token';
