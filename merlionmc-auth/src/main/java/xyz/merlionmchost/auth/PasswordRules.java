package xyz.merlionmchost.auth;

/** What a new password must be. Plain logic (no Paper), so it's tested on its own. */
public final class PasswordRules {
    static final int MIN_LENGTH = 6;
    static final int MAX_LENGTH = 128;

    private PasswordRules() {}

    /** Why a new password is refused, or null if it's fine. */
    public static String problem(String playerName, String password, String repeat) {
        if (!password.equals(repeat)) return "The two passwords don't match.";
        if (password.length() < MIN_LENGTH) return "Use at least " + MIN_LENGTH + " characters.";
        if (password.length() > MAX_LENGTH) return "That password is too long.";
        if (password.equalsIgnoreCase(playerName)) return "Don't use your name as your password.";
        return null;
    }
}
