package app.ziji.auth.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证密钥和受信代理配置；生产值必须由外部部署配置提供。 */
@ConfigurationProperties(prefix = "ziji.auth")
public class AuthSecurityProperties {

	private HmacProperties hmac = new HmacProperties();
	private EnvelopeProperties envelope = new EnvelopeProperties();
	private List<String> trustedProxyAddresses = new ArrayList<>();

	public HmacProperties getHmac() {
		return hmac;
	}

	public void setHmac(HmacProperties hmac) {
		this.hmac = hmac;
	}

	public EnvelopeProperties getEnvelope() {
		return envelope;
	}

	public void setEnvelope(EnvelopeProperties envelope) {
		this.envelope = envelope;
	}

	public List<String> getTrustedProxyAddresses() {
		return List.copyOf(trustedProxyAddresses);
	}

	public void setTrustedProxyAddresses(List<String> trustedProxyAddresses) {
		this.trustedProxyAddresses = new ArrayList<>();
		if (trustedProxyAddresses != null) {
			// 空白环境变量表示不信任任何代理，不能被当作待解析的 IP 地址。
			for (String address : trustedProxyAddresses) {
				if (address != null && !address.isBlank()) {
					this.trustedProxyAddresses.add(address.trim());
				}
			}
		}
	}

	public static class HmacProperties {
		private String currentKeyBase64;
		private int currentKeyVersion;
		private String previousKeyBase64;
		private String previousKeyVersion;
		private Duration previousKeyRetention = Duration.ofHours(48);

		public String getCurrentKeyBase64() {
			return currentKeyBase64;
		}

		public void setCurrentKeyBase64(String currentKeyBase64) {
			this.currentKeyBase64 = currentKeyBase64;
		}

		public int getCurrentKeyVersion() {
			return currentKeyVersion;
		}

		public void setCurrentKeyVersion(int currentKeyVersion) {
			this.currentKeyVersion = currentKeyVersion;
		}

		public String getPreviousKeyBase64() {
			return previousKeyBase64;
		}

		public void setPreviousKeyBase64(String previousKeyBase64) {
			this.previousKeyBase64 = previousKeyBase64;
		}

		public String getPreviousKeyVersion() {
			return previousKeyVersion;
		}

		public void setPreviousKeyVersion(String previousKeyVersion) {
			this.previousKeyVersion = previousKeyVersion;
		}

		public Duration getPreviousKeyRetention() {
			return previousKeyRetention;
		}

		public void setPreviousKeyRetention(Duration previousKeyRetention) {
			this.previousKeyRetention = previousKeyRetention;
		}
	}

	public static class EnvelopeProperties {
		private String kekBase64;
		private int kekVersion;

		public String getKekBase64() {
			return kekBase64;
		}

		public void setKekBase64(String kekBase64) {
			this.kekBase64 = kekBase64;
		}

		public int getKekVersion() {
			return kekVersion;
		}

		public void setKekVersion(int kekVersion) {
			this.kekVersion = kekVersion;
		}
	}
}
