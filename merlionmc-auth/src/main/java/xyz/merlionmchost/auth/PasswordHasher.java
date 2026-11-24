package xyz.merlionmchost.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Argon2id password hashes in the standard PHC string format ($argon2id$v=19$m=...,t=...,p=...$salt$hash), so the
 * parameters travel with each hash and can be raised later without breaking old ones.
 * Parameters: OWASP's recommended minimum for Argon2id (19 MiB, 2 passes, 1 lane): a few tens of milliseconds per
 * hash, run off the main thread.
 */
public final class PasswordHasher {
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;
    static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] out = argon2(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(out);
    }

    /** True if the password matches. A malformed stored value is a mismatch, never an exception. */
    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            // ["", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash]
            if (parts.length != 6 || !parts[1].equals("argon2id") || !parts[2].equals("v=19")) return false;
            int m = 0, t = 0, p = 0;
            for (String kv : parts[3].split(",")) {
                String[] pair = kv.split("=", 2);
                int v = Integer.parseInt(pair[1]);
                switch (pair[0]) {
                    case "m" -> m = v;
                    case "t" -> t = v;
                    case "p" -> p = v;
                    default -> { return false; }
                }
            }
            // Bounds keep a tampered database row from making the server hash with absurd settings.
            if (m < 1024 || m > 1024 * 1024 || t < 1 || t > 10 || p < 1 || p > 4) return false;
            byte[] salt = B64D.decode(parts[4]);
            byte[] expected = B64D.decode(parts[5]);
            if (salt.length < 8 || expected.length < 16 || expected.length > 64) return false;
            byte[] actual = argon2(password, salt, m, t, p, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] argon2(String password, byte[] salt, int memoryKib, int iterations, int parallelism, int length) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
