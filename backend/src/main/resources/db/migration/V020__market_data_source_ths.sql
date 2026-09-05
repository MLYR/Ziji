-- Ziji V1: CHG-MD-001 行情数据源切换为同花顺公开接口。
-- TUSHARE 保留在枚举中只用于读取既有历史行，不进入 V1 新写入路径。

ALTER TABLE instrument_source_mappings DROP CONSTRAINT instrument_source_mappings_source_check;
ALTER TABLE instrument_source_mappings ADD CONSTRAINT instrument_source_mappings_source_check
    CHECK (source IN ('THS', 'TUSHARE', 'MANUAL'));

ALTER TABLE price_snapshots DROP CONSTRAINT price_snapshots_source_check;
ALTER TABLE price_snapshots ADD CONSTRAINT price_snapshots_source_check
    CHECK (source IN ('THS', 'TUSHARE', 'MANUAL'));
