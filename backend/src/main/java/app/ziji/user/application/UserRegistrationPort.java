package app.ziji.user.application;

/** 认证模块创建首个 users 事实的公开 application 端口，禁止跨模块访问 user infrastructure。 */
public interface UserRegistrationPort {

	void register(UserRegistrationCommand command);
}
