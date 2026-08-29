package app.ziji.auth.application;

/** 认证邮件投递端口；SMTP、Mailpit 或其他供应商实现均隔离在 infrastructure。 */
public interface EmailDelivery {

	void send(EmailChallengeEmail email);
}
