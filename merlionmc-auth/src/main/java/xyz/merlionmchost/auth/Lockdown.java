package xyz.merlionmchost.auth;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent;
import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent;
import com.destroystokyo.paper.event.player.*;
import com.destroystokyo.paper.loottable.LootableInventoryReplenishEvent;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.event.player.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a player can't do before logging in: nearly everything. Every cancellable player event in the Paper API is
 * either in LOCKED (cancelled), handled specially (movement, teleports, commands, chat), or in ALLOWED with a reason;
 * LockdownCoverageTest fails if Paper adds one that's in none of them.
 */
// Deprecated events still fire, so they are locked too.
@SuppressWarnings({"removal", "deprecation"})
final class Lockdown {
    /** Cancelled outright for a player who hasn't logged in. */
    static final List<Class<? extends Event>> LOCKED = List.of(
            AsyncPlayerSendSuggestionsEvent.class, PlayerAdvancementCriterionGrantEvent.class, PlayerAttackEntityCooldownResetEvent.class,
            PlayerElytraBoostEvent.class, PlayerJumpEvent.class, PlayerLaunchProjectileEvent.class, PlayerPickupExperienceEvent.class,
            PlayerReadyArrowEvent.class, com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent.class, org.bukkit.event.player.PlayerRecipeBookClickEvent.class, PlayerSetSpawnEvent.class, PlayerStartSpectatingEntityEvent.class,
            PlayerStopSpectatingEntityEvent.class, PlayerShearBlockEvent.class, UncheckedSignChangeEvent.class, ChatEvent.class,
            PlayerArmSwingEvent.class, PlayerBedFailEnterEvent.class, PlayerChangeBeaconEffectEvent.class, PlayerDeepSleepEvent.class,
            PlayerFlowerPotManipulateEvent.class, PlayerInsertLecternBookEvent.class, PlayerItemCooldownEvent.class,
            PlayerItemFrameChangeEvent.class, PlayerItemGroupCooldownEvent.class, PlayerLecternPageChangeEvent.class,
            PlayerLoomPatternSelectEvent.class, PlayerNameEntityEvent.class, PlayerOpenSignEvent.class, PlayerPickBlockEvent.class,
            PlayerPickEntityEvent.class, PlayerPurchaseEvent.class, PlayerShieldDisableEvent.class, PlayerSignCommandPreprocessEvent.class,
            PlayerStonecutterRecipeSelectEvent.class, PlayerSwapWithEquipmentSlotEvent.class, PlayerToggleEntityAgeLockEvent.class,
            PlayerTradeEvent.class, PrePlayerAttackEntityEvent.class, PlayerAnimationEvent.class, PlayerArmorStandManipulateEvent.class,
            PlayerAttemptPickupItemEvent.class, PlayerBedEnterEvent.class, PlayerBedLeaveEvent.class, PlayerBucketEmptyEvent.class,
            PlayerBucketEntityEvent.class, PlayerBucketFillEvent.class, PlayerBucketFishEvent.class, PlayerDropItemEvent.class,
            PlayerEditBookEvent.class, PlayerFishEvent.class, PlayerHarvestBlockEvent.class, PlayerInteractAtEntityEvent.class,
            PlayerInteractEntityEvent.class, PlayerInteractEvent.class, PlayerItemConsumeEvent.class, PlayerItemDamageEvent.class,
            PlayerItemHeldEvent.class, PlayerItemMendEvent.class, PlayerPickupArrowEvent.class, PlayerPickupItemEvent.class,
            PlayerRecipeDiscoverEvent.class, PlayerRiptideEvent.class, PlayerShearEntityEvent.class, PlayerSignOpenEvent.class,
            PlayerSpawnChangeEvent.class, PlayerStatisticIncrementEvent.class, PlayerSwapHandItemsEvent.class,
            PlayerTakeLecternBookEvent.class, PlayerToggleFlightEvent.class, PlayerToggleSneakEvent.class, PlayerToggleSprintEvent.class,
            PlayerVelocityEvent.class);

    /** Handled by their own rules below (subclasses included: PlayerTeleportEvent covers portals and end gateways). */
    static final List<Class<? extends Event>> SPECIAL = List.of(
            PlayerMoveEvent.class, PlayerTeleportEvent.class, PlayerCommandPreprocessEvent.class, AsyncChatEvent.class);

    /** Left alone on purpose. */
    static final Map<Class<?>, String> ALLOWED = Map.of(
            PlayerKickEvent.class, "our own kicks (timeout, wrong passwords) must go through",
            PlayerGameModeChangeEvent.class, "changed by the server or an admin, not by the player",
            PlayerTrackEntityEvent.class, "decides which entities the client is sent; cancelling it hides the world, even after login",
            PlayerNaturallySpawnCreaturesEvent.class, "mob spawning around a player, not something the player does",
            LootableInventoryReplenishEvent.class, "loot chests refilling, not something the player does",
            PlayerAdvancementCriterionRevokeEvent.class, "taking progress away is an admin action",
            AsyncPlayerChatEvent.class, "deprecated chat event; chat is stopped through AsyncChatEvent, and listening to this switches Paper to its legacy chat path",
            AsyncPlayerChatPreviewEvent.class, "deprecated chat preview; chat itself is stopped through AsyncChatEvent",
            PlayerChatEvent.class, "deprecated synchronous chat; chat is stopped through AsyncChatEvent");

    /** The only commands a player can use before logging in (plugin.yml commands and aliases). */
    static final Set<String> AUTH_COMMANDS = Set.of("login", "l", "log", "register", "reg");

    static boolean isCovered(Class<?> event) {
        for (Class<?> c : LOCKED) if (c.isAssignableFrom(event)) return true;
        for (Class<?> c : SPECIAL) if (c.isAssignableFrom(event)) return true;
        return ALLOWED.containsKey(event);
    }

    /** "/login x", "/MerlionMCAuth:LOGIN x" and "/l" are auth commands; "/loginx" and "/me" are not. */
    static boolean isAuthCommand(String message) {
        String first = message.startsWith("/") ? message.substring(1) : message;
        int space = first.indexOf(' ');
        String label = (space < 0 ? first : first.substring(0, space)).toLowerCase(Locale.ROOT);
        if (label.startsWith("merlionmcauth:")) label = label.substring("merlionmcauth:".length());
        return AUTH_COMMANDS.contains(label);
    }

    private final MerlionMCAuth plugin;
    private final Listener owner = new Listener() {};

    Lockdown(MerlionMCAuth plugin) {
        this.plugin = plugin;
    }

    private boolean locked(Entity e) {
        return e instanceof Player p && !plugin.isLoggedIn(p);
    }

    void register() {
        PluginManager pm = plugin.getServer().getPluginManager();
        for (Class<? extends Event> type : LOCKED) {
            // One handler for the whole list; it checks the type itself because subclasses can share a handler list.
            pm.registerEvent(type, owner, EventPriority.HIGHEST, (l, e) -> {
                if (type.isInstance(e) && e instanceof PlayerEvent pe && e instanceof Cancellable c && locked(pe.getPlayer())) c.setCancelled(true);
            }, plugin, false);
        }

        on(pm, PlayerMoveEvent.class, e -> {
            // Looking around is fine; moving isn't.
            if (locked(e.getPlayer()) && e.hasChangedPosition()) {
                Location back = e.getFrom().clone();
                back.setYaw(e.getTo().getYaw());
                back.setPitch(e.getTo().getPitch());
                e.setTo(back);
            }
            return null;
        });
        on(pm, PlayerTeleportEvent.class, e -> {
            // Teleports by the server or a plugin (spawn on join, an admin) go through; ones the player causes don't.
            String cause = e.getCause().name();
            if (locked(e.getPlayer()) && !(cause.equals("PLUGIN") || cause.equals("COMMAND") || cause.equals("UNKNOWN"))) e.setCancelled(true);
            return null;
        });
        on(pm, PlayerCommandPreprocessEvent.class, e -> {
            if (locked(e.getPlayer()) && !(isAuthCommand(e.getMessage()) && ownCommand(e.getMessage()))) {
                e.setCancelled(true);
                remind(e.getPlayer());
            }
            return null;
        });
        on(pm, AsyncChatEvent.class, e -> {
            if (locked(e.getPlayer())) {
                e.setCancelled(true);
                remind(e.getPlayer());
            }
            // Nobody waiting at the login prompt reads other people's chat (they might not be who they say).
            e.viewers().removeIf(a -> a instanceof Player p && locked(p));
            return null;
        });

        // Not player events, but things that happen to or through a player.
        on(pm, EntityDamageEvent.class, e -> {
            if (locked(e.getEntity())) e.setCancelled(true);
            if (e instanceof EntityDamageByEntityEvent by) {
                Entity damager = by.getDamager();
                if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;
                if (locked(damager)) e.setCancelled(true);
            }
            return null;
        });
        on(pm, EntityTargetEvent.class, e -> cancelIf(e, locked(e.getTarget())));
        on(pm, EntityPickupItemEvent.class, e -> cancelIf(e, locked(e.getEntity())));
        on(pm, FoodLevelChangeEvent.class, e -> cancelIf(e, locked(e.getEntity())));
        on(pm, InventoryOpenEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, InventoryClickEvent.class, e -> cancelIf(e, locked(e.getWhoClicked())));
        on(pm, InventoryDragEvent.class, e -> cancelIf(e, locked(e.getWhoClicked())));
        on(pm, BlockBreakEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, BlockDamageEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, BlockPlaceEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, BlockIgniteEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, SignChangeEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, HangingPlaceEvent.class, e -> cancelIf(e, locked(e.getPlayer())));
        on(pm, HangingBreakByEntityEvent.class, e -> cancelIf(e, locked(e.getRemover())));
        on(pm, VehicleEnterEvent.class, e -> cancelIf(e, locked(e.getEntered())));
        on(pm, EntityMountEvent.class, e -> cancelIf(e, locked(e.getEntity())));
    }

    private static Void cancelIf(Cancellable e, boolean cancel) {
        if (cancel) e.setCancelled(true);
        return null;
    }

    private <T extends Event> void on(PluginManager pm, Class<T> type, Function<T, Void> handler) {
        pm.registerEvent(type, owner, EventPriority.HIGHEST, (l, e) -> {
            if (type.isInstance(e)) handler.apply(type.cast(e));
        }, plugin, false);
    }

    /**
     * The typed label really is one of ours: an alias like /l or /log loses to another plugin's command of that name
     * (a chat plugin's "local" channel, say), which must not run before login.
     */
    private boolean ownCommand(String message) {
        String first = message.startsWith("/") ? message.substring(1) : message;
        int space = first.indexOf(' ');
        String label = (space < 0 ? first : first.substring(0, space)).toLowerCase(Locale.ROOT);
        org.bukkit.command.Command c = plugin.getServer().getCommandMap().getCommand(label);
        return c instanceof org.bukkit.command.PluginCommand pc && pc.getPlugin() == plugin;
    }

    private void remind(Player player) {
        // Chat arrives off the main thread; sending a message is thread-safe in Paper.
        player.sendMessage(Component.text("Log in first: /login <password> (new here? /register <password> <password>)", NamedTextColor.RED));
    }
}
