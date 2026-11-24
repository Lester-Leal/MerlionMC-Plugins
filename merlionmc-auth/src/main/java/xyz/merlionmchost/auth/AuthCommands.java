package xyz.merlionmchost.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * /register, /login and /changepassword. Password hashing (Argon2id, 19 MiB each) runs on two worker threads with a
 * short queue, so a crowd of bots registering at once can't run the server out of memory: past that, "busy, try
 * again". One request per player at a time. Results come back on the main thread, and only to the exact connection
 * that asked (a new connection with the same name gets nothing from it).
 */
public final class AuthCommands implements TabExecutor {
    private final MerlionMCAuth plugin;
    private final AccountStore store;
    private final LoginGuard guard;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor hashing = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "MerlionMCAuth-hashing");
        t.setDaemon(true);
        return t;
    });

    AuthCommands(MerlionMCAuth plugin, AccountStore store, LoginGuard guard) {
        this.plugin = plugin;
        this.store = store;
        this.guard = guard;
    }

    void shutdown() {
        hashing.shutdownNow();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Never let an exception escape: Bukkit would log the whole command line, password included.
        try {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this.");
                return true;
            }
            if (command.getName().equals("changepassword")) {
                if (!plugin.isLoggedIn(player)) say(player, "Log in first: /login <password>", NamedTextColor.RED);
                else changePassword(player, args);
                return true;
            }
            if (plugin.isLoggedIn(player)) {
                say(player, "You're already logged in.", NamedTextColor.GRAY);
                return true;
            }
            switch (command.getName()) {
                case "register" -> register(player, args);
                case "login" -> login(player, args);
                default -> { }
            }
        } catch (Throwable e) {
            plugin.getLogger().log(Level.SEVERE, "Login command failed for " + sender.getName() + " (" + command.getName() + ")", e);
        }
        return true;
    }

    /** Nothing to suggest, and nothing typed into these commands goes anywhere else. */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }

    /**
     * /register <password> <password>, or /register <code> <password> <password> for a name that needs the owner's
     * one-time code (OwnerCodes): after a reset, or a name that played here before the plugin was installed.
     */
    private void register(Player player, String[] args) {
        if (args.length != 2 && args.length != 3) {
            say(player, "Use: /register <password> <same password again>", NamedTextColor.RED);
            return;
        }
        String code = args.length == 3 ? args[0] : null;
        String password = args[args.length - 2];
        String problem = PasswordRules.problem(player.getName(), password, args[args.length - 1]);
        if (problem != null) {
            say(player, problem, NamedTextColor.RED);
            return;
        }
        String key = AccountStore.key(player.getName());
        long wait = guard.accountWait(key, System.currentTimeMillis());
        if (code != null && wait > 0) {
            say(player, "Too many wrong codes for this name. Wait " + ((wait + 999) / 1000) + " seconds and try again.", NamedTextColor.RED);
            return;
        }
        String ip = MerlionMCAuth.ipOf(player);
        boolean playedBefore = player.hasPlayedBefore();
        long firstPlayed = player.getFirstPlayed();
        async(player, () -> {
            if (store.find(player.getName()).isPresent()) {
                return () -> say(player, "This name is already registered. Use /login <password>.", NamedTextColor.RED);
            }
            long now = System.currentTimeMillis();
            Optional<AccountStore.Code> pending = store.findCode(player.getName()).filter(c -> c.expiresAt() > now);
            boolean needsCode = OwnerCodes.needsCode(pending.isPresent(), playedBefore, firstPlayed, store.installedAt());
            boolean created;
            if (needsCode) {
                if (code == null) {
                    return () -> say(player, "This name needs a code from the server owner first. Ask them, then type: /register <code> <password> <password>", NamedTextColor.YELLOW);
                }
                if (pending.isEmpty()) {
                    return () -> say(player, "There's no valid code for this name (codes last 24 hours). Ask the server owner for a new one.", NamedTextColor.RED);
                }
                String codeHash = OwnerCodes.hash(code);
                if (!java.security.MessageDigest.isEqual(codeHash.getBytes(), pending.get().hash().getBytes())) return wrongPassword(player, ip);
                created = store.createWithCode(player.getName(), codeHash, PasswordHasher.hash(password), ip, now);
                if (created) guard.success(key);
            } else {
                if (code != null) return () -> say(player, "No code needed for this name. Use: /register <password> <same password again>", NamedTextColor.RED);
                created = store.create(player.getName(), PasswordHasher.hash(password), ip, now);
            }
            if (!created) return () -> say(player, "This name was just registered. Use /login <password>.", NamedTextColor.RED);
            return () -> {
                plugin.markLoggedIn(player);
                plugin.getLogger().info(player.getName() + " registered" + (needsCode ? " with the owner's code" : ""));
                say(player, "Registered and logged in. Use this password every time you join.", NamedTextColor.GREEN);
            };
        });
    }

    private void login(Player player, String[] args) {
        if (args.length != 1) {
            say(player, "Use: /login <password>", NamedTextColor.RED);
            return;
        }
        String key = AccountStore.key(player.getName());
        long wait = guard.accountWait(key, System.currentTimeMillis());
        if (wait > 0) {
            say(player, "Too many wrong passwords for this name. Wait " + ((wait + 999) / 1000) + " seconds and try again.", NamedTextColor.RED);
            return;
        }
        String password = args[0];
        String ip = MerlionMCAuth.ipOf(player);
        async(player, () -> {
            Optional<AccountStore.Account> account = store.find(player.getName());
            if (account.isEmpty()) return () -> say(player, "This name isn't registered yet. Use /register <password> <password>.", NamedTextColor.RED);
            if (!PasswordHasher.verify(password, account.get().hash())) return wrongPassword(player, ip);
            guard.success(key);
            store.recordLogin(player.getName(), ip, System.currentTimeMillis());
            return () -> {
                plugin.markLoggedIn(player);
                plugin.getLogger().info(player.getName() + " logged in");
                say(player, "Logged in. Welcome back!", NamedTextColor.GREEN);
            };
        });
    }

    private void changePassword(Player player, String[] args) {
        if (args.length != 2) {
            say(player, "Use: /changepassword <current password> <new password>", NamedTextColor.RED);
            return;
        }
        String current = args[0];
        String next = args[1];
        String problem = PasswordRules.problem(player.getName(), next, next);
        if (problem != null) {
            say(player, problem, NamedTextColor.RED);
            return;
        }
        if (next.equals(current)) {
            say(player, "That's your current password.", NamedTextColor.RED);
            return;
        }
        String ip = MerlionMCAuth.ipOf(player);
        async(player, () -> {
            Optional<AccountStore.Account> account = store.find(player.getName());
            if (account.isEmpty()) return () -> say(player, "This name isn't registered.", NamedTextColor.RED);
            // A wrong current password counts like a wrong login: someone at an unattended computer can't guess at it.
            if (!PasswordHasher.verify(current, account.get().hash())) return wrongPassword(player, ip);
            store.updateHash(player.getName(), PasswordHasher.hash(next));
            return () -> {
                plugin.getLogger().info(player.getName() + " changed their password");
                say(player, "Password changed. Use the new one from now on.", NamedTextColor.GREEN);
            };
        });
    }

    /** Counts the miss for this connection and account; when the connection is blocked, the player is kicked. */
    private Runnable wrongPassword(Player player, String ip) {
        LoginGuard.Miss miss = guard.failure(ip, AccountStore.key(player.getName()), System.currentTimeMillis());
        return () -> {
            plugin.getLogger().info(player.getName() + " typed a wrong password" + (miss.ipBlocked() ? " (connection blocked for 10 minutes)" : ""));
            if (miss.ipBlocked()) {
                player.kick(Component.text("Too many wrong passwords. Try again in 10 minutes.", NamedTextColor.RED));
            } else {
                int left = miss.ipTriesLeft();
                say(player, "Wrong password. " + left + (left == 1 ? " try" : " tries") + " left.", NamedTextColor.RED);
            }
        };
    }

    private interface Work {
        Runnable run() throws Exception;
    }

    /** Runs the slow part on the hashing threads, then its result on the main thread for the same connection only. */
    private void async(Player player, Work work) {
        UUID id = player.getUniqueId();
        if (!busy.add(id)) {
            say(player, "Just a moment...", NamedTextColor.GRAY);
            return;
        }
        try {
            hashing.execute(() -> {
                Runnable result;
                try {
                    result = work.run();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Login system error for " + player.getName(), e);
                    result = () -> say(player, "Something went wrong. Please try again.", NamedTextColor.RED);
                } finally {
                    busy.remove(id);
                }
                Runnable done = result;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // isConnected is this connection; isOnline would also be true for a NEW connection with the same
                    // name (same offline UUID), which must not be logged in by the old one's password.
                    if (player.isConnected() && Bukkit.getPlayer(id) == player) done.run();
                });
            });
        } catch (RejectedExecutionException e) {
            busy.remove(id);
            say(player, "The server is busy. Please try again in a few seconds.", NamedTextColor.YELLOW);
        }
    }

    static void say(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text(text, color));
    }
}
