package dev.hydrogen.core.console;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * One-time pairing codes.
 *
 * Twelve symbols from Crockford's base 32 carry 60 bits, drawn from
 * {@link SecureRandom}. The code is shown to the player and never sent: the
 * server only receives its SHA-256 under a fixed prefix, and a browser proves
 * it saw the code by sending the code itself, which the server hashes the same
 * way. The Worker's tests check the same vector as this class's tests.
 */
public final class PairCode {
	public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
	public static final int LENGTH = 12;
	private static final String PREFIX = "hydrogen-pair-v1:";

	private PairCode() {
	}

	public static String generate(SecureRandom random) {
		char[] out = new char[LENGTH];

		for (int i = 0; i < LENGTH; i++) {
			out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
		}

		return new String(out);
	}

	/** Lower-case hex SHA-256 of the prefixed code. */
	public static String hash(String code) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest((PREFIX + code).getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(64);

			for (byte b : d) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}

			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// Every Java runtime is required to provide SHA-256.
			throw new IllegalStateException(e);
		}
	}

	/** H7QK2MXD9RTC becomes H7QK-2MXD-9RTC, the way it is shown and typed. */
	public static String format(String code) {
		return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8);
	}
}
