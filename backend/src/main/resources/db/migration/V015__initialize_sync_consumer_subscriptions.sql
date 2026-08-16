-- Ziji V1: initialize the built-in SYNC consumer subscription facts.

INSERT INTO outbox_consumer_subscriptions (
	consumer_name,
	aggregate_type,
	event_type,
	subscribed_from,
	subscribed_until,
	required_for_cleanup,
	created_at)
VALUES
	('SYNC', 'Transaction', 'TransactionPosted', TIMESTAMPTZ '1970-01-01 00:00:00+00', NULL, TRUE,
	 TIMESTAMPTZ '1970-01-01 00:00:00+00'),
	('SYNC', 'Transaction', 'TransactionReversed', TIMESTAMPTZ '1970-01-01 00:00:00+00', NULL, TRUE,
	 TIMESTAMPTZ '1970-01-01 00:00:00+00')
ON CONFLICT DO NOTHING;

COMMENT ON TABLE outbox_consumer_subscriptions IS
	'追加式、按 aggregate/event 和有效时间表达消费者订阅义务；内置 SYNC 订阅由 V015 初始化。';
