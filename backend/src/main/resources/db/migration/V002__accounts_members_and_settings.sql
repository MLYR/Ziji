-- Ziji V1: visible accounts, liabilities, historical memberships, inclusion settings, and liquidity holds.

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    account_class varchar(20) NOT NULL
        CHECK (account_class IN ('ASSET', 'INVESTMENT', 'LIABILITY')),
    account_type varchar(40) NOT NULL
        CHECK (account_type IN ('BANK', 'WECHAT', 'ALIPAY', 'CASH', 'BROKERAGE', 'CREDIT_CARD', 'LOAN', 'OTHER')),
    name varchar(120) NOT NULL,
    institution varchar(160),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    note text,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    archived_at timestamptz,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_accounts_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);

CREATE INDEX idx_accounts_created_by_status ON accounts (created_by, status);
CREATE INDEX idx_accounts_class_type ON accounts (account_class, account_type);

CREATE TABLE liability_details (
    account_id uuid PRIMARY KEY,
    interest_rate numeric(12,8) CHECK (interest_rate >= 0),
    loan_date date,
    due_date date,
    billing_day smallint CHECK (billing_day BETWEEN 1 AND 31),
    repayment_day smallint CHECK (repayment_day BETWEEN 1 AND 31),
    current_amount_due numeric(24,8) CHECK (current_amount_due >= 0),
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_liability_details_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK (due_date IS NULL OR loan_date IS NULL OR due_date >= loan_date)
);

CREATE TABLE account_members (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role varchar(10) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    joined_at timestamptz NOT NULL,
    ended_at timestamptz,
    membership_no integer NOT NULL CHECK (membership_no > 0),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_account_members_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_account_members_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_account_members_cycle UNIQUE (account_id, user_id, membership_no),
    CHECK ((status = 'ACTIVE' AND ended_at IS NULL) OR (status <> 'ACTIVE' AND ended_at IS NOT NULL)),
    CHECK (ended_at IS NULL OR ended_at >= joined_at)
);

CREATE UNIQUE INDEX uq_account_members_active
    ON account_members (account_id, user_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_account_members_user_status
    ON account_members (user_id, status, account_id);

CREATE TABLE account_invitations (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    invited_email_normalized varchar(320) NOT NULL,
    invited_user_id uuid,
    role varchar(10) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    status varchar(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'REVOKED', 'EXPIRED')),
    token_hash varchar(500) NOT NULL,
    expires_at timestamptz NOT NULL,
    invited_by uuid NOT NULL,
    responded_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_account_invitations_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_account_invitations_user FOREIGN KEY (invited_user_id) REFERENCES users (id),
    CONSTRAINT fk_account_invitations_inviter FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT uq_account_invitations_token UNIQUE (token_hash),
    CHECK (expires_at > created_at),
    CHECK ((status = 'PENDING' AND responded_at IS NULL) OR (status <> 'PENDING' AND responded_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_account_invitations_pending_email
    ON account_invitations (account_id, invited_email_normalized) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_account_invitations_pending_user
    ON account_invitations (account_id, invited_user_id)
    WHERE status = 'PENDING' AND invited_user_id IS NOT NULL;

CREATE TABLE account_inclusion_settings (
    id uuid PRIMARY KEY,
    membership_id uuid NOT NULL,
    included boolean NOT NULL,
    ratio numeric(9,6) NOT NULL CHECK (ratio BETWEEN 0 AND 1),
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_inclusion_membership FOREIGN KEY (membership_id) REFERENCES account_members (id),
    CONSTRAINT fk_inclusion_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK (included OR ratio = 0)
);

ALTER TABLE account_inclusion_settings
    ADD CONSTRAINT ex_inclusion_period_no_overlap
    EXCLUDE USING gist (
        membership_id WITH =,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    );

CREATE UNIQUE INDEX uq_inclusion_current_membership
    ON account_inclusion_settings (membership_id) WHERE valid_to IS NULL;

CREATE TABLE liquidity_holds (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    hold_type varchar(16) NOT NULL CHECK (hold_type IN ('FROZEN', 'IN_TRANSIT', 'RESERVED')),
    amount numeric(24,8) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    effective_at timestamptz NOT NULL,
    expires_at timestamptz,
    released_at timestamptz,
    source varchar(24) NOT NULL CHECK (source IN ('MANUAL', 'IMPORT', 'SYSTEM')),
    note text,
    root_hold_id uuid NOT NULL,
    previous_revision_id uuid,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    ended_at timestamptz,
    end_reason varchar(12) CHECK (end_reason IN ('RELEASED', 'SUPERSEDED', 'EXPIRED')),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT fk_liquidity_holds_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_liquidity_holds_root FOREIGN KEY (root_hold_id)
        REFERENCES liquidity_holds (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_liquidity_holds_previous FOREIGN KEY (previous_revision_id)
        REFERENCES liquidity_holds (id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_liquidity_holds_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_liquidity_holds_revision UNIQUE (root_hold_id, revision_no),
    CONSTRAINT uq_liquidity_holds_previous UNIQUE (previous_revision_id),
    CHECK (expires_at IS NULL OR expires_at > effective_at),
    CHECK ((ended_at IS NULL AND end_reason IS NULL) OR (ended_at IS NOT NULL AND end_reason IS NOT NULL)),
    CHECK (released_at IS NULL OR end_reason = 'RELEASED'),
    CHECK (revision_no > 1 OR previous_revision_id IS NULL)
);

CREATE UNIQUE INDEX uq_liquidity_holds_current
    ON liquidity_holds (root_hold_id) WHERE ended_at IS NULL;
CREATE INDEX idx_liquidity_holds_account_effective
    ON liquidity_holds (account_id, effective_at, ended_at);
