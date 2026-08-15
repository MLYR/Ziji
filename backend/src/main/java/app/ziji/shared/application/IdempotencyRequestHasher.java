package app.ziji.shared.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * API §2.4 的请求 Hash：外层固定为六段大端长度前缀帧，载荷内部以无歧义类型标签递归编码。
 * 调用方必须先完成路由解码、类型校验和重复语义 query 参数拒绝，再传入实际资源标识与类型化载荷。
 */
public final class IdempotencyRequestHasher {

	private static final String DOMAIN = "ZIJI-IDEMPOTENCY-REQUEST-V1";

	private IdempotencyRequestHasher() {
	}

	public static String hash(
		String httpMethod,
		String mediaType,
		String actualResourceIdentifier,
		Object typedBusinessPayload,
		String ifMatch) {
		ByteArrayOutputStream frame = new ByteArrayOutputStream();
		writeFrame(frame, utf8(DOMAIN));
		writeFrame(frame, utf8(normalizeMethod(httpMethod)));
		writeFrame(frame, utf8(normalizeMediaType(mediaType)));
		writeFrame(frame, utf8(requireResourceIdentifier(actualResourceIdentifier)));
		writeFrame(frame, canonicalPayload(typedBusinessPayload));
		writeFrame(frame, canonicalIfMatch(ifMatch));
		return hexadecimal(sha256(frame.toByteArray()));
	}

	/** 显式建模 multipart/二进制分片，避免把原始文件内容或 Token 载入 Hash 输入。 */
	public static BinaryPart binaryPart(String mediaType, long byteLength, String sha256) {
		return new BinaryPart(mediaType, byteLength, sha256);
	}

	/** 显式标记应按 Decimal 规则规范化的字符串，普通字符串始终保留原样和前导零。 */
	public static Decimal decimal(String value) {
		try {
			return new Decimal(new BigDecimal(value));
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	private static byte[] canonicalPayload(Object value) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		writeValue(output, value);
		return output.toByteArray();
	}

	private static void writeValue(ByteArrayOutputStream output, Object value) {
		if (value == null) {
			output.write('N');
			return;
		}
		if (value instanceof String string) {
			output.write('S');
			writeFrame(output, utf8(string));
			return;
		}
		if (value instanceof Boolean bool) {
			output.write(bool ? 'T' : 'F');
			return;
		}
		if (value instanceof Decimal decimal) {
			writeDecimal(output, decimal.value());
			return;
		}
		if (value instanceof BigDecimal decimal) {
			writeDecimal(output, decimal);
			return;
		}
		if (value instanceof BigInteger integer) {
			writeInteger(output, integer.toString());
			return;
		}
		if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
			writeInteger(output, String.valueOf(value));
			return;
		}
		if (value instanceof Float || value instanceof Double) {
			throw invalid();
		}
		if (value instanceof UUID uuid) {
			output.write('U');
			writeFrame(output, utf8(uuid.toString()));
			return;
		}
		if (value instanceof LocalDate date) {
			output.write('D');
			writeFrame(output, utf8(DateTimeFormatter.ISO_LOCAL_DATE.format(date)));
			return;
		}
		if (value instanceof Instant instant) {
			writeInstant(output, instant);
			return;
		}
		if (value instanceof OffsetDateTime dateTime) {
			writeInstant(output, dateTime.toInstant());
			return;
		}
		if (value instanceof ZonedDateTime dateTime) {
			writeInstant(output, dateTime.toInstant());
			return;
		}
		if (value instanceof Enum<?> enumeration) {
			output.write('E');
			writeFrame(output, utf8(enumeration.name()));
			return;
		}
		if (value instanceof BinaryPart part) {
			output.write('B');
			writeFrame(output, utf8(part.mediaType()));
			writeFrame(output, utf8(Long.toString(part.byteLength())));
			writeFrame(output, utf8(part.sha256()));
			return;
		}
		if (value instanceof Map<?, ?> map) {
			writeObject(output, map);
			return;
		}
		if (value instanceof Collection<?> collection) {
			writeArray(output, collection);
			return;
		}
		if (value instanceof Object[] values) {
			// Arrays may legally contain JSON null，不能使用会拒绝 null 的 List.of。
			writeArray(output, Arrays.asList(values));
			return;
		}
		throw invalid();
	}

	private static void writeObject(ByteArrayOutputStream output, Map<?, ?> values) {
		List<ObjectEntry> entries = new ArrayList<>();
		for (Map.Entry<?, ?> entry : values.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw invalid();
			}
			entries.add(new ObjectEntry(utf8(key), entry.getValue()));
		}
		entries.sort(Comparator.comparing(ObjectEntry::keyBytes, IdempotencyRequestHasher::compareUnsigned));
		output.write('O');
		writeInt(output, entries.size());
		for (ObjectEntry entry : entries) {
			writeFrame(output, entry.keyBytes());
			writeValue(output, entry.value());
		}
	}

	private static void writeArray(ByteArrayOutputStream output, Collection<?> values) {
		output.write('A');
		writeInt(output, values.size());
		for (Object value : values) {
			writeValue(output, value);
		}
	}

	private static void writeDecimal(ByteArrayOutputStream output, BigDecimal value) {
		if (value == null) {
			throw invalid();
		}
		BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
		output.write('M');
		writeFrame(output, utf8(normalized.toPlainString()));
	}

	private static void writeInteger(ByteArrayOutputStream output, String value) {
		output.write('I');
		writeFrame(output, utf8(value));
	}

	private static void writeInstant(ByteArrayOutputStream output, Instant value) {
		output.write('Z');
		writeFrame(output, utf8(DateTimeFormatter.ISO_INSTANT.format(value)));
	}

	private static byte[] canonicalIfMatch(String value) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (value == null) {
			// 0 与 1 + 长度帧确保缺失不会和任意实际 ETag 文本碰撞。
			output.write(0);
		} else {
			output.write(1);
			writeFrame(output, utf8(value));
		}
		return output.toByteArray();
	}

	private static String normalizeMethod(String value) {
		if (value == null) {
			throw invalid();
		}
		String normalized = value.toUpperCase(Locale.ROOT);
		if (normalized.isBlank()) {
			throw invalid();
		}
		for (int index = 0; index < normalized.length(); index++) {
			char character = normalized.charAt(index);
			if (character <= 0x20 || character >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(character) >= 0) {
				throw invalid();
			}
		}
		return normalized;
	}

	private static String normalizeMediaType(String value) {
		if (value == null) {
			throw invalid();
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		int parameterStart = normalized.indexOf(';');
		if (parameterStart >= 0) {
			normalized = normalized.substring(0, parameterStart).trim();
		}
		int slash = normalized.indexOf('/');
		if (slash <= 0 || slash != normalized.lastIndexOf('/') || slash == normalized.length() - 1) {
			throw invalid();
		}
		return normalized;
	}

	private static String requireResourceIdentifier(String value) {
		if (value == null || value.isBlank()) {
			throw invalid();
		}
		utf8(value);
		return value;
	}

	private static byte[] utf8(String value) {
		if (value == null) {
			throw invalid();
		}
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++index))) {
					throw invalid();
				}
			} else if (Character.isLowSurrogate(character)) {
				throw invalid();
			}
		}
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static void writeFrame(ByteArrayOutputStream output, byte[] value) {
		writeInt(output, value.length);
		output.writeBytes(value);
	}

	private static void writeInt(ByteArrayOutputStream output, int value) {
		if (value < 0) {
			throw invalid();
		}
		output.write(value >>> 24);
		output.write(value >>> 16);
		output.write(value >>> 8);
		output.write(value);
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		int length = Math.min(left.length, right.length);
		for (int index = 0; index < length; index++) {
			int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
			if (comparison != 0) {
				return comparison;
			}
		}
		return Integer.compare(left.length, right.length);
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 不可用。", exception);
		}
	}

	private static String hexadecimal(byte[] value) {
		StringBuilder output = new StringBuilder(value.length * 2);
		for (byte item : value) {
			output.append(Character.forDigit(Byte.toUnsignedInt(item) >>> 4, 16));
			output.append(Character.forDigit(Byte.toUnsignedInt(item) & 0x0f, 16));
		}
		return output.toString();
	}

	private static IdempotencyValidationException invalid() {
		return new IdempotencyValidationException("幂等请求规范化无效。");
	}

	private record ObjectEntry(byte[] keyBytes, Object value) {
	}

	public static final class Decimal {

		private final BigDecimal value;

		private Decimal(BigDecimal value) {
			if (value == null) {
				throw invalid();
			}
			this.value = value;
		}

		private BigDecimal value() {
			return value;
		}
	}

	public static final class BinaryPart {

		private final String mediaType;
		private final long byteLength;
		private final String sha256;

		private BinaryPart(String mediaType, long byteLength, String sha256) {
			String normalizedMediaType = normalizeMediaType(mediaType);
			if (byteLength < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
				throw invalid();
			}
			this.mediaType = normalizedMediaType;
			this.byteLength = byteLength;
			this.sha256 = sha256;
		}

		private String mediaType() {
			return mediaType;
		}

		private long byteLength() {
			return byteLength;
		}

		private String sha256() {
			return sha256;
		}
	}
}
