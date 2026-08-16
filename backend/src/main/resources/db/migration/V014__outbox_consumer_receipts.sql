-- Ziji V1: independent durable delivery receipts for each outbox consumer.

CREATE TABLE outbox_consumer_subscriptions (
    consumer_name varchar(100) NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    event_type varchar(100) NOT NULL,
    subscribed_from timestamptz NOT NULL,
    subscribed_until timestamptz,
    required_for_cleanup boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    valid_period tstzrange GENERATED ALWAYS AS (
        tstzrange(subscribed_from, COALESCE(subscribed_until, 'infinity'::timestamptz), '[)')
    ) STORED,
    CONSTRAINT pk_outbox_consumer_subscriptions
        PRIMARY KEY (consumer_name, aggregate_type, event_type, subscribed_from),
    CONSTRAINT ck_outbox_consumer_subscriptions_name
        CHECK (consumer_name ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,99}$'),
    CONSTRAINT ck_outbox_consumer_subscriptions_aggregate
        CHECK (aggregate_type ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,99}$'),
    CONSTRAINT ck_outbox_consumer_subscriptions_event
        CHECK (event_type ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,99}$'),
    CONSTRAINT ck_outbox_consumer_subscriptions_period
        CHECK (subscribed_until IS NULL OR subscribed_until > subscribed_from),
    CONSTRAINT ex_outbox_consumer_subscriptions_period
        EXCLUDE USING gist (
            consumer_name WITH =,
            aggregate_type WITH =,
            event_type WITH =,
            valid_period WITH &&
        )
);

CREATE INDEX idx_outbox_consumer_subscriptions_required
    ON outbox_consumer_subscriptions (aggregate_type, event_type, subscribed_from, consumer_name)
    WHERE required_for_cleanup;

COMMENT ON TABLE outbox_consumer_subscriptions IS
    '追加式、按 aggregate/event 和有效时间表达消费者订阅义务；清理只等待事件发生时有效且 required 的订阅。';
COMMENT ON COLUMN outbox_consumer_subscriptions.consumer_name IS
    '稳定消费者名称；与 aggregate_type、event_type 和订阅起点组成不可重复的订阅事实。';
COMMENT ON COLUMN outbox_consumer_subscriptions.aggregate_type IS
    '该消费者订阅的 outbox aggregate_type，非订阅 aggregate 不得建立 receipt。';
COMMENT ON COLUMN outbox_consumer_subscriptions.event_type IS
    '该消费者订阅的 outbox event_type，非订阅 event 不得建立 receipt。';
COMMENT ON COLUMN outbox_consumer_subscriptions.subscribed_from IS
    '订阅生效起点；事件 occurred_at 早于此时间不产生该消费者的订阅义务。';
COMMENT ON COLUMN outbox_consumer_subscriptions.subscribed_until IS
    '半开有效区间终点；NULL 表示开放区间，历史边界由订阅事实保留。';
COMMENT ON COLUMN outbox_consumer_subscriptions.required_for_cleanup IS
    '事件清理是否必须等待该订阅的终态 receipt；非 required 订阅不阻塞清理。';
COMMENT ON COLUMN outbox_consumer_subscriptions.valid_period IS
    '由 subscribed_from/subscribed_until 生成的半开有效区间；GiST 排斥约束禁止同消费者同 aggregate/event 重叠。';

CREATE TABLE outbox_consumer_receipts (
    consumer_name varchar(100) NOT NULL,
    outbox_event_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    claim_token uuid,
    lease_expires_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    completed_at timestamptz,
    failed_at timestamptz,
    error_code varchar(80),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_outbox_consumer_receipts PRIMARY KEY (consumer_name, outbox_event_id),
    CONSTRAINT fk_outbox_consumer_receipts_event
        FOREIGN KEY (outbox_event_id) REFERENCES outbox_events (id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_consumer_receipts_name
        CHECK (consumer_name ~ '^[A-Za-z][A-Za-z0-9_.:-]{0,99}$'),
    CONSTRAINT ck_outbox_consumer_receipts_error_code
        CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    CONSTRAINT ck_outbox_consumer_receipts_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_outbox_consumer_receipts_lifecycle
        CHECK (
            (
                status = 'PENDING'
                AND claim_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NULL
                AND failed_at IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'PROCESSING'
                AND claim_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND completed_at IS NULL
                AND failed_at IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'SUCCEEDED'
                AND claim_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NOT NULL
                AND failed_at IS NULL
                AND error_code IS NULL
            )
            OR (
                status IN ('FAILED_RETRYABLE', 'FAILED_FINAL')
                AND claim_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NULL
                AND failed_at IS NOT NULL
                AND error_code IS NOT NULL
            )
        )
);

CREATE INDEX idx_outbox_consumer_receipts_ready
    ON outbox_consumer_receipts (consumer_name, next_attempt_at, outbox_event_id)
    WHERE status IN ('PENDING', 'FAILED_RETRYABLE');

CREATE INDEX idx_outbox_consumer_receipts_lease
    ON outbox_consumer_receipts (consumer_name, lease_expires_at, outbox_event_id)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_outbox_consumer_receipts_event
    ON outbox_consumer_receipts (outbox_event_id, consumer_name);

COMMENT ON TABLE outbox_consumer_receipts IS
    '每消费者独立 outbox delivery receipt；同一事件可由多个消费者分别 claim、retry 和完成。';
COMMENT ON COLUMN outbox_consumer_receipts.consumer_name IS
    '代码/部署配置冻结的稳定消费者名称；与 outbox_event_id 联合唯一。';
COMMENT ON COLUMN outbox_consumer_receipts.outbox_event_id IS
    '既有 outbox_events.id；外键禁止在 receipt 仍存在时删除事件。';
COMMENT ON COLUMN outbox_consumer_receipts.status IS
    'PENDING、PROCESSING、SUCCEEDED、FAILED_RETRYABLE 或 FAILED_FINAL；状态只属于当前消费者。';
COMMENT ON COLUMN outbox_consumer_receipts.claim_token IS
    '当前消费者实例 claim 的随机租约 token；完成/失败更新必须匹配当前 token。';
COMMENT ON COLUMN outbox_consumer_receipts.lease_expires_at IS
    '当前消费者 claim 的租约到期时间；到期后可由同一消费者的其他实例重新抢占。';
COMMENT ON COLUMN outbox_consumer_receipts.attempt_count IS
    '当前消费者自己的领取次数，不与其他消费者共享。';
COMMENT ON COLUMN outbox_consumer_receipts.next_attempt_at IS
    '当前消费者下一次可 claim 时间，不与其他消费者共享。';
COMMENT ON COLUMN outbox_consumer_receipts.completed_at IS
    '当前消费者成功完成时间；仅 SUCCEEDED 使用。';
COMMENT ON COLUMN outbox_consumer_receipts.failed_at IS
    '当前消费者最近一次不可成功完成的时间；仅 FAILED_* 使用。';
COMMENT ON COLUMN outbox_consumer_receipts.error_code IS
    '仅用于排障的稳定安全错误码；不得保存 SQL、堆栈、载荷、凭据或敏感业务值。';

-- V007 的默认表权限不覆盖本次新增表；只授予后续受控 consumer/retention adapter 所需权限。
REVOKE ALL ON outbox_consumer_receipts FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON outbox_consumer_receipts TO ziji_app;

CREATE VIEW outbox_event_cleanup_eligibility AS
SELECT
    event.id AS outbox_event_id,
    COUNT(subscription.consumer_name)::integer AS required_subscription_count,
    COUNT(receipt.outbox_event_id)::integer AS terminal_receipt_count,
    COUNT(subscription.consumer_name) FILTER (WHERE receipt.outbox_event_id IS NULL)::integer
        AS missing_required_receipt_count,
    (COUNT(subscription.consumer_name) FILTER (WHERE receipt.outbox_event_id IS NULL) = 0)
        AS eligible_for_cleanup
FROM outbox_events event
LEFT JOIN outbox_consumer_subscriptions subscription
    ON subscription.aggregate_type = event.aggregate_type
    AND subscription.event_type = event.event_type
    AND subscription.required_for_cleanup
    AND subscription.subscribed_from <= event.occurred_at
    AND (subscription.subscribed_until IS NULL OR event.occurred_at < subscription.subscribed_until)
LEFT JOIN outbox_consumer_receipts receipt
    ON receipt.consumer_name = subscription.consumer_name
    AND receipt.outbox_event_id = event.id
    AND receipt.status IN ('SUCCEEDED', 'FAILED_FINAL')
GROUP BY event.id;

COMMENT ON VIEW outbox_event_cleanup_eligibility IS
    '清理资格只等待事件 occurred_at 时有效且 required 的每个订阅；缺少 receipt 或 receipt 非终态均不可清理。';

REVOKE ALL ON outbox_consumer_subscriptions FROM PUBLIC;
GRANT SELECT ON outbox_consumer_subscriptions TO ziji_app;
REVOKE ALL ON outbox_event_cleanup_eligibility FROM PUBLIC;
GRANT SELECT ON outbox_event_cleanup_eligibility TO ziji_app;

COMMENT ON COLUMN outbox_events.published_at IS
    '兼容性全局提示字段，不是任何单一消费者的完成状态；消费者必须使用 outbox_consumer_receipts。';
COMMENT ON COLUMN outbox_events.attempt_count IS
    '历史全局字段；多消费者模型不得用作消费者独立 attempt 事实。独立次数保存在 outbox_consumer_receipts。';
COMMENT ON COLUMN outbox_events.next_attempt_at IS
    '历史全局字段；多消费者模型不得用作消费者独立 retry/lease 时间。独立时间保存在 outbox_consumer_receipts。';
