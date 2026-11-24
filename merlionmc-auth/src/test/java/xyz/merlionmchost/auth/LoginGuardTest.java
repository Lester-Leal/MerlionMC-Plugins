package xyz.merlionmchost.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginGuardTest {
    private static final long MIN = 60_000;

    @Test
    void eighthWrongPasswordFromOneConnectionBlocksItForTenMinutes() {
        LoginGuard g = new LoginGuard();
        for (int i = 1; i <= 7; i++) assertFalse(g.failure("1.2.3.4", "victim", i * 1000).ipBlocked(), "miss " + i);
        LoginGuard.Miss m = g.failure("1.2.3.4", "other", 8000);
        assertTrue(m.ipBlocked(), "counted across names");
        assertTrue(g.ipBlockedFor("1.2.3.4", 9000) > 9 * MIN);
        assertEquals(0, g.ipBlockedFor("1.2.3.4", 8000 + 10 * MIN));
        assertEquals(0, g.ipBlockedFor("5.6.7.8", 9000), "other connections unaffected");
    }

    @Test
    void loggingIntoYourOwnAccountDoesNotResetTheConnectionCount() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < 4; i++) g.failure("1.2.3.4", "victim", i);
        g.success("attacker"); // the review's bypass: log in as yourself in between
        for (int i = 0; i < 3; i++) assertFalse(g.failure("1.2.3.4", "victim", 10 + i).ipBlocked());
        assertTrue(g.failure("1.2.3.4", "victim", 20).ipBlocked(), "still the 8th miss");
    }

    @Test
    void anAccountUnderAttackFromManyAddressesSlowsDownButNeverLocks() {
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < 5; i++) g.failure("10.0.0." + i, "victim", i * 1000);
        long wait = g.accountWait("victim", 4000);
        assertTrue(wait > 25_000 && wait <= 30_000, "after 5 misses, 30 s between tries: " + wait);
        assertEquals(0, g.accountWait("victim", 4000 + 30_000), "then one more try is allowed");
        assertEquals(0, g.accountWait("someone-else", 4000));
        g.success("victim");
        assertEquals(0, g.accountWait("victim", 5000), "the real owner logging in ends it");
    }

    @Test
    void ipv6IsCountedPerSlash64() {
        assertEquals("2001:db8:1:2::/64", LoginGuard.ipKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        assertEquals("2001:db8:1:2::/64", LoginGuard.ipKey("2001:db8:1:2::1"));
        assertEquals("2001:db8:0:0::/64", LoginGuard.ipKey("2001:db8::1"));
        assertEquals("203.0.113.9", LoginGuard.ipKey("::ffff:203.0.113.9"));
        LoginGuard g = new LoginGuard();
        for (int i = 0; i < 8; i++) g.failure("2001:db8:1:2::" + (i + 1), "victim", i);
        assertTrue(g.ipBlockedFor("2001:db8:1:2::ffff", 10) > 0, "rotating addresses inside one /64 doesn't help");
    }

    @Test
    void squattingANameUntilTheTimeoutBlocksThatNameFromThatConnection() {
        LoginGuard g = new LoginGuard();
        g.timedOut("steve", "6.6.6.6", 0);
        assertTrue(g.squatBlockedFor("steve", "6.6.6.6", 1000) > 4 * MIN);
        assertEquals(0, g.squatBlockedFor("steve", "1.1.1.1", 1000), "the real player elsewhere can join");
        assertEquals(0, g.squatBlockedFor("alex", "6.6.6.6", 1000), "only that name");
        assertEquals(0, g.squatBlockedFor("steve", "6.6.6.6", 5 * MIN + 1));
    }

    @Test
    void nameCase() {
        assertNull(LoginGuard.nameCaseProblem(null, "steve"));
        assertNull(LoginGuard.nameCaseProblem("Steve", "Steve"));
        assertTrue(LoginGuard.nameCaseProblem("Steve", "STEVE").contains("\"Steve\""));
    }
}
