package xyz.merlionmchost.auth;

import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every action a player can take is a PlayerEvent. This scans the whole Paper API and fails if a cancellable one is
 * neither locked before login nor deliberately allowed (with a reason). When Paper adds events (a version bump), this
 * test is what catches it.
 */
class LockdownCoverageTest {

    static List<Class<?>> cancellablePlayerEvents() throws Exception {
        URL url = PlayerEvent.class.getResource("PlayerEvent.class");
        assertNotNull(url);
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        List<Class<?>> out = new ArrayList<>();
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.contains("$") || name.startsWith("META-INF")) continue;
                Class<?> c;
                try {
                    c = Class.forName(name.replace('/', '.').replace(".class", ""), false, PlayerEvent.class.getClassLoader());
                } catch (Throwable t) {
                    continue;
                }
                if (PlayerEvent.class.isAssignableFrom(c) && Cancellable.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers())) out.add(c);
            }
        }
        return out;
    }

    @Test
    void everyCancellablePlayerEventIsLockedOrDeliberatelyAllowed() throws Exception {
        List<Class<?>> all = cancellablePlayerEvents();
        assertTrue(all.size() > 30, "found " + all.size() + " (scan broken?)");
        TreeSet<String> missing = new TreeSet<>();
        for (Class<?> c : all) {
            if (!Lockdown.isCovered(c)) missing.add(c.getName());
        }
        assertTrue(missing.isEmpty(), "Not locked and not allowed (" + missing.size() + "):\n" + String.join("\n", missing));
    }

    @Test
    void allowedEventsHaveAReason() {
        Lockdown.ALLOWED.forEach((c, why) -> assertFalse(why.isBlank(), c.getName()));
    }
}
