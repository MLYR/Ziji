-- Ziji V1: Tushare 增量同步运行记录与每日调用配额。
-- 运行记录只追加，是同步任务的可观测事实；每日配额以数据库原子自增保证多实例共享上限。

CREATE TABLE market_data_sync_runs (
    id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    instrument_count integer NOT NULL DEFAULT 0 CHECK (instrument_count >= 0),
    succeeded_count integer NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    outcome varchar(200),
    error_summary varchar(500)
);

COMMENT ON TABLE market_data_sync_runs IS '市场数据增量同步运行记录；只追加，不记录供应商原始响应或 token。';
COMMENT ON COLUMN market_data_sync_runs.status IS 'RUNNING/SUCCEEDED/PARTIAL/FAILED；PARTIAL 表示部分产品同步失败但本次运行已结束。';
COMMENT ON COLUMN market_data_sync_runs.outcome IS '本次运行的受控结果摘要，如 SUCCESS/QUOTA_EXHAUSTED/NO_TOKEN。';

CREATE INDEX idx_sync_runs_started ON market_data_sync_runs (started_at DESC);

CREATE TABLE market_data_daily_quotas (
    usage_date date PRIMARY KEY,
    used_calls integer NOT NULL DEFAULT 0 CHECK (used_calls >= 0),
    call_limit integer NOT NULL CHECK (call_limit > 0)
);

COMMENT ON TABLE market_data_daily_quotas IS '外部行情供应商的每日调用配额；used_calls 原子自增且不得超过 call_limit。';
COMMENT ON COLUMN market_data_daily_quotas.usage_date IS '配额统计的自然日，以服务端时区计算。';
COMMENT ON COLUMN market_data_daily_quotas.call_limit IS '当日允许的最大供应商调用次数，由配置写入，跨实例共享。';
