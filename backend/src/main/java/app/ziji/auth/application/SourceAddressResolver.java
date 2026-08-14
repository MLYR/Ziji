package app.ziji.auth.application;

import java.net.InetAddress;

import app.ziji.auth.domain.SourceAddress;

/** 来源地址解析端口；只有 infrastructure 实现可以解释受信代理头。 */
public interface SourceAddressResolver {

	SourceAddress resolve(InetAddress peerAddress, String forwarded, String xForwardedFor);
}
