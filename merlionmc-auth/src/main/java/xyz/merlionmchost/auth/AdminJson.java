package xyz.merlionmchost.auth;

import java.util.List;
import java.util.function.Predicate;

/**
 * The one-line answer to "mmauth json", read by the MerlionMC dashboard over RCON. Prefixed so it can be found
 * among other output, small enough per page to fit one RCON reply, and without IPs or password hashes.
 */
final class AdminJson {
    static final String PREFIX = "MMAUTH-JSON ";
    static final int PAGE_SIZE = 25;

    private AdminJson() {}

    static String page(List<AccountStore.Account> accounts, int total, int page, Predicate<String> online) {
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        StringBuilder b = new StringBuilder(PREFIX).append("{\"v\":1,\"total\":").append(total)
                .append(",\"page\":").append(page).append(",\"pages\":").append(pages).append(",\"accounts\":[");
        for (int i = 0; i < accounts.size(); i++) {
            AccountStore.Account a = accounts.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(string(a.displayName()))
                    .append(",\"registered\":").append(a.registeredAt())
                    .append(",\"lastLogin\":").append(a.lastLoginAt())
                    .append(",\"online\":").append(online.test(a.displayName()))
                    .append('}');
        }
        return b.append("]}").toString();
    }

    /** A JSON string. Names are letters, digits, "_" and "." today, but nothing here assumes it. */
    static String string(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
