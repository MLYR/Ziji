-- Ziji V1: cross-table financial invariants, deferred aggregate checks, immutable facts, and seed data.

-- Return the number of decimal places accepted for a V1 currency.
CREATE OR REPLACE FUNCTION currency_minor_units(p_currency char(3))
RETURNS integer
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
    SELECT CASE p_currency
        WHEN 'JPY' THEN 0
        WHEN 'CNY' THEN 2
        WHEN 'USD' THEN 2
        WHEN 'HKD' THEN 2
        WHEN 'EUR' THEN 2
        ELSE NULL
    END
$$;

-- Validate one or more numeric columns named in TG_ARGV against the row's currency column.
CREATE OR REPLACE FUNCTION enforce_currency_precision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    row_json jsonb := to_jsonb(NEW);
    currency_value char(3);
    column_name text;
    numeric_value numeric;
BEGIN
    currency_value := (row_json ->> TG_ARGV[0])::char(3);
    IF currency_minor_units(currency_value) IS NULL THEN
        RAISE EXCEPTION 'unsupported currency: %', currency_value USING ERRCODE = '23514';
    END IF;

    FOREACH column_name IN ARRAY TG_ARGV[1:array_length(TG_ARGV, 1)] LOOP
        IF row_json ->> column_name IS NOT NULL THEN
            numeric_value := (row_json ->> column_name)::numeric;
            IF numeric_value <> round(numeric_value, currency_minor_units(currency_value)) THEN
                RAISE EXCEPTION '% exceeds minor-unit precision for %', column_name, currency_value
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ledger_entries_currency_precision
BEFORE INSERT OR UPDATE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision('currency', 'amount');

CREATE TRIGGER trg_liquidity_holds_currency_precision
BEFORE INSERT OR UPDATE ON liquidity_holds
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision('currency', 'amount');

CREATE TRIGGER trg_trades_currency_precision
BEFORE INSERT OR UPDATE ON trades
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision('currency', 'gross_amount', 'fee_amount', 'tax_amount');

-- Amount projections must also be rounded once to the projection currency.
CREATE TRIGGER trg_account_balances_currency_precision
BEFORE INSERT OR UPDATE ON account_balance_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision('currency', 'balance');

CREATE TRIGGER trg_account_liquidity_currency_precision
BEFORE INSERT OR UPDATE ON account_liquidity_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision(
    'currency', 'ledger_balance', 'unavailable_amount', 'available_balance');

CREATE TRIGGER trg_daily_assets_currency_precision
BEFORE INSERT OR UPDATE ON daily_user_asset_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision(
    'base_currency', 'total_assets', 'available_funds', 'investment_assets', 'total_liabilities',
    'net_assets', 'income_effect', 'expense_effect', 'market_effect', 'fx_effect',
    'adjustment_effect', 'inclusion_effect');

CREATE TRIGGER trg_investment_daily_return_currency_precision
BEFORE INSERT OR UPDATE ON investment_daily_return_snapshots
FOR EACH ROW EXECUTE FUNCTION enforce_currency_precision(
    'base_currency', 'begin_value', 'end_value', 'net_cash_flow', 'daily_profit');

-- The application supplies account currency to detail commands; these tables derive it from visible accounts.
CREATE OR REPLACE FUNCTION enforce_account_amount_precision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    account_currency char(3);
    row_json jsonb := to_jsonb(NEW);
    account_column text := TG_ARGV[0];
    column_name text;
    numeric_value numeric;
BEGIN
    SELECT currency INTO account_currency
    FROM accounts
    WHERE id = (row_json ->> account_column)::uuid;

    IF account_currency IS NULL THEN
        RAISE EXCEPTION 'account referenced by % does not exist', account_column USING ERRCODE = '23503';
    END IF;

    FOREACH column_name IN ARRAY TG_ARGV[1:array_length(TG_ARGV, 1)] LOOP
        IF row_json ->> column_name IS NOT NULL THEN
            numeric_value := (row_json ->> column_name)::numeric;
            IF numeric_value <> round(numeric_value, currency_minor_units(account_currency)) THEN
                RAISE EXCEPTION '% exceeds minor-unit precision for %', column_name, account_currency
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_liability_amount_precision
BEFORE INSERT OR UPDATE ON liability_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision('account_id', 'current_amount_due');

CREATE TRIGGER trg_adjustment_amount_precision
BEFORE INSERT OR UPDATE ON balance_adjustment_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision(
    'account_id', 'before_balance', 'actual_balance', 'difference_amount');

-- Transfer amounts follow each side's account currency; the fee is charged in the source currency.
CREATE TRIGGER trg_transfer_from_amount_precision
BEFORE INSERT OR UPDATE ON transfer_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision(
    'from_account_id', 'from_amount', 'fee_amount');

CREATE TRIGGER trg_transfer_to_amount_precision
BEFORE INSERT OR UPDATE ON transfer_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision('to_account_id', 'to_amount');

-- Repayment principal follows the liability currency; interest and fee leave the cash account.
CREATE TRIGGER trg_repayment_principal_precision
BEFORE INSERT OR UPDATE ON repayment_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision(
    'liability_account_id', 'principal_amount');

CREATE TRIGGER trg_repayment_cash_precision
BEFORE INSERT OR UPDATE ON repayment_details
FOR EACH ROW EXECUTE FUNCTION enforce_account_amount_precision(
    'cash_account_id', 'interest_amount', 'fee_amount');

CREATE OR REPLACE FUNCTION validate_repayment_currencies()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    liability_currency char(3);
    cash_currency char(3);
BEGIN
    SELECT currency INTO liability_currency FROM accounts WHERE id = NEW.liability_account_id;
    SELECT currency INTO cash_currency FROM accounts WHERE id = NEW.cash_account_id;
    IF liability_currency <> cash_currency THEN
        RAISE EXCEPTION 'V1 liability repayment accounts must use the same currency' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_repayment_currencies
BEFORE INSERT OR UPDATE ON repayment_details
FOR EACH ROW EXECUTE FUNCTION validate_repayment_currencies();

-- Enforce visible account class/currency against its ledger account role.
CREATE OR REPLACE FUNCTION validate_ledger_account_mapping()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    visible_class varchar(20);
    visible_currency char(3);
BEGIN
    IF NEW.visible_account_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT account_class, currency INTO visible_class, visible_currency
    FROM accounts WHERE id = NEW.visible_account_id;

    IF NEW.currency <> visible_currency THEN
        RAISE EXCEPTION 'ledger currency must equal visible account currency' USING ERRCODE = '23514';
    END IF;
    IF NEW.ledger_role = 'PRIMARY'
       AND ((visible_class IN ('ASSET', 'INVESTMENT') AND NEW.account_nature <> 'ASSET')
         OR (visible_class = 'LIABILITY' AND NEW.account_nature <> 'LIABILITY')) THEN
        RAISE EXCEPTION 'PRIMARY ledger nature is inconsistent with account class' USING ERRCODE = '23514';
    END IF;
    IF NEW.ledger_role = 'POSITION_COST'
       AND (visible_class <> 'INVESTMENT' OR NEW.account_nature <> 'ASSET') THEN
        RAISE EXCEPTION 'POSITION_COST is only valid for investment accounts' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ledger_account_mapping
BEFORE INSERT OR UPDATE ON ledger_accounts
FOR EACH ROW EXECUTE FUNCTION validate_ledger_account_mapping();

-- Liquidity holds and investment trades inherit their visible account class and currency.
CREATE OR REPLACE FUNCTION validate_visible_account_dependent()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_account_id uuid;
    expected_class varchar(20);
    expected_currency char(3);
BEGIN
    IF TG_TABLE_NAME = 'liquidity_holds' THEN
        target_account_id := NEW.account_id;
    ELSE
        target_account_id := NEW.investment_account_id;
    END IF;
    SELECT account_class, currency INTO expected_class, expected_currency
    FROM accounts WHERE id = target_account_id;
    IF NEW.currency <> expected_currency THEN
        RAISE EXCEPTION '% currency must equal visible account currency', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'liquidity_holds' AND expected_class NOT IN ('ASSET', 'INVESTMENT') THEN
        RAISE EXCEPTION 'liquidity holds require an asset or investment account' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'trades' AND expected_class <> 'INVESTMENT' THEN
        RAISE EXCEPTION 'trades require an investment account' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_liquidity_hold_account
BEFORE INSERT OR UPDATE ON liquidity_holds
FOR EACH ROW EXECUTE FUNCTION validate_visible_account_dependent();

CREATE TRIGGER trg_trade_account
BEFORE INSERT OR UPDATE ON trades
FOR EACH ROW EXECUTE FUNCTION validate_visible_account_dependent();

CREATE OR REPLACE FUNCTION validate_liability_account()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND account_class = 'LIABILITY') THEN
        RAISE EXCEPTION 'liability details require a liability account' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_liability_account
BEFORE INSERT OR UPDATE ON liability_details
FOR EACH ROW EXECUTE FUNCTION validate_liability_account();

-- Entry business date/currency must match its immutable transaction and ledger account facts.
CREATE OR REPLACE FUNCTION validate_ledger_entry_references()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_date date;
    expected_currency char(3);
BEGIN
    SELECT business_date INTO expected_date FROM transactions WHERE id = NEW.transaction_id;
    SELECT currency INTO expected_currency FROM ledger_accounts WHERE id = NEW.ledger_account_id;
    IF NEW.business_date <> expected_date THEN
        RAISE EXCEPTION 'ledger entry business_date must match transaction' USING ERRCODE = '23514';
    END IF;
    IF NEW.currency <> expected_currency THEN
        RAISE EXCEPTION 'ledger entry currency must match ledger account' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_ledger_entry_references
BEFORE INSERT OR UPDATE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION validate_ledger_entry_references();

-- Posted entries are permanent facts. Reversal is represented by a new transaction, never by editing entries.
CREATE OR REPLACE FUNCTION prevent_posted_entry_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    original_posted_at timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT posted_at INTO original_posted_at FROM transactions WHERE id = NEW.transaction_id;
    ELSE
        SELECT posted_at INTO original_posted_at FROM transactions WHERE id = OLD.transaction_id;
    END IF;
    IF original_posted_at IS NOT NULL THEN
        RAISE EXCEPTION 'posted ledger entries are immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_prevent_posted_entry_mutation
BEFORE INSERT OR UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_posted_entry_mutation();

-- Once posted, business date and version-chain identity are frozen; status may move to REVERSED/SUPERSEDED.
CREATE OR REPLACE FUNCTION prevent_posted_transaction_fact_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.posted_at IS NOT NULL AND (
        NEW.business_at IS DISTINCT FROM OLD.business_at
        OR NEW.business_date IS DISTINCT FROM OLD.business_date
        OR NEW.timezone IS DISTINCT FROM OLD.timezone
        OR NEW.transaction_type IS DISTINCT FROM OLD.transaction_type
        OR NEW.counterparty IS DISTINCT FROM OLD.counterparty
        OR NEW.merchant IS DISTINCT FROM OLD.merchant
        OR NEW.note IS DISTINCT FROM OLD.note
        OR NEW.source IS DISTINCT FROM OLD.source
        OR NEW.client_operation_id IS DISTINCT FROM OLD.client_operation_id
        OR NEW.idempotency_record_id IS DISTINCT FROM OLD.idempotency_record_id
        OR NEW.root_transaction_id IS DISTINCT FROM OLD.root_transaction_id
        OR NEW.previous_version_id IS DISTINCT FROM OLD.previous_version_id
        OR NEW.reversal_of_id IS DISTINCT FROM OLD.reversal_of_id
        OR NEW.version_no IS DISTINCT FROM OLD.version_no
        OR NEW.posted_at IS DISTINCT FROM OLD.posted_at
    ) THEN
        RAISE EXCEPTION 'posted transaction facts are immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_prevent_posted_transaction_fact_mutation
BEFORE UPDATE ON transactions
FOR EACH ROW EXECUTE FUNCTION prevent_posted_transaction_fact_mutation();

-- Deferred checking permits either entry order, but posting follows DRAFT transaction -> entries -> POSTED.
CREATE OR REPLACE FUNCTION assert_transaction_balanced()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_transaction_id uuid;
    target_posted_at timestamptz;
    entry_count integer;
    unbalanced_count integer;
BEGIN
    IF TG_TABLE_NAME = 'transactions' THEN
        target_transaction_id := NEW.id;
    ELSE
        target_transaction_id := NEW.transaction_id;
    END IF;
    SELECT posted_at INTO target_posted_at FROM transactions WHERE id = target_transaction_id;
    IF target_posted_at IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO entry_count FROM ledger_entries WHERE transaction_id = target_transaction_id;
    SELECT count(*) INTO unbalanced_count
    FROM (
        SELECT currency
        FROM ledger_entries
        WHERE transaction_id = target_transaction_id
        GROUP BY currency
        HAVING sum(CASE WHEN direction = 'D' THEN amount ELSE 0 END)
            <> sum(CASE WHEN direction = 'C' THEN amount ELSE 0 END)
    ) unbalanced;

    IF entry_count < 2 OR unbalanced_count > 0 THEN
        RAISE EXCEPTION 'posted transaction % is not balanced per currency', target_transaction_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_transactions_balanced
AFTER INSERT OR UPDATE OF status, posted_at ON transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

CREATE CONSTRAINT TRIGGER trg_entries_balanced
AFTER INSERT OR UPDATE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- Membership authors can only create their own inclusion settings.
CREATE OR REPLACE FUNCTION validate_inclusion_creator()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    member_user_id uuid;
BEGIN
    SELECT user_id INTO member_user_id FROM account_members WHERE id = NEW.membership_id;
    IF NEW.created_by <> member_user_id THEN
        RAISE EXCEPTION 'inclusion setting creator must own the membership' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_validate_inclusion_creator
BEFORE INSERT OR UPDATE ON account_inclusion_settings
FOR EACH ROW EXECUTE FUNCTION validate_inclusion_creator();

-- This initialization invariant only applies to account creation; later ownership and inclusion may change.
CREATE OR REPLACE FUNCTION assert_account_initialized()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_account_id uuid := NEW.id;
    creator_id uuid := NEW.created_by;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM account_members
        WHERE account_id = target_account_id AND status = 'ACTIVE' AND role = 'OWNER'
    ) THEN
        RAISE EXCEPTION 'account % must have at least one active owner', target_account_id USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM ledger_accounts
        WHERE visible_account_id = target_account_id AND ledger_role = 'PRIMARY'
    ) THEN
        RAISE EXCEPTION 'account % must have a PRIMARY ledger account', target_account_id USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM account_members m
        JOIN account_inclusion_settings s ON s.membership_id = m.id
        WHERE m.account_id = target_account_id AND m.user_id = creator_id
          AND m.status = 'ACTIVE' AND m.role = 'OWNER'
          AND s.valid_to IS NULL AND s.included AND s.ratio = 1
    ) THEN
        RAISE EXCEPTION 'account creator must have a current 100%% inclusion setting' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_accounts_complete
AFTER INSERT ON accounts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_account_initialized();

-- Role and membership changes must retain at least one ACTIVE OWNER at transaction commit.
CREATE OR REPLACE FUNCTION assert_account_has_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_account_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_account_id := OLD.account_id;
    ELSE
        target_account_id := NEW.account_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM account_members
        WHERE account_id = target_account_id AND status = 'ACTIVE' AND role = 'OWNER'
    ) THEN
        RAISE EXCEPTION 'account % must have at least one active owner', target_account_id USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER trg_members_keep_owner
AFTER INSERT OR UPDATE OR DELETE ON account_members
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_account_has_owner();

-- Historical memberships are ended, not deleted.
CREATE OR REPLACE FUNCTION prevent_membership_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'account membership history cannot be deleted' USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_prevent_membership_delete
BEFORE DELETE ON account_members
FOR EACH ROW EXECUTE FUNCTION prevent_membership_delete();

-- Core facts are never physically deleted by ordinary SQL; lifecycle changes use states and reversals.
CREATE OR REPLACE FUNCTION prevent_core_fact_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% facts cannot be physically deleted', TG_TABLE_NAME USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_prevent_transaction_delete
BEFORE DELETE ON transactions
FOR EACH ROW EXECUTE FUNCTION prevent_core_fact_delete();

CREATE TRIGGER trg_prevent_trade_delete
BEFORE DELETE ON trades
FOR EACH ROW EXECUTE FUNCTION prevent_core_fact_delete();

-- Audit records are append-only even if a privileged application path accidentally issues a mutation.
CREATE OR REPLACE FUNCTION prevent_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit logs are append-only' USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_prevent_audit_mutation
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();

-- Flyway owns the schema; deployments grant login roles membership in this restricted application role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ziji_app') THEN
        CREATE ROLE ziji_app NOLOGIN;
    END IF;
END
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ziji_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO ziji_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ziji_app;
REVOKE UPDATE ON ledger_entries, audit_logs FROM ziji_app;
REVOKE DELETE ON ledger_entries, transactions, trades, audit_logs, account_members FROM ziji_app;

-- System categories use stable application-independent UUIDs. User-level ledger system accounts are created at registration.
INSERT INTO categories (
    id, owner_user_id, account_id, category_type, parent_id, name, name_normalized,
    status, merged_into_id, created_at, updated_at, version
) VALUES
    ('00000000-0000-4000-8000-000000000101', NULL, NULL, 'INCOME', NULL, '工资', '工资', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1),
    ('00000000-0000-4000-8000-000000000102', NULL, NULL, 'INCOME', NULL, '投资收益', '投资收益', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1),
    ('00000000-0000-4000-8000-000000000201', NULL, NULL, 'EXPENSE', NULL, '餐饮', '餐饮', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1),
    ('00000000-0000-4000-8000-000000000202', NULL, NULL, 'EXPENSE', NULL, '交通', '交通', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1),
    ('00000000-0000-4000-8000-000000000203', NULL, NULL, 'EXPENSE', NULL, '投资费用', '投资费用', 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1);
