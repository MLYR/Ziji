package app.ziji.auth.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** 规范化来源 IP；只保留 4 或 16 字节二进制地址，不保存原始字符串。 */
public final class SourceAddress {

	private final byte[] address;

	private SourceAddress(byte[] address) {
		if (address.length != 4 && address.length != 16) {
			throw new AuthDomainException("来源地址格式无效。");
		}
		this.address = address.clone();
	}

	public static SourceAddress fromInetAddress(InetAddress address) {
		if (address == null) {
			throw new AuthDomainException("来源地址不能为空。");
		}
		byte[] bytes = address.getAddress();
		if (isIpv4Mapped(bytes)) {
			return new SourceAddress(Arrays.copyOfRange(bytes, 12, 16));
		}
		return new SourceAddress(bytes);
	}

	/** 只接受数字/冒号组成的字面 IP，避免对外部头部做 DNS 解析。 */
	public static SourceAddress parseLiteral(String value) {
		if (value == null || value.isBlank()
			|| !value.matches("[0-9A-Fa-f:.]+")
			|| (!value.contains(":") && !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
			throw new AuthDomainException("来源地址格式无效。");
		}
		try {
			return fromInetAddress(InetAddress.getByName(value));
		} catch (UnknownHostException exception) {
			throw new AuthDomainException("来源地址格式无效。");
		}
	}

	private static boolean isIpv4Mapped(byte[] bytes) {
		if (bytes.length != 16) {
			return false;
		}
		for (int index = 0; index < 10; index++) {
			if (bytes[index] != 0) {
				return false;
			}
		}
		return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
	}

	public byte[] bytes() {
		return address.clone();
	}

	@Override
	public boolean equals(Object other) {
		return this == other
			|| other instanceof SourceAddress source && Arrays.equals(address, source.address);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(address);
	}
}
