-- Ziji V1: double-entry ledger facts, structured transaction details, and rebuildable balance projections.

CREATE TABLE ledger_accounts (
    id uuid PRIMARY KEY,
    visible_account_id uuid,
    owner_user_id uuid,
    code varchar(80) NOT NULL,
    ledger_role varchar(20) NOT NULL CHECK (ledger_role IN ('PRIMARY', 'POSITION_COST', 'SYSTEM')),
    account_nature varchar(12) NOT NULL
        CHECK (account_nature IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY', 'CLEARING')),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_ledger_accounts_visible_account FOREIGN KEY (visible_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_ledger_accounts_owner FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CHECK ((visible_account_id IS NOT NULL AND ledger_role IN ('PRIMARY', 'POSITION_COST'))
        OR (visible_account_id IS NULL AND owner_user_id IS NOT NULL AND ledger_role = 'SYSTEM'))
);

CREATE UNIQUE INDEX uq_ledger_accounts_visible_role
    ON ledger_accounts (visible_account_id, ledger_role) WHERE visible_account_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ledger_accounts_system
    ON ledger_accounts (owner_user_id, code, currency) WHERE visible_account_id IS NULL;

CREATE TABLE transactions (
    id uuid PRIMARY KEY,
    transaction_type varchar(32) NOT NULL
        CHECK (transaction_type IN ('OPENING', 'INCOME', 'EXPENSE', 'REFUND', 'TRANSFER', 'FX_TRANSFER',
            'ADJUSTMENT', 'INVESTMENT', 'REPAYMENT', 'INTEREST', 'REVERSAL')),
    status varchar(16) NOT NULL
        CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED', 'SUPERSEDED', 'DISCARDED')),
    business_at timestamptz NOT NULL,
    business_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    counterparty varchar(200),
    merchant varchar(200),
    note text,
    source varchar(24) NOT NULL
        CHECK (source IN ('MANUAL', 'IMPORT', 'RECURRING', 'INVESTMENT', 'ADJUSTMENT', 'SYNC')),
    client_operation_id uuid,
    idempotency_record_id uuid,
    root_transaction_id uuid NOT NULL,
    previous_version_id uuid,
    reversal_of_id uuid,
    version_no integer NOT NULL CHECK (version_no > 0),
    posted_at timestamptz,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    entity_version integer NOT NULL DEFAULT 1 CHECK (entity_version > 0),
    CONSTRAINT fk_transactions_idempotency FOREIGN KEY (idempotency_record_id) REFERENCES idempotency_records (id),
    CONSTRAINT fk_transactions_root FOREIGN KEY (root_transaction_id)
        REFERENCES transactions (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_transactions_previous FOREIGN KEY (previous_version_id)
        REFERENCES transactions (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_transactions_reversal FOREIGN KEY (reversal_of_id)
        REFERENCES transactions (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_transactions_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_transactions_updater FOREIGN KEY (updated_by) REFERENCES users (id),
    CONSTRAINT uq_transactions_root_version UNIQUE (root_transaction_id, version_no),
    CONSTRAINT uq_transactions_idempotency UNIQUE (idempotency_record_id),
    CHECK ((status IN ('DRAFT', 'DISCARDED') AND posted_at IS NULL)
        OR (status IN ('POSTED', 'REVERSED', 'SUPERSEDED') AND posted_at IS NOT NULL)),
    CHECK (reversal_of_id IS NULL OR transaction_type = 'REVERSAL'),
    CHECK (version_no > 1 OR previous_version_id IS NULL)
);

CREATE UNIQUE INDEX uq_transactions_reversal_of
    ON transactions (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE UNIQUE INDEX uq_transactions_client_operation
    ON transactions (created_by, client_operation_id) WHERE client_operation_id IS NOT NULL;
CREATE INDEX idx_transactions_business_date ON transactions (business_date, id);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY,
    transaction_id uuid NOT NULL,
    ledger_account_id uuid NOT NULL,
    sequence_no smallint NOT NULL CHECK (sequence_no > 0),
    direction char(1) NOT NULL CHECK (direction IN ('D', 'C')),
    amount numeric(24,8) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    business_date date NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id),
    CONSTRAINT uq_ledger_entries_sequence UNIQUE (transaction_id, sequence_no)
);

CREATE INDEX idx_entries_account_date
    ON ledger_entries (ledger_account_id, business_date, transaction_id);
CREATE INDEX idx_entries_transaction ON ledger_entries (transaction_id);

CREATE TABLE transfer_details (
    transaction_id uuid PRIMARY KEY,
    from_account_id uuid NOT NULL,
    to_account_id uuid NOT NULL,
    from_amount numeric(24,8) NOT NULL CHECK (from_amount > 0),
    to_amount numeric(24,8) NOT NULL CHECK (to_amount > 0),
    exchange_rate numeric(28,12) CHECK (exchange_rate > 0),
    fee_amount numeric(24,8) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    CONSTRAINT fk_transfer_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_transfer_from_account FOREIGN KEY (from_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transfer_to_account FOREIGN KEY (to_account_id) REFERENCES accounts (id),
    CHECK (from_account_id <> to_account_id)
);

CREATE TABLE refund_details (
    transaction_id uuid PRIMARY KEY,
    original_transaction_id uuid NOT NULL,
    category_id uuid,
    CONSTRAINT fk_refund_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_refund_original FOREIGN KEY (original_transaction_id) REFERENCES transactions (id),
    CHECK (transaction_id <> original_transaction_id)
);

CREATE TABLE balance_adjustment_details (
    transaction_id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    before_balance numeric(24,8) NOT NULL,
    actual_balance numeric(24,8) NOT NULL,
    difference_amount numeric(24,8) NOT NULL,
    reason varchar(500) NOT NULL,
    CONSTRAINT fk_adjustment_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_adjustment_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK (difference_amount = actual_balance - before_balance)
);

CREATE TABLE repayment_details (
    transaction_id uuid PRIMARY KEY,
    liability_account_id uuid NOT NULL,
    cash_account_id uuid NOT NULL,
    principal_amount numeric(24,8) NOT NULL CHECK (principal_amount > 0),
    interest_amount numeric(24,8) NOT NULL DEFAULT 0 CHECK (interest_amount >= 0),
    fee_amount numeric(24,8) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    CONSTRAINT fk_repayment_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_repayment_liability FOREIGN KEY (liability_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_repayment_cash FOREIGN KEY (cash_account_id) REFERENCES accounts (id),
    CHECK (liability_account_id <> cash_account_id)
);

CREATE TABLE account_balance_snapshots (
    ledger_account_id uuid NOT NULL,
    business_date date NOT NULL,
    balance numeric(24,8) NOT NULL,
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    as_of_change_sequence bigint NOT NULL CHECK (as_of_change_sequence >= 0),
    calculated_at timestamptz NOT NULL,
    PRIMARY KEY (ledger_account_id, business_date),
    CONSTRAINT fk_balance_snapshots_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts (id)
);

CREATE TABLE account_liquidity_snapshots (
    account_id uuid NOT NULL,
    business_date date NOT NULL,
    ledger_balance numeric(24,8) NOT NULL,
    unavailable_amount numeric(24,8) NOT NULL CHECK (unavailable_amount >= 0),
    available_balance numeric(24,8) NOT NULL,
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    as_of_change_sequence bigint NOT NULL CHECK (as_of_change_sequence >= 0),
    calculated_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, business_date),
    CONSTRAINT fk_liquidity_snapshots_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK (available_balance = ledger_balance - unavailable_amount)
);
