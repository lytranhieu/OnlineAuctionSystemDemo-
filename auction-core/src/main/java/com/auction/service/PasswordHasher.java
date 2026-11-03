package com.auction.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2, never persist raw passwords. */
public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordHasher() { }

    public static String salt() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    public static String hash(char[] password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(password, Base64.getDecoder().decode(salt), 120_000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing unavailable", e);
        } finally { spec.clearPassword(); }
    }

    public static boolean matches(char[] password, String salt, String expected) {
        byte[] actual = Base64.getDecoder().decode(hash(password, salt));
        return MessageDigest.isEqual(actual, Base64.getDecoder().decode(expected));
    }
}
