package com.linkr.backend.invite;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF (RFC 5869) con HMAC-SHA256: ricava da un segreto una chiave per UNA finalita' precisa (il parametro {@code info}).
 * Serve a non usare mai lo stesso segreto, tale e quale, per due scopi diversi: il JWT firma con il segreto, i token d'invito
 * con una chiave derivata. Implementazione standard, verificata con i vettori di prova dell'RFC (HkdfTest); il JDK 21 non ne ha una.
 */
final class Hkdf {

	private static final String HMAC = "HmacSHA256";
	private static final int HASH_LENGTH = 32;

	private Hkdf() {
	}

	/** Extract + Expand. Con {@code salt} nullo o vuoto l'RFC prevede HashLen byte a zero. */
	static byte[] derive(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length) {
		if (length < 1 || length > 255 * HASH_LENGTH) {
			throw new IllegalArgumentException("Invalid output length");
		}
		byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[HASH_LENGTH] : salt;
		byte[] pseudoRandomKey = hmac(effectiveSalt, inputKeyMaterial);

		byte[] output = new byte[length];
		byte[] previous = new byte[0];
		int offset = 0;
		for (int counter = 1; offset < length; counter++) {
			Mac mac = mac(pseudoRandomKey);
			mac.update(previous);
			mac.update(info);
			mac.update((byte) counter);
			previous = mac.doFinal();
			int copy = Math.min(previous.length, length - offset);
			System.arraycopy(previous, 0, output, offset, copy);
			offset += copy;
		}
		return output;
	}

	static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		return mac(key).doFinal(data);
	}

	private static Mac mac(byte[] key) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(new SecretKeySpec(key, HMAC));
			return mac;
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

}
