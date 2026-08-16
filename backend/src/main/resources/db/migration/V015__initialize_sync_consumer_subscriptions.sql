-- Ziji V1: initialize the built-in SYNC consumer subscription facts.

-- 迁移提交时刻是 SYNC 的正常消费下界；更早的历史事件只能由后续显式、可审计的 backfill 处理。

INSERT INTO outbox_consumer_subscriptions (
	consumer_name,
	aggregate_type,
	event_type,
	subscribed_from,
	subscribed_until,
	required_for_cleanup,
	created_at)
VALUES
	('SYNC', 'Transaction', 'TransactionPosted', CURRENT_TIMESTAMP, NULL, TRUE, CURRENT_TIMESTAMP),
	('SYNC', 'Transaction', 'TransactionReversed', CURRENT_TIMESTAMP, NULL, TRUE, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE outbox_consumer_subscriptions IS
	'追加式、按 aggregate/event 和有效时间表达消费者订阅义务；内置 SYNC 订阅由 V015 初始化。';
