-- Ziji V1: keep the public PositiveMoney 22-digit integer bound lossless in PostgreSQL.
-- numeric(30,8) provides 22 integer digits plus the database's 8 fractional digits.
ALTER TABLE liquidity_holds
    ALTER COLUMN amount TYPE numeric(30,8);

-- LiquidityHold sums can be larger than the historical ledger money typmod;
-- keep the directly dependent availability projection lossless as well.
ALTER TABLE account_liquidity_snapshots
    ALTER COLUMN ledger_balance TYPE numeric(30,8),
    ALTER COLUMN unavailable_amount TYPE numeric(30,8),
    ALTER COLUMN available_balance TYPE numeric(30,8);
