package xyz.merlionmchost.auth;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrong-password and join-abuse limits. Plain logic with the time passed in, so it's tested without a server.
 * In memory: a restart clears it.
 *
 * - Per connection (IP, IPv6 per /64): 8 wrong passwords within 10 minutes, on any names, blocks it for 10 minutes.
 *   A correct login does NOT clear this, so an attacker can't reset the count by logging into their own account.
 * - Per account: after 5 wrong passwords within an hour, each further try must wait 30 seconds. That slows guessing
 *   from many addresses to a crawl without ever locking the real player out (they only wait a little).
 * - Per name and connection: someone who sits on another player's name at the login prompt until the timeout can't
 *   rejoin as that name from the same connection for 5 minutes, so the real player gets a turn.
 */
final class LoginGuard {
    static final int IP_MAX_FAILURES = 8;
    static final long IP_WINDOW_MS = 10 * 60_000;
    static final long IP_BLOCK_MS = 10 * 60_000;
    static final int ACCOUNT_FREE_FAILURES = 5;
    static final long ACCOUNT_WINDOW_MS = 60 * 60_000;
    static final long ACCOUNT_DELAY_MS = 30_000;
    static final long SQUAT_BLOCK_MS = 5 * 60_000;

    private record Count(int n, long firstAt, long lastAt) {}

    private final Map<String, Count> ipFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> ipBlockedUntil = new ConcurrentHashMap<>();
    private final Map<String, Count> accountFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> squatBlockedUntil = new ConcurrentHashMap<>();

    /** What a wrong password led to. */
    record Miss(boolean ipBlocked, int ipTriesLeft) {}

    /** The address as it's counted: IPv4 as is, IPv6 by its /64 (one household or server gets a whole /64). */
    static String ipKey(String ip) {
        String a = ip.replaceFirst("^::ffff:", "");
        if (!a.contains(":")) return a;
        String[] groups = expandV6(a);
        return groups == null ? a.toLowerCase(Locale.ROOT) : String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::/64";
    }

    private static String[] expandV6(String a) {
        String s = a.toLowerCase(Locale.ROOT);
        int pct = s.indexOf('%');
        if (pct >= 0) s = s.substring(0, pct);
        String[] halves = s.split("::", -1);
        if (halves.length > 2) return null;
        String[] left = halves[0].isEmpty() ? new String[0] : halves[0].split(":");
        String[] right = halves.length == 2 && !halves[1].isEmpty() ? halves[1].split(":") : new String[0];
        int missing = 8 - left.length - right.length;
        if (halves.length == 1 ? missing != 0 : missing < 1) return null;
        String[] out = new String[8];
        int i = 0;
        for (String g : left) out[i++] = g;
        for (int k = 0; k < missing; k++) out[i++] = "0";
        for (String g : right) out[i++] = g;
        return out;
    }

    /** Records a wrong password for this connection and account. */
    synchronized Miss failure(String ip, String account, long now) {
        prune(now);
        String k = ipKey(ip);
        Count c = bump(ipFailures.get(k), now, IP_WINDOW_MS);
        accountFailures.put(account, bump(accountFailures.get(account), now, ACCOUNT_WINDOW_MS));
        if (c.n() >= IP_MAX_FAILURES) {
            ipFailures.remove(k);
            ipBlockedUntil.put(k, now + IP_BLOCK_MS);
            return new Miss(true, 0);
        }
        ipFailures.put(k, c);
        return new Miss(false, IP_MAX_FAILURES - c.n());
    }

    private static Count bump(Count c, long now, long window) {
        return (c == null || now - c.firstAt() > window) ? new Count(1, now, now) : new Count(c.n() + 1, c.firstAt(), now);
    }

    /** Milliseconds this connection is still blocked for, or 0. */
    long ipBlockedFor(String ip, long now) {
        return remaining(ipBlockedUntil, ipKey(ip), now);
    }

    /** Milliseconds this account must wait before its next password try, or 0. */
    long accountWait(String account, long now) {
        Count c = accountFailures.get(account);
        if (c == null || now - c.firstAt() > ACCOUNT_WINDOW_MS || c.n() < ACCOUNT_FREE_FAILURES) return 0;
        return Math.max(0, c.lastAt() + ACCOUNT_DELAY_MS - now);
    }

    /** The right password: the account's own slow-down ends. The connection's count stays (see the class comment). */
    synchronized void success(String account) {
        accountFailures.remove(account);
    }

    /** Someone sat on this name at the login prompt until the timeout. */
    void timedOut(String account, String ip, long now) {
        squatBlockedUntil.put(account + "|" + ipKey(ip), now + SQUAT_BLOCK_MS);
    }

    long squatBlockedFor(String account, String ip, long now) {
        return remaining(squatBlockedUntil, account + "|" + ipKey(ip), now);
    }

    private static long remaining(Map<String, Long> m, String key, long now) {
        Long until = m.get(key);
        if (until == null) return 0;
        if (until <= now) {
            m.remove(key, until);
            return 0;
        }
        return until - now;
    }

    /** Keeps the maps from growing forever on a busy server. */
    private void prune(long now) {
        if (ipFailures.size() + accountFailures.size() + ipBlockedUntil.size() + squatBlockedUntil.size() < 10_000) return;
        ipFailures.values().removeIf(c -> now - c.firstAt() > IP_WINDOW_MS);
        accountFailures.values().removeIf(c -> now - c.firstAt() > ACCOUNT_WINDOW_MS);
        ipBlockedUntil.values().removeIf(u -> u <= now);
        squatBlockedUntil.values().removeIf(u -> u <= now);
    }

    /** "Steve" registered, "steve" joining: refused with the right spelling. Null when it's fine. */
    static String nameCaseProblem(String registeredAs, String joiningAs) {
        if (registeredAs == null || registeredAs.equals(joiningAs)) return null;
        return "This name is registered as \"" + registeredAs + "\". Join with exactly that name.";
    }
}
