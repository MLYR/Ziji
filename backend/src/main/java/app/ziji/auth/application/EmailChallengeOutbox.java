package app.ziji.auth.application;

/** 认证事件 outbox 端口；实现必须与挑战事实共用同一数据库事务。 */
public interface EmailChallengeOutbox {

	void append(EmailChallengeIssuedEvent event);
}
