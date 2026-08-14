package app.ziji.auth.application;

/** outbox 中验证码的应用层信封；字段只含密文和密钥包装材料。 */
public final class EncryptedCodeEnvelope {

	private final String algorithm;
	private final String keyEncryptionAlgorithm;
	private final int keyVersion;
	private final String nonce;
	private final String ciphertext;
	private final String wrappedDataKey;
	private final String wrappedDataKeyNonce;

	public EncryptedCodeEnvelope(
		String algorithm,
		String keyEncryptionAlgorithm,
		int keyVersion,
		String nonce,
		String ciphertext,
		String wrappedDataKey,
		String wrappedDataKeyNonce) {
		this.algorithm = algorithm;
		this.keyEncryptionAlgorithm = keyEncryptionAlgorithm;
		this.keyVersion = keyVersion;
		this.nonce = nonce;
		this.ciphertext = ciphertext;
		this.wrappedDataKey = wrappedDataKey;
		this.wrappedDataKeyNonce = wrappedDataKeyNonce;
	}

	public String algorithm() {
		return algorithm;
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public String keyEncryptionAlgorithm() {
		return keyEncryptionAlgorithm;
	}

	public String getKeyEncryptionAlgorithm() {
		return keyEncryptionAlgorithm;
	}

	public int keyVersion() {
		return keyVersion;
	}

	public int getKeyVersion() {
		return keyVersion;
	}

	public String nonce() {
		return nonce;
	}

	public String getNonce() {
		return nonce;
	}

	public String ciphertext() {
		return ciphertext;
	}

	public String getCiphertext() {
		return ciphertext;
	}

	public String wrappedDataKey() {
		return wrappedDataKey;
	}

	public String getWrappedDataKey() {
		return wrappedDataKey;
	}

	public String wrappedDataKeyNonce() {
		return wrappedDataKeyNonce;
	}

	public String getWrappedDataKeyNonce() {
		return wrappedDataKeyNonce;
	}
}
