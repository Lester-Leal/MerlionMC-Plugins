package xyz.merlionmchost.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One-time codes the server owner gives a player so that player, and nobody faster, can (re)register a name:
 * after a reset, or for a name that played here before MerlionMCAuth was installed. 8 characters without look-alikes
 * (no 0/O, 1/I/L), shown as ABCD-2345, valid 24 hours, used once. Only a SHA-256 is stored: a code is random and
 * short-lived, and guessing it over the game is limited like passwords (LoginGuard).
 */
final class OwnerCodes {
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final long VALID_MS = 24 * 60 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OwnerCodes() {}

    static String generate() {
        StringBuilder b = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) b.append('-');
            b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    /** "abcd-2345", "ABCD2345" and " abcd 2345 " are the same code. */
    static String normalize(String typed) {
        return typed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
    }

    static String hash(String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalize(code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether a name needs an owner's code to register. */
    static boolean needsCode(boolean pendingCode, boolean playedBefore, long firstPlayed, long installedAt) {
        // firstPlayed is 0 when unknown; such a player is treated as new (nothing of theirs to take over).
        return pendingCode || (playedBefore && firstPlayed > 0 && firstPlayed < installedAt);
    }
}
