package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordRulesTest {

    @Test
    void acceptsAGoodPassword() {
        assertNull(PasswordRules.problem("Steve", "barkada2026", "barkada2026"));
    }

    @Test
    void refusesMismatchShortLongAndOwnName() {
        assertNotNull(PasswordRules.problem("Steve", "barkada2026", "barkada2025"));
        assertNotNull(PasswordRules.problem("Steve", "abc12", "abc12"));
        String tooLong = "x".repeat(PasswordRules.MAX_LENGTH + 1);
        assertNotNull(PasswordRules.problem("Steve", tooLong, tooLong));
        assertNotNull(PasswordRules.problem("Steve1", "STEVE1", "STEVE1"), "own name, any case");
    }
}
