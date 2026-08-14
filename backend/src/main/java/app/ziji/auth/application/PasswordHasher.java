package app.ziji.auth.application;

/** 密码 Hash 端口隔离具体算法；调用方只处理短暂存在的明文输入。 */
public interface PasswordHasher {

	String hash(String password);

	boolean matches(String password, String encodedHash);
}
