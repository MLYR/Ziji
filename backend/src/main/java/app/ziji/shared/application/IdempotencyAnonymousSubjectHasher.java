package app.ziji.shared.application;

/** 公开接口的匿名主体端口；实现负责既有邮箱规范化和独立 HMAC 密钥轮换。 */
public interface IdempotencyAnonymousSubjectHasher {

	IdempotencySubject.Anonymous forEmail(String email);
}
