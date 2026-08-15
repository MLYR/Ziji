package app.ziji.shared.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 已认证或公开接口的幂等主体；匿名摘要永不通过 toString 暴露。 */
public sealed interface IdempotencySubject
	permits IdempotencySubject.Authenticated, IdempotencySubject.Anonymous {

	static Authenticated authenticated(UUID userId) {
		return new Authenticated(userId);
	}

	static Anonymous anonymous(AnonymousDigest current, AnonymousDigest previous) {
		return new Anonymous(current, previous);
	}

	/** 已认证写操作使用已经完成认证与授权检查的用户标识。 */
	final class Authenticated implements IdempotencySubject {

		private final UUID userId;

		private Authenticated(UUID userId) {
			if (userId == null) {
				throw new IdempotencyValidationException("已认证幂等主体不能为空。");
			}
			this.userId = userId;
		}

		public UUID userId() {
			return userId;
		}
	}

	/** 公开写操作只携带当前及上一版本的匿名 HMAC 摘要候选。 */
	final class Anonymous implements IdempotencySubject {

		private final AnonymousDigest current;
		private final List<AnonymousDigest> lookupCandidatesInVersionOrder;

		private Anonymous(AnonymousDigest current, AnonymousDigest previous) {
			if (current == null) {
				throw new IdempotencyValidationException("当前匿名幂等主体不能为空。");
			}
			if (previous != null && previous.keyVersion() == current.keyVersion()) {
				throw new IdempotencyValidationException("匿名幂等主体密钥版本必须唯一。");
			}
			this.current = current;
			List<AnonymousDigest> candidates = new ArrayList<>();
			candidates.add(current);
			if (previous != null) {
				candidates.add(previous);
			}
			candidates.sort(Comparator.comparingInt(AnonymousDigest::keyVersion));
			this.lookupCandidatesInVersionOrder = List.copyOf(candidates);
		}

		/** 新纪录只能使用当前版本，上一版本仅用于安全命中既有记录。 */
		public AnonymousDigest current() {
			return current;
		}

		/** 查询顺序固定，避免同一轮换窗口内以不同顺序锁定两条候选记录。 */
		public List<AnonymousDigest> lookupCandidatesInVersionOrder() {
			return lookupCandidatesInVersionOrder;
		}
	}

	/** 32 字节 HMAC-SHA-256 输出与其外部密钥版本；访问器返回副本。 */
	final class AnonymousDigest {

		private final int keyVersion;
		private final byte[] value;

		public AnonymousDigest(int keyVersion, byte[] value) {
			// 所有端口实现都必须遵守 V009 smallint 版本边界，不能只依赖默认 HMAC adapter。
			if (keyVersion <= 0 || keyVersion > Short.MAX_VALUE || value == null || value.length != 32) {
				throw new IdempotencyValidationException("匿名幂等主体摘要无效。");
			}
			this.keyVersion = keyVersion;
			this.value = value.clone();
		}

		public int keyVersion() {
			return keyVersion;
		}

		public byte[] valueCopy() {
			return value.clone();
		}

		@Override
		public boolean equals(Object other) {
			return this == other
				|| other instanceof AnonymousDigest digest
				&& keyVersion == digest.keyVersion
				&& Arrays.equals(value, digest.value);
		}

		@Override
		public int hashCode() {
			return 31 * keyVersion + Arrays.hashCode(value);
		}

		@Override
		public String toString() {
			return "AnonymousDigest[keyVersion=" + keyVersion + ", value=redacted]";
		}
	}
}
