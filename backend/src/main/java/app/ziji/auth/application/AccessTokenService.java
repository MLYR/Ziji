package app.ziji.auth.application;

import java.time.Instant;
import java.util.UUID;

/** Access Token 签发与验证端口；HTTP 边界只接收结果，不能接触签名私钥。 */
public interface AccessTokenService {

	IssuedAccessToken issue(UUID userId, UUID sessionId, Instant issuedAt, Instant sessionExpiresAt);

	VerifiedAccessToken verify(String encodedToken, Instant now);
}
