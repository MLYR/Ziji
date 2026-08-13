-- Ziji V1: categories, tags, streamed import metadata, and shared asynchronous job resources.

CREATE TABLE categories (
    id uuid PRIMARY KEY,
    owner_user_id uuid,
    account_id uuid,
    category_type varchar(12) NOT NULL CHECK (category_type IN ('INCOME', 'EXPENSE')),
    parent_id uuid,
    name varchar(80) NOT NULL,
    name_normalized varchar(80) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'MERGED')),
    merged_into_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_categories_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_categories_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT fk_categories_merged_into FOREIGN KEY (merged_into_id) REFERENCES categories (id),
    CHECK (owner_user_id IS NOT NULL OR account_id IS NULL),
    CHECK ((status = 'MERGED') = (merged_into_id IS NOT NULL)),
    CHECK (parent_id IS NULL OR id <> parent_id),
    CHECK (merged_into_id IS NULL OR id <> merged_into_id)
);

CREATE UNIQUE INDEX uq_categories_scope_name
    ON categories (COALESCE(owner_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(account_id, '00000000-0000-0000-0000-000000000000'::uuid),
        category_type,
        COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
        name_normalized);

ALTER TABLE refund_details
    ADD CONSTRAINT fk_refund_category FOREIGN KEY (category_id) REFERENCES categories (id);

CREATE TABLE tags (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL,
    name varchar(80) NOT NULL,
    name_normalized varchar(80) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_tags_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT uq_tags_owner_name UNIQUE (owner_user_id, name_normalized)
);

CREATE TABLE transaction_tags (
    transaction_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    PRIMARY KEY (transaction_id, tag_id),
    CONSTRAINT fk_transaction_tags_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_transaction_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
);

CREATE TABLE transaction_categories (
    transaction_id uuid NOT NULL,
    category_id uuid NOT NULL,
    role varchar(12) NOT NULL DEFAULT 'PRIMARY' CHECK (role IN ('PRIMARY', 'ORIGINAL')),
    PRIMARY KEY (transaction_id, role),
    CONSTRAINT fk_transaction_categories_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_transaction_categories_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE async_jobs (
    id uuid PRIMARY KEY,
    owner_user_id uuid,
    job_type varchar(32) NOT NULL
        CHECK (job_type IN ('IMPORT_PARSE', 'IMPORT_CONFIRM', 'DATA_EXPORT', 'STATISTICS_REBUILD')),
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    resource_type varchar(50),
    resource_id uuid,
    progress_completed bigint CHECK (progress_completed >= 0),
    progress_total bigint CHECK (progress_total >= 0),
    error_code varchar(80),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_async_jobs_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CHECK ((resource_type IS NULL) = (resource_id IS NULL)),
    CHECK (progress_total IS NULL OR progress_completed IS NULL OR progress_completed <= progress_total),
    CHECK (completed_at IS NULL OR started_at IS NOT NULL)
);

CREATE INDEX idx_async_jobs_owner_status ON async_jobs (owner_user_id, status, created_at DESC);

CREATE TABLE import_batches (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    source_type varchar(24) NOT NULL
        CHECK (source_type IN ('WECHAT', 'ALIPAY', 'BANK_CSV', 'BANK_EXCEL', 'GENERIC_CSV')),
    status varchar(20) NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED', 'PARSING', 'REVIEW', 'CONFIRMING', 'COMPLETED', 'FAILED', 'REVERSED')),
    object_key varchar(500) NOT NULL,
    file_sha256 char(64) NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    mapping_config jsonb,
    total_rows integer NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    success_rows integer NOT NULL DEFAULT 0 CHECK (success_rows >= 0),
    failed_rows integer NOT NULL DEFAULT 0 CHECK (failed_rows >= 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_import_batches_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_import_batches_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CHECK (success_rows + failed_rows <= total_rows)
);

CREATE INDEX idx_import_batches_file
    ON import_batches (created_by, account_id, file_sha256);

CREATE TABLE import_rows (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL,
    row_no integer NOT NULL CHECK (row_no > 0),
    raw_data jsonb NOT NULL,
    normalized_data jsonb NOT NULL,
    external_transaction_id varchar(200),
    fingerprint char(64) CHECK (fingerprint IS NULL OR fingerprint ~ '^[0-9a-f]{64}$'),
    duplicate_type varchar(12) NOT NULL DEFAULT 'NONE'
        CHECK (duplicate_type IN ('NONE', 'EXACT', 'SUSPECTED')),
    decision varchar(12) NOT NULL DEFAULT 'PENDING'
        CHECK (decision IN ('PENDING', 'SKIP', 'IMPORT', 'MERGE')),
    transaction_id uuid,
    error_code varchar(80),
    error_message text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_import_rows_batch FOREIGN KEY (batch_id) REFERENCES import_batches (id),
    CONSTRAINT fk_import_rows_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT uq_import_rows_batch_row UNIQUE (batch_id, row_no)
);

CREATE TABLE external_transaction_keys (
    source_type varchar(24) NOT NULL,
    account_id uuid NOT NULL,
    external_transaction_id varchar(200) NOT NULL,
    transaction_id uuid NOT NULL,
    PRIMARY KEY (source_type, account_id, external_transaction_id),
    CONSTRAINT fk_external_keys_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_external_keys_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE TABLE data_exports (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    job_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'GENERATING', 'READY', 'FAILED', 'EXPIRED')),
    object_key varchar(500),
    file_sha256 char(64) CHECK (file_sha256 IS NULL OR file_sha256 ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_data_exports_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_data_exports_job FOREIGN KEY (job_id) REFERENCES async_jobs (id),
    CONSTRAINT uq_data_exports_job UNIQUE (job_id),
    CHECK ((status = 'READY' AND object_key IS NOT NULL AND expires_at IS NOT NULL) OR status <> 'READY')
);

CREATE TABLE account_closure_requests (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'COOLING_OFF'
        CHECK (status IN ('COOLING_OFF', 'APPROVED', 'CANCELED', 'COMPLETED', 'BLOCKED')),
    requested_at timestamptz NOT NULL,
    cooling_off_until timestamptz NOT NULL,
    canceled_at timestamptz,
    completed_at timestamptz,
    impact_snapshot jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_closure_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CHECK (cooling_off_until > requested_at),
    CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_closure_requests_active_user
    ON account_closure_requests (user_id)
    WHERE status IN ('COOLING_OFF', 'APPROVED', 'BLOCKED');
