package app.ziji.auth.infrastructure;

import java.net.InetAddress;
import java.util.Set;

import app.ziji.auth.application.SourceAddressResolver;
import app.ziji.auth.domain.AuthDomainException;
import app.ziji.auth.domain.SourceAddress;

/**
 * 只对显式配置的受信代理读取覆盖头；未受信请求永远使用连接对端地址。
 */
public final class TrustedProxySourceAddressResolver implements SourceAddressResolver {

	private final Set<SourceAddress> trustedProxies;

	public TrustedProxySourceAddressResolver(Set<SourceAddress> trustedProxies) {
		this.trustedProxies = Set.copyOf(trustedProxies == null ? Set.of() : trustedProxies);
	}

	@Override
	public SourceAddress resolve(InetAddress peerAddress, String forwarded, String xForwardedFor) {
		SourceAddress peer = SourceAddress.fromInetAddress(peerAddress);
		if (!trustedProxies.contains(peer)) {
			// 客户端可以伪造这些头，但不能改变未受信连接的主体摘要。
			return peer;
		}
		String candidate = firstForwardedAddress(forwarded);
		if (candidate == null) {
			candidate = firstForwardedAddress(xForwardedFor);
		}
		if (candidate == null) {
			return peer;
		}
		return SourceAddress.parseLiteral(candidate);
	}

	private static String firstForwardedAddress(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String firstHop = header.split(",", 2)[0].trim();
		String value = firstHop;
		if (firstHop.contains("=")) {
			value = null;
			for (String parameter : firstHop.split(";")) {
				String[] pair = parameter.trim().split("=", 2);
				if (pair.length == 2 && "for".equalsIgnoreCase(pair[0].trim())) {
					value = pair[1].trim();
					break;
				}
			}
			if (value == null) {
				throw new AuthDomainException("受信代理来源地址缺失。");
			}
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}
		if (value.startsWith("[")) {
			int closingBracket = value.indexOf(']');
			if (closingBracket < 0) {
				throw new AuthDomainException("受信代理来源地址格式无效。");
			}
			return value.substring(1, closingBracket);
		}
		// X-Forwarded-For 通常没有端口；仅对单冒号的 IPv4:port 形式去掉端口。
		int firstColon = value.indexOf(':');
		if (firstColon > 0 && firstColon == value.lastIndexOf(':')
			&& value.substring(firstColon + 1).matches("[0-9]{1,5}")) {
			return value.substring(0, firstColon);
		}
		return value;
	}
}
