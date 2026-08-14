package app.ziji.auth.application;

/** 密码 Hash 端口隔离具体算法；调用方只处理短暂存在的明文输入。 */
public interface PasswordHasher {

	String hash(String password);

	/**
	 * 无日志、无昂贵计算地确认编码值是否属于当前可安全校验的密码 Hash 格式。
	 */
	boolean supports(int hashVersion, String encodedHash);

	boolean matches(String password, String encodedHash);
}
