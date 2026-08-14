package app.ziji.auth.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** 用长度前缀编码 HMAC 输入，避免简单字符串拼接产生域边界歧义。 */
final class HmacInputEncoder {

	private HmacInputEncoder() {
	}

	static byte[] encode(String domain, byte[]... parts) {
		byte[] domainBytes = domain.getBytes(StandardCharsets.UTF_8);
		int length = Integer.BYTES + domainBytes.length;
		for (byte[] part : parts) {
			if (part == null) {
				throw new AuthInfrastructureException("HMAC 输入不能为空。");
			}
			length += Integer.BYTES + part.length;
		}
		ByteBuffer buffer = ByteBuffer.allocate(length)
			.putInt(domainBytes.length)
			.put(domainBytes);
		for (byte[] part : parts) {
			buffer.putInt(part.length).put(part);
		}
		return buffer.array();
	}
}
