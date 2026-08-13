-- Ziji V1: investment instruments, trades, versioned Tushare/manual prices, exchange rates, and positions.

CREATE TABLE instruments (
    id uuid PRIMARY KEY,
    instrument_type varchar(12) NOT NULL CHECK (instrument_type IN ('STOCK', 'FUND', 'ETF', 'OTHER')),
    name varchar(200) NOT NULL,
    market varchar(40) NOT NULL,
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELISTED', 'INACTIVE')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX idx_instruments_search ON instruments (market, instrument_type, status);

CREATE TABLE instrument_source_mappings (
    id uuid PRIMARY KEY,
    instrument_id uuid NOT NULL,
    source varchar(20) NOT NULL CHECK (source IN ('TUSHARE', 'MANUAL')),
    external_code varchar(80) NOT NULL,
    source_market varchar(40),
    raw_metadata jsonb,
    last_synced_at timestamptz,
    CONSTRAINT fk_instrument_mappings_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT uq_instrument_mappings_external UNIQUE (source, external_code),
    CONSTRAINT uq_instrument_mappings_source UNIQUE (instrument_id, source)
);

CREATE TABLE trades (
    id uuid PRIMARY KEY,
    transaction_id uuid NOT NULL,
    investment_account_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    side varchar(12) NOT NULL CHECK (side IN ('BUY', 'SELL', 'DIVIDEND')),
    quantity numeric(28,12),
    unit_price numeric(28,12),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    gross_amount numeric(24,8) NOT NULL CHECK (gross_amount >= 0),
    fee_amount numeric(24,8) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    tax_amount numeric(24,8) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    trade_at timestamptz NOT NULL,
    CONSTRAINT fk_trades_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_trades_account FOREIGN KEY (investment_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_trades_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT uq_trades_transaction UNIQUE (transaction_id),
    CHECK ((side IN ('BUY', 'SELL') AND quantity > 0 AND unit_price > 0 AND gross_amount > 0)
        OR (side = 'DIVIDEND' AND quantity IS NULL AND unit_price IS NULL AND gross_amount > 0))
);

CREATE INDEX idx_trades_account_date ON trades (investment_account_id, trade_at DESC);
CREATE INDEX idx_trades_instrument_date ON trades (instrument_id, trade_at DESC);

CREATE TABLE price_snapshots (
    id uuid PRIMARY KEY,
    instrument_id uuid NOT NULL,
    source varchar(20) NOT NULL CHECK (source IN ('TUSHARE', 'MANUAL')),
    price_type varchar(20) NOT NULL CHECK (price_type IN ('CLOSE', 'UNIT_NAV', 'ADJUSTED_CLOSE', 'MANUAL')),
    business_date date NOT NULL,
    price numeric(28,12) NOT NULL CHECK (price > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    source_updated_at timestamptz,
    fetched_at timestamptz NOT NULL,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    is_current boolean NOT NULL DEFAULT true,
    supersedes_id uuid,
    created_by uuid,
    reason varchar(500),
    raw_payload_hash char(64) CHECK (raw_payload_hash IS NULL OR raw_payload_hash ~ '^[0-9a-f]{64}$'),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_prices_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CONSTRAINT fk_prices_supersedes FOREIGN KEY (supersedes_id) REFERENCES price_snapshots (id),
    CONSTRAINT fk_prices_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_prices_revision
        UNIQUE (instrument_id, source, price_type, business_date, revision_no),
    CONSTRAINT uq_prices_content
        UNIQUE (instrument_id, source, price_type, business_date, content_hash),
    CONSTRAINT uq_prices_supersedes UNIQUE (supersedes_id),
    CHECK ((source = 'MANUAL' AND created_by IS NOT NULL AND reason IS NOT NULL)
        OR source <> 'MANUAL'),
    CHECK (revision_no > 1 OR supersedes_id IS NULL)
);

CREATE UNIQUE INDEX uq_prices_current
    ON price_snapshots (instrument_id, source, price_type, business_date) WHERE is_current;
CREATE INDEX idx_prices_latest
    ON price_snapshots (instrument_id, price_type, business_date DESC) WHERE is_current;

CREATE TABLE position_snapshots (
    investment_account_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    business_date date NOT NULL,
    quantity numeric(28,12) NOT NULL,
    cost_basis numeric(24,8) NOT NULL,
    average_cost numeric(28,12) NOT NULL,
    as_of_change_sequence bigint NOT NULL CHECK (as_of_change_sequence >= 0),
    calculated_at timestamptz NOT NULL,
    PRIMARY KEY (investment_account_id, instrument_id, business_date),
    CONSTRAINT fk_positions_account FOREIGN KEY (investment_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_positions_instrument FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    CHECK (quantity >= 0),
    CHECK (cost_basis >= 0),
    CHECK (average_cost >= 0)
);

CREATE INDEX idx_positions_current
    ON position_snapshots (investment_account_id, instrument_id, business_date DESC);

CREATE TABLE exchange_rate_snapshots (
    id uuid PRIMARY KEY,
    base_currency char(3) NOT NULL CHECK (base_currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    quote_currency char(3) NOT NULL CHECK (quote_currency IN ('CNY', 'USD', 'HKD', 'JPY', 'EUR')),
    rate numeric(28,12) NOT NULL CHECK (rate > 0),
    business_at timestamptz NOT NULL,
    business_date date NOT NULL,
    source varchar(20) NOT NULL,
    fetched_at timestamptz NOT NULL,
    is_manual boolean NOT NULL,
    revision_no integer NOT NULL CHECK (revision_no > 0),
    is_current boolean NOT NULL DEFAULT true,
    supersedes_id uuid,
    created_by uuid,
    reason varchar(500),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_rates_supersedes FOREIGN KEY (supersedes_id) REFERENCES exchange_rate_snapshots (id),
    CONSTRAINT fk_rates_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uq_rates_revision
        UNIQUE (base_currency, quote_currency, source, business_at, revision_no),
    CONSTRAINT uq_rates_content
        UNIQUE (base_currency, quote_currency, source, business_at, content_hash),
    CONSTRAINT uq_rates_supersedes UNIQUE (supersedes_id),
    CHECK (base_currency <> quote_currency),
    CHECK ((is_manual AND source = 'MANUAL' AND created_by IS NOT NULL AND reason IS NOT NULL)
        OR (NOT is_manual AND source <> 'MANUAL')),
    CHECK (revision_no > 1 OR supersedes_id IS NULL)
);

CREATE UNIQUE INDEX uq_rates_current
    ON exchange_rate_snapshots (base_currency, quote_currency, source, business_at) WHERE is_current;
CREATE INDEX idx_rates_latest
    ON exchange_rate_snapshots (base_currency, quote_currency, business_date DESC) WHERE is_current;
