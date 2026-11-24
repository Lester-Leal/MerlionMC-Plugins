package xyz.merlionmchost.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Entry point. Tracks who has logged in this session; everyone else is held (Holding) and locked down (Lockdown).
 * Fails closed: if the account database can't be opened, nobody can join rather than everyone playing unchecked.
 */
public final class MerlionMCAuth extends JavaPlugin implements Listener {
    static final int LOGIN_SECONDS = 60;

    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    // Names (lower case) of players online and logged in, readable from the async pre-login check.
    private final Set<String> loggedInNames = ConcurrentHashMap.newKeySet();
    // Names (lower case) waiting at the login prompt, and the connection each is waiting from.
    private final Map<String, String> waitingFrom = new ConcurrentHashMap<>();
    // Waiting names someone else tried to join as from another connection: only then is sitting on the name until
    // the timeout treated as squatting. A real player who is just slow to type is never blocked from coming back.
    private final Set<String> contested = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();
    private final LoginGuard guard = new LoginGuard();
    private Holding holding;
    private AccountStore store;
    private AuthCommands commands;
    // Bedrock players verified by Floodgate; "never" when Floodgate isn't installed.
    private Predicate<UUID> isBedrock = id -> false;

    @Override
    public void onEnable() {
        // First, so nothing typed after this point can leak a password into the logs.
        SensitiveLogFilter.install();
        holding = new Holding(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        new Lockdown(this).register();
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            try {
                FloodgateBedrock floodgate = new FloodgateBedrock();
                isBedrock = floodgate::isBedrock;
                getLogger().info("Floodgate found: Bedrock players (verified by Xbox) skip login");
            } catch (Throwable e) {
                // Floodgate present but its API changed: everyone logs in, which is safe.
                getLogger().log(Level.WARNING, "Floodgate is installed but couldn't be used; Bedrock players will log in like everyone else", e);
            }
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            store = new AccountStore(getDataFolder().toPath().resolve("accounts.db"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not open the account database. Players will be kicked until this is fixed.", e);
            for (Player p : Bukkit.getOnlinePlayers()) p.kick(unavailable());
            return;
        }
        commands = new AuthCommands(this, store, guard);
        for (String name : new String[] {"register", "login", "changepassword"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(commands);
                c.setTabCompleter(commands);
            }
        }
        PluginCommand admin = getCommand("mmauth");
        if (admin != null) admin.setExecutor(new AdminCommand(this, store));
        if (Bukkit.getOnlineMode()) {
            getLogger().info("This server is in online mode: Mojang already verifies every player, so nobody has to log in. "
                    + "MerlionMCAuth only matters with online mode off.");
        }
        // After a reload, players already online have to log in again.
        for (Player p : Bukkit.getOnlinePlayers()) onJoined(p, false);
        getLogger().info("MerlionMCAuth " + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) release(p);
        if (commands != null) commands.shutdown();
        loggedIn.clear();
        loggedInNames.clear();
        waitingFrom.clear();
        contested.clear();
        SensitiveLogFilter.WAITING.clear();
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Closing the account database", e);
            }
        }
    }

    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    /** Called on the main thread when /login or /register succeeds (and for players who don't need to log in). */
    void markLoggedIn(Player player) {
        loggedIn.add(player.getUniqueId());
        loggedInNames.add(AccountStore.key(player.getName()));
        release(player);
    }

    private void hold(Player player) {
        String key = AccountStore.key(player.getName());
        waitingFrom.put(key, ipOf(player));
        SensitiveLogFilter.WAITING.add(key);
        holding.hold(player);
        BukkitTask old = timeouts.put(player.getUniqueId(), Bukkit.getScheduler().runTaskLater(this, () -> {
            timeouts.remove(player.getUniqueId());
            if (player.isConnected() && !isLoggedIn(player)) {
                if (contested.contains(key)) guard.timedOut(key, ipOf(player), System.currentTimeMillis());
                player.kick(Component.text("You didn't log in within " + LOGIN_SECONDS + " seconds. Rejoin and type /login <password>.", NamedTextColor.RED));
            }
        }, LOGIN_SECONDS * 20L));
        if (old != null) old.cancel();
    }

    /** Undoes hold(): after login, on quit (before the player is saved) and when the plugin stops. */
    private void release(Player player) {
        BukkitTask timeout = timeouts.remove(player.getUniqueId());
        if (timeout != null) timeout.cancel();
        String key = AccountStore.key(player.getName());
        waitingFrom.remove(key);
        contested.remove(key);
        SensitiveLogFilter.WAITING.remove(key);
        holding.release(player);
    }

    /**
     * Before a player joins (off the main thread): refuse blocked connections, the wrong capitalisation of a registered
     * name, a second connection for a name that's already playing, and a second connection for a name that's waiting
     * at the login prompt from somewhere else. The last two matter because in offline mode a new connection with the
     * same name kicks the old one ("logged in from another location"): a troll could otherwise kick real players, or
     * keep a real player from ever finishing /login.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (store == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, unavailable());
            return;
        }
        if (Bukkit.getOnlineMode() || bedrockId(event.getUniqueId())) return; // verified by Mojang or Xbox
        String name = event.getName();
        String key = AccountStore.key(name);
        String ip = event.getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        long blockedMs = guard.ipBlockedFor(ip, now);
        if (blockedMs > 0) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Too many wrong passwords from your connection. Try again in " + minutes(blockedMs) + ".", NamedTextColor.RED));
            return;
        }
        if (guard.squatBlockedFor(key, ip, now) > 0) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("You can't join as " + name + " from this connection right now. Try again later.", NamedTextColor.RED));
            return;
        }
        if (loggedInNames.contains(key)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(name + " is already playing on this server. If that's you, wait a moment and join again.", NamedTextColor.RED));
            return;
        }
        String waiting = waitingFrom.get(key);
        if (waiting != null && !LoginGuard.ipKey(waiting).equals(LoginGuard.ipKey(ip))) {
            // Same connection: a real player reconnecting, allowed (their stale session is replaced).
            contested.add(key);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(name + " is logging in right now. If that's you, try again in a minute.", NamedTextColor.RED));
            return;
        }
        try {
            Optional<AccountStore.Account> account = store.find(name);
            String problem = LoginGuard.nameCaseProblem(account.map(AccountStore.Account::displayName).orElse(null), name);
            if (problem != null) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(problem, NamedTextColor.RED));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Checking " + name + " before join", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, unavailable());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        onJoined(event.getPlayer(), true);
    }

    private void onJoined(Player player, boolean fresh) {
        loggedIn.remove(player.getUniqueId());
        if (store == null) {
            player.kick(unavailable());
            return;
        }
        if (player.hasMetadata("NPC")) {
            markLoggedIn(player); // NPC plugins (Citizens) create fake players; they aren't people
            return;
        }
        if (Bukkit.getOnlineMode()) {
            markLoggedIn(player); // Mojang verified this player already
            return;
        }
        if (bedrock(player)) {
            markLoggedIn(player);
            if (fresh) {
                getLogger().info(player.getName() + " joined from Bedrock (verified by Xbox): no login needed");
                player.sendMessage(Component.text("Signed in with your Xbox account. Have fun!", NamedTextColor.GREEN));
            }
            return;
        }
        hold(player);
        prompt(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer());
        if (loggedIn.remove(event.getPlayer().getUniqueId())) loggedInNames.remove(AccountStore.key(event.getPlayer().getName()));
    }

    private boolean bedrock(Player player) {
        return bedrockId(player.getUniqueId());
    }

    private boolean bedrockId(UUID id) {
        try {
            return isBedrock.test(id);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Floodgate check failed for " + id + "; they log in like a Java player", e);
            return false;
        }
    }

    private void prompt(Player player) {
        // The lookup is quick, but it's still disk I/O: off the main thread.
        String name = player.getName();
        boolean playedBefore = player.hasPlayedBefore();
        long firstPlayed = player.getFirstPlayed();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Component message;
            try {
                long now = System.currentTimeMillis();
                if (store.find(name).isPresent()) {
                    message = Component.text("Welcome back! Log in with /login <password>", NamedTextColor.YELLOW);
                } else if (OwnerCodes.needsCode(store.findCode(name).filter(c -> c.expiresAt() > now).isPresent(), playedBefore, firstPlayed, store.installedAt())) {
                    message = Component.text("This name needs a code from the server owner. Ask them, then type /register <code> <password> <password>", NamedTextColor.YELLOW);
                } else {
                    message = Component.text("Welcome! Protect your name: /register <password> <same password again>", NamedTextColor.YELLOW);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Looking up " + name, e);
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isConnected() && !isLoggedIn(player)) player.sendMessage(message);
            });
        });
    }

    static String ipOf(Player player) {
        return player.getAddress() == null ? "" : player.getAddress().getAddress().getHostAddress();
    }

    private static String minutes(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000);
        return m + (m == 1 ? " minute" : " minutes");
    }

    private static Component unavailable() {
        return Component.text("The login system is unavailable right now. Please tell the server owner.", NamedTextColor.RED);
    }
}
