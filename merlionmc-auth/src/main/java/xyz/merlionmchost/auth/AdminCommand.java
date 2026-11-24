package xyz.merlionmchost.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

/**
 * /mmauth for the server console, RCON (how the MerlionMC dashboard calls it) and operators:
 *   mmauth list [page]          accounts, readable
 *   mmauth json [page]          the same as one JSON line (AdminJson); no dashes, because rcon-cli would read
 *                               "--json" as one of its own options
 *   mmauth reset <player>       deletes the password and issues a one-time code (kicked if online): only the
 *                               person the owner gives the code to can register the name again
 *   mmauth code <player>        a one-time code for a name with no account yet that needs one (it played here
 *                               before the plugin was installed)
 * Codes come back on a line "MMAUTH-CODE ABCD-2345" for the dashboard to show the owner.
 * Runs on the main thread on purpose: RCON only returns what the command says before it finishes. These are quick
 * single-table SQLite queries with no password hashing.
 */
final class AdminCommand implements TabExecutor {
    private final MerlionMCAuth plugin;
    private final AccountStore store;

    AdminCommand(MerlionMCAuth plugin, AccountStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Server console and RCON (how the MerlionMC dashboard calls it) only, never a player, operators included:
        // whoever can reset an account and get its code could take that name over.
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !(sender instanceof org.bukkit.command.RemoteConsoleCommandSender)) {
            sender.sendMessage("This is only for the server console and the MerlionMC dashboard.");
            return true;
        }
        try {
            if (args.length >= 1 && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("json"))) {
                list(sender, args[0].equalsIgnoreCase("json"), parsePage(args.length >= 2 ? args[1] : "1"));
                return true;
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("unregister"))) {
                reset(sender, args[1]);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("code")) {
                issueCode(sender, args[1]);
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "mmauth " + String.join(" ", args), e);
            sender.sendMessage("MMAUTH-ERROR " + e.getClass().getSimpleName());
            return true;
        }
        sender.sendMessage("Use: /mmauth list [page] | /mmauth json [page] | /mmauth reset <player> | /mmauth code <player>");
        return true;
    }

    private static int parsePage(String s) {
        try {
            return Math.max(1, Math.min(100_000, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void list(CommandSender sender, boolean json, int page) throws Exception {
        int total = store.count();
        List<AccountStore.Account> accounts = store.list((page - 1) * AdminJson.PAGE_SIZE, AdminJson.PAGE_SIZE);
        if (json) {
            sender.sendMessage(AdminJson.page(accounts, total, page, name -> Bukkit.getPlayerExact(name) != null));
            return;
        }
        int pages = Math.max(1, (total + AdminJson.PAGE_SIZE - 1) / AdminJson.PAGE_SIZE);
        sender.sendMessage(total + " registered " + (total == 1 ? "account" : "accounts") + " (page " + page + " of " + pages + "):");
        SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (AccountStore.Account a : accounts) {
            sender.sendMessage(" - " + a.displayName() + "  registered " + date.format(new Date(a.registeredAt())) + ", last login " + date.format(new Date(a.lastLoginAt())));
        }
    }

    private String newCode(String name) throws Exception {
        String code = OwnerCodes.generate();
        store.putCode(name, OwnerCodes.hash(code), System.currentTimeMillis() + OwnerCodes.VALID_MS);
        return code;
    }

    private void issueCode(CommandSender sender, String name) throws Exception {
        if (store.find(name).isPresent()) {
            sender.sendMessage("MMAUTH-EXISTS " + name + " already has an account. Use /mmauth reset " + name + " if they forgot their password.");
            return;
        }
        String code = newCode(name);
        plugin.getLogger().info("A registration code for " + name + " was issued by " + sender.getName());
        sender.sendMessage("MMAUTH-OK Give this code to " + name + ". It works once, for 24 hours: /register <code> <password> <password>");
        sender.sendMessage("MMAUTH-CODE " + code);
    }

    private void reset(CommandSender sender, String name) throws Exception {
        if (!store.delete(name)) {
            sender.sendMessage("MMAUTH-NOT-FOUND No account called " + name);
            return;
        }
        String code = newCode(name);
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(name)) online = p;
        }
        if (online != null) {
            online.kick(Component.text("Your password was reset by the server owner. Ask them for your code, then rejoin and type /register <code> <password> <password>.", NamedTextColor.YELLOW));
        }
        plugin.getLogger().info("Account " + name + " was reset by " + sender.getName());
        sender.sendMessage("MMAUTH-OK Reset " + name + ". Give them this code. It works once, for 24 hours: /register <code> <password> <password>");
        sender.sendMessage("MMAUTH-CODE " + code);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("list", "json", "reset", "code");
        return List.of();
    }
}
