package edu.pb.dcls;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class Security {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private Security() { }

    static Credentials credentials(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltText = Base64.getEncoder().encodeToString(salt);
        return new Credentials(Base64.getEncoder().encodeToString(derive(password, salt)), saltText);
    }

    static boolean matches(char[] password, String expectedHash, String saltText) {
        if (expectedHash == null || saltText == null) return false;
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(saltText);
            expected = Base64.getDecoder().decode(expectedHash);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return MessageDigest.isEqual(expected, derive(password, salt));
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Password hashing is unavailable.", exception);
        } finally {
            spec.clearPassword();
        }
    }

    record Credentials(String hash, String salt) { }
}
