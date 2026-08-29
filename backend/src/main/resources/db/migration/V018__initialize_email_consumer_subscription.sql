-- Ziji V1: 初始化内置 EMAIL 消费者的邮件验证码订阅事实。
-- 订阅从本次迁移时刻生效，不隐式补发订阅起点之前的旧挑战。

WITH migration_clock AS (
	SELECT CURRENT_TIMESTAMP AS occurred_at
)
INSERT INTO outbox_consumer_subscriptions (
	consumer_name,
	aggregate_type,
	event_type,
	subscribed_from,
	subscribed_until,
	required_for_cleanup,
	created_at)
SELECT
	'EMAIL',
	'EmailChallenge',
	'EmailChallengeIssued',
	migration_clock.occurred_at,
	NULL,
	TRUE,
	migration_clock.occurred_at
FROM migration_clock
ON CONFLICT DO NOTHING;

COMMENT ON TABLE outbox_consumer_subscriptions IS
	'追加式、按 aggregate/event 和有效时间表达消费者订阅义务；内置 SYNC 与 EMAIL 订阅分别由 V015/V018 初始化。';
