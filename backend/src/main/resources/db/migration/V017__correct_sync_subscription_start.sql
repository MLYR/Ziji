-- Ziji V1: preserve V015 history and establish the intended normal SYNC consumption boundary.

-- 仅更正 V015 写入的两条内置初始化事实；显式 backfill 与其他消费者订阅保持原有效区间。
WITH migration_clock AS (
    SELECT CURRENT_TIMESTAMP AS occurred_at
)
UPDATE outbox_consumer_subscriptions AS subscription
SET subscribed_from = migration_clock.occurred_at,
    created_at = migration_clock.occurred_at
FROM migration_clock
WHERE subscription.consumer_name = 'SYNC'
  AND subscription.aggregate_type = 'Transaction'
  AND subscription.event_type IN ('TransactionPosted', 'TransactionReversed')
  AND subscription.subscribed_from = TIMESTAMPTZ '1970-01-01 00:00:00+00'
  AND subscription.created_at = TIMESTAMPTZ '1970-01-01 00:00:00+00'
  AND subscription.subscribed_until IS NULL
  AND subscription.required_for_cleanup;
