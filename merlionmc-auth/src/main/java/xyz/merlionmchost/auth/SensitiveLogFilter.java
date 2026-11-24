package xyz.merlionmchost.auth;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps passwords out of the console and log files. Paper logs every player command ("Steve issued server command:
 * /login hunter2"); on MerlionMC the server owner reads those logs in the dashboard's Console tab. Those lines are
 * dropped; the plugin logs "Steve logged in" itself instead.
 */
final class SensitiveLogFilter extends AbstractFilter {
    private static final Pattern SENSITIVE = Pattern.compile(
            "issued server command: /(?:merlionmcauth:)?(?:login|l|log|register|reg|changepassword|changepw)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    /** "Steve issued server command: ..." */
    private static final Pattern COMMAND_BY = Pattern.compile("^(\\S+) issued server command: ");

    /** Names (lower case) of players waiting to log in: every command they type is dropped, typos like /logn too. */
    static final Set<String> WAITING = ConcurrentHashMap.newKeySet();

    static boolean isSensitive(String line) {
        if (line == null) return false;
        if (SENSITIVE.matcher(line).find()) return true;
        Matcher m = COMMAND_BY.matcher(line);
        return m.find() && WAITING.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    /** Adds the filter to the root logger, which the server's command logging goes through. */
    static SensitiveLogFilter install() {
        SensitiveLogFilter f = new SensitiveLogFilter();
        ((Logger) LogManager.getRootLogger()).addFilter(f);
        f.start();
        return f;
    }

    private Result check(String text) {
        return isSensitive(text) ? Result.DENY : Result.NEUTRAL;
    }

    @Override
    public Result filter(LogEvent event) {
        return event == null || event.getMessage() == null ? Result.NEUTRAL : check(event.getMessage().getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : check(msg.getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : check(msg.toString());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return check(msg);
    }
}
