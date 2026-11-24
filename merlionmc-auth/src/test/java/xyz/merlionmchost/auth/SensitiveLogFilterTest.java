package xyz.merlionmchost.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveLogFilterTest {

    @Test
    void hidesAuthCommandsInAnyCaseOrForm() {
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /login hunter2"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /LOGIN hunter2"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /l hunter2"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /register a b"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /reg a b"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /merlionmcauth:login hunter2"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /changepassword old new"));
        assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /login"));
    }

    @Test
    void leavesOtherLinesAlone() {
        assertFalse(SensitiveLogFilter.isSensitive("Steve issued server command: /list"));
        assertFalse(SensitiveLogFilter.isSensitive("Steve issued server command: /logout"));
        assertFalse(SensitiveLogFilter.isSensitive("Steve issued server command: /loginhistory"));
        assertFalse(SensitiveLogFilter.isSensitive("Steve joined the game"));
        assertFalse(SensitiveLogFilter.isSensitive("<Steve> how do I /login?"), "chat isn't a command line");
        assertFalse(SensitiveLogFilter.isSensitive(null));
    }

    @Test
    void dropsEveryCommandFromSomeoneWaitingToLogInSoTyposCantLeak() {
        SensitiveLogFilter.WAITING.add("steve");
        try {
            assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /logn hunter2"));
            assertTrue(SensitiveLogFilter.isSensitive("Steve issued server command: /regsiter a a"));
            assertFalse(SensitiveLogFilter.isSensitive("Alex issued server command: /logn hunter2"), "Alex is logged in");
        } finally {
            SensitiveLogFilter.WAITING.remove("steve");
        }
        assertFalse(SensitiveLogFilter.isSensitive("Steve issued server command: /spawn"), "after login, normal commands are logged again");
    }

    @Test
    void reallyDropsTheLineFromLog4jOutput() {
        List<String> written = new ArrayList<>();
        AbstractAppender capture = new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                written.add(event.getMessage().getFormattedMessage());
            }
        };
        capture.start();
        // Like Paper: the console and log file are appenders on the root logger configuration.
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig rootConfig = ctx.getConfiguration().getRootLogger();
        rootConfig.addAppender(capture, Level.INFO, null);
        rootConfig.setLevel(Level.INFO);
        ctx.updateLoggers();
        try {
            SensitiveLogFilter.install();
            org.apache.logging.log4j.Logger server = LogManager.getLogger("net.minecraft.server.network.ServerGamePacketListenerImpl");
            server.info("{} issued server command: {}", "Steve", "/login hunter2");
            server.info("{} issued server command: {}", "Steve", "/list");
            assertTrue(written.stream().anyMatch(l -> l.contains("/list")), "other commands still logged: " + written);
            assertTrue(written.stream().noneMatch(l -> l.contains("hunter2")), "password line dropped: " + written);
        } finally {
            rootConfig.removeAppender("capture");
            ctx.updateLoggers();
        }
    }

    @Test
    void authCommandCheck() {
        assertTrue(Lockdown.isAuthCommand("/login hunter2"));
        assertTrue(Lockdown.isAuthCommand("/L hunter2"));
        assertTrue(Lockdown.isAuthCommand("/MerlionMCAuth:register a b"));
        assertTrue(Lockdown.isAuthCommand("/reg"));
        assertFalse(Lockdown.isAuthCommand("/loginx hunter2"));
        assertFalse(Lockdown.isAuthCommand("/me hi"));
        assertFalse(Lockdown.isAuthCommand("/minecraft:login"));
        assertFalse(Lockdown.isAuthCommand("/spawn"));
    }
}
