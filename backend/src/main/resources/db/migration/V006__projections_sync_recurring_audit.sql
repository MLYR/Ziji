-- Ziji V1: versioned Dashboard projections, directed synchronization, recurring rules, outbox, and audit.

CREATE TABLE daily_user_asset_snapshots (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    business_date date NOT NULL,
    base_currency char(3) NOT NULL CHECK (base_currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    total_assets numeric(24,8) NOT NULL,
    available_funds numeric(24,8) NOT NULL,
    investment_assets numeric(24,8) NOT NULL,
    total_liabilities numeric(24,8) NOT NULL,
    net_assets numeric(24,8) NOT NULL,
    income_effect numeric(24,8) NOT NULL,
    expense_effect numeric(24,8) NOT NULL,
    market_effect numeric(24,8) NOT NULL,
    fx_effect numeric(24,8) NOT NULL,
    adjustment_effect numeric(24,8) NOT NULL,
    inclusion_effect numeric(24,8) NOT NULL,
    -- Completeness metadata lets calendar APIs distinguish real zero values from incomplete valuation.
    valuation_status varchar(16) NOT NULL CHECK (valuation_status IN ('CALCULATED', 'PARTIAL', 'UNAVAILABLE')),
    included_asset_count integer NOT NULL CHECK (included_asset_count >= 0),
    included_liability_count integer NOT NULL CHECK (included_liability_count >= 0),
    missing_valuation_count integer NOT NULL CHECK (missing_valuation_count >= 0),
    valuation_revision integer NOT NULL CHECK (valuation_revision > 0),
    is_current boolean NOT NULL DEFAULT true,
    supersedes_snapshot_id uuid,
    as_of_change_sequence bigint NOT NULL CHECK (as_of_change_sequence >= 0),
    calculated_at timestamptz NOT NULL,
    CONSTRAINT fk_daily_assets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_daily_assets_supersedes FOREIGN KEY (supersedes_snapshot_id)
        REFERENCES daily_user_asset_snapshots (id),
    CONSTRAINT uq_daily_assets_revision
        UNIQUE (user_id, business_date, base_currency, valuation_revision),
    CONSTRAINT uq_daily_assets_supersedes UNIQUE (supersedes_snapshot_id),
    CHECK (net_assets = total_assets - total_liabilities),
    CHECK (valuation_status = 'CALCULATED' OR missing_valuation_count > 0),
    CHECK (valuation_revision > 1 OR supersedes_snapshot_id IS NULL)
);

CREATE UNIQUE INDEX uq_daily_assets_current
    ON daily_user_asset_snapshots (user_id, business_date, base_currency) WHERE is_current;

-- Rebuildable daily return calendar projection for the user's whole portfolio or one instrument.
CREATE TABLE investment_daily_return_snapshots (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('PORTFOLIO', 'INSTRUMENT')),
    instrument_id uuid,
    business_date date NOT NULL,
    base_currency char(3) NOT NULL CHECK (base_currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    status varchar(20) NOT NULL CHECK (status IN (
        'CALCULATED', 'NON_TRADING_DAY', 'NO_POSITION', 'PENDING_DATA', 'PARTIAL', 'UNPRICED'
    )),
    begin_value numeric(24,8),
    end_value numeric(24,8),
    net_cash_flow numeric(24,8),
    daily_profit numeric(24,8),
    daily_return_rate numeric(18,10),
    missing_instrument_count integer NOT NULL DEFAULT 0 CHECK (missing_instrument_count >= 0),
    valuation_revision integer NOT NULL CHECK (valuation_revision > 0),
    is_current boolean NOT NULL DEFAULT true,
    supersedes_snapshot_id uuid,
    as_of_change_sequence bigint NOT NULL CHECK (as_of_change_sequence >= 0),
    calculated_at timestamptz NOT NULL,
    CONSTRAINT fk_investment_daily_return_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_investment_daily_return_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT fk_investment_daily_return_supersedes FOREIGN KEY (supersedes_snapshot_id)
        REFERENCES investment_daily_return_snapshots (id),
    CONSTRAINT uq_investment_daily_return_revision
        UNIQUE NULLS NOT DISTINCT (
            user_id, scope_type, instrument_id, business_date, base_currency, valuation_revision
        ),
    CONSTRAINT uq_investment_daily_return_supersedes UNIQUE (supersedes_snapshot_id),
    CHECK ((scope_type = 'PORTFOLIO' AND instrument_id IS NULL)
        OR (scope_type = 'INSTRUMENT' AND instrument_id IS NOT NULL)),
    CHECK ((status = 'CALCULATED'
            AND begin_value IS NOT NULL
            AND end_value IS NOT NULL
            AND net_cash_flow IS NOT NULL
            AND daily_profit IS NOT NULL)
        OR (status <> 'CALCULATED'
            AND daily_profit IS NULL
            AND daily_return_rate IS NULL)),
    CHECK (status NOT IN ('PARTIAL', 'UNPRICED') OR missing_instrument_count > 0),
    CHECK (valuation_revision > 1 OR supersedes_snapshot_id IS NULL)
);

CREATE UNIQUE INDEX uq_investment_daily_return_current
    ON investment_daily_return_snapshots (
        user_id, scope_type, instrument_id, business_date, base_currency
    ) NULLS NOT DISTINCT WHERE is_current;

-- A regular lookup index already stores NULL instrument_id values; null-equality syntax is only needed above for uniqueness.
CREATE INDEX idx_investment_daily_return_calendar
    ON investment_daily_return_snapshots (
        user_id, scope_type, instrument_id, base_currency, business_date
    );

CREATE TABLE sync_operations (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    device_id varchar(200) NOT NULL,
    idempotency_record_id uuid NOT NULL,
    entity_type varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    operation_type varchar(20) NOT NULL
        CHECK (operation_type IN ('CREATE', 'UPDATE', 'REVERSE', 'ARCHIVE')),
    base_version integer CHECK (base_version > 0),
    status varchar(16) NOT NULL CHECK (status IN ('APPLIED', 'DUPLICATE', 'CONFLICT', 'REJECTED')),
    result_ref jsonb,
    processed_at timestamptz NOT NULL,
    CONSTRAINT fk_sync_operations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_sync_operations_idempotency FOREIGN KEY (idempotency_record_id) REFERENCES idempotency_records (id),
    CONSTRAINT uq_sync_operations_idempotency UNIQUE (idempotency_record_id)
);

CREATE TABLE change_log (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type varchar(40) NOT NULL,
    entity_id uuid NOT NULL,
    entity_version integer NOT NULL CHECK (entity_version > 0),
    change_type varchar(16) NOT NULL
        CHECK (change_type IN ('UPSERT', 'TOMBSTONE', 'ACCESS_REVOKED', 'BOOTSTRAP')),
    recipient_user_id uuid NOT NULL,
    account_id uuid,
    changed_at timestamptz NOT NULL,
    payload_version smallint NOT NULL DEFAULT 1 CHECK (payload_version > 0),
    payload jsonb,
    CONSTRAINT fk_change_log_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    CONSTRAINT fk_change_log_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uq_change_log_delivery
        UNIQUE (recipient_user_id, entity_type, entity_id, entity_version, change_type)
);

CREATE INDEX idx_change_log_recipient ON change_log (recipient_user_id, sequence);

CREATE TABLE sync_device_cursors (
    user_id uuid NOT NULL,
    device_id varchar(200) NOT NULL,
    last_ack_sequence bigint NOT NULL CHECK (last_ack_sequence >= 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, device_id),
    CONSTRAINT fk_sync_cursors_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE recurring_rules (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    account_id uuid NOT NULL,
    transaction_template jsonb NOT NULL,
    template_schema_version smallint NOT NULL DEFAULT 1 CHECK (template_schema_version = 1),
    frequency varchar(12) NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    interval_value integer NOT NULL CHECK (interval_value > 0),
    starts_on date NOT NULL,
    ends_on date,
    next_occurrence_on date NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_recurring_rules_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_recurring_rules_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CHECK (next_occurrence_on >= starts_on)
);

CREATE INDEX idx_recurring_rules_due
    ON recurring_rules (next_occurrence_on) WHERE status = 'ACTIVE';

CREATE TABLE recurring_occurrences (
    id uuid PRIMARY KEY,
    rule_id uuid NOT NULL,
    scheduled_date date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING_CONFIRMATION'
        CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'SKIPPED', 'EXPIRED')),
    transaction_id uuid,
    created_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT fk_occurrences_rule FOREIGN KEY (rule_id) REFERENCES recurring_rules (id),
    CONSTRAINT fk_occurrences_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT uq_occurrences_rule_date UNIQUE (rule_id, scheduled_date),
    CHECK ((status = 'PENDING_CONFIRMATION' AND decided_at IS NULL AND transaction_id IS NULL)
        OR (status = 'CONFIRMED' AND decided_at IS NOT NULL AND transaction_id IS NOT NULL)
        OR (status IN ('SKIPPED', 'EXPIRED') AND decided_at IS NOT NULL AND transaction_id IS NULL))
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_type varchar(50) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    payload_version smallint NOT NULL DEFAULT 1 CHECK (payload_version > 0),
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (next_attempt_at) WHERE published_at IS NULL;

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor_user_id uuid,
    actor_type varchar(12) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    action varchar(80) NOT NULL,
    resource_type varchar(50) NOT NULL,
    resource_id uuid NOT NULL,
    account_id uuid,
    request_id varchar(100) NOT NULL,
    result varchar(12) NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    reason_code varchar(60),
    metadata jsonb,
    previous_hash char(64) CHECK (previous_hash IS NULL OR previous_hash ~ '^[0-9a-f]{64}$'),
    record_hash char(64) CHECK (record_hash IS NULL OR record_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK ((actor_type = 'USER' AND actor_user_id IS NOT NULL) OR actor_type = 'SYSTEM')
);

CREATE INDEX idx_audit_resource ON audit_logs (resource_type, resource_id, occurred_at);

CREATE TABLE scheduled_job_runs (
    id uuid PRIMARY KEY,
    job_name varchar(100) NOT NULL,
    scheduled_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_code varchar(80),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uq_scheduled_job_run UNIQUE (job_name, scheduled_at),
    CHECK (completed_at IS NULL OR completed_at >= started_at)
);
