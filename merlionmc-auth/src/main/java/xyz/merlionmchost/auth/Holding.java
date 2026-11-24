package xyz.merlionmchost.auth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * What a player who hasn't logged in yet is kept from seeing: where the real player is and what they carry.
 * On hold, the player's location, inventory, flight permission and any blindness they already had are saved into
 * their own player data (Paper's persistent data container), and only then is the inventory emptied and the player
 * moved to the world spawn. Saved state and the player are written in the same player file, so a crash can't lose
 * items: either the old inventory is still on the player, or the saved copy is. release() puts everything back.
 * If saved state is already there on join (the server went down while they were held), it's the real state: kept.
 */
final class Holding {
    private final NamespacedKey inventory;
    private final NamespacedKey location;
    private final NamespacedKey flight;
    private final NamespacedKey blindness;
    static final int BLIND_TICKS = (MerlionMCAuth.LOGIN_SECONDS + 30) * 20; // runs out on its own if anything goes wrong

    Holding(Plugin plugin) {
        inventory = new NamespacedKey(plugin, "held_inventory");
        location = new NamespacedKey(plugin, "held_location");
        flight = new NamespacedKey(plugin, "held_flight");
        blindness = new NamespacedKey(plugin, "held_blindness");
    }

    boolean isHeld(Player p) {
        return p.getPersistentDataContainer().has(inventory, PersistentDataType.BYTE_ARRAY);
    }

    void hold(Player p) {
        PersistentDataContainer data = p.getPersistentDataContainer();
        if (p.isInsideVehicle()) p.leaveVehicle(); // a rejoin on a horse or boat could otherwise still steer it
        if (!isHeld(p)) {
            ItemStack[] contents = Arrays.stream(p.getInventory().getContents()).map(i -> i == null ? ItemStack.empty() : i).toArray(ItemStack[]::new);
            data.set(inventory, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(contents));
            if (!p.isDead()) data.set(location, PersistentDataType.STRING, encode(p.getLocation()));
            data.set(flight, PersistentDataType.BYTE, (byte) (p.getAllowFlight() ? 1 : 0));
            PotionEffect had = p.getPotionEffect(PotionEffectType.BLINDNESS);
            if (had != null) data.set(blindness, PersistentDataType.STRING, had.getDuration() + ";" + had.getAmplifier());
        }
        // Saved first, emptied second.
        p.getInventory().clear();
        p.setAllowFlight(true); // standing still in the air must not count as "flying" and kick them
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, BLIND_TICKS, 0, false, false, false));
        if (!p.isDead()) p.teleport(limbo());
    }

    void release(Player p) {
        PersistentDataContainer data = p.getPersistentDataContainer();
        if (!isHeld(p)) return;
        ItemStack[] saved = ItemStack.deserializeItemsFromBytes(data.get(inventory, PersistentDataType.BYTE_ARRAY));
        // Anything they picked up meanwhile (an admin's /give) isn't lost: it goes back in after, or drops if full.
        ItemStack[] meanwhile = Arrays.stream(p.getInventory().getContents()).filter(i -> i != null && !i.isEmpty()).toArray(ItemStack[]::new);
        p.getInventory().setContents(saved);
        Map<Integer, ItemStack> left = p.getInventory().addItem(meanwhile);
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));

        Location back = decode(data.get(location, PersistentDataType.STRING));
        if (back != null && !p.isDead()) p.teleport(back);
        Byte couldFly = data.get(flight, PersistentDataType.BYTE);
        p.setAllowFlight(couldFly != null && couldFly == 1);
        if (couldFly == null || couldFly == 0) p.setFlying(false);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        String hadBlind = data.get(blindness, PersistentDataType.STRING);
        if (hadBlind != null) {
            String[] d = hadBlind.split(";");
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.parseInt(d[0]), Integer.parseInt(d[1])));
        }
        for (NamespacedKey k : new NamespacedKey[] {inventory, location, flight, blindness}) data.remove(k);
    }

    private static Location limbo() {
        return Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    static String encode(Location l) {
        return l.getWorld().getUID() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    static Location decode(String s) {
        if (s == null) return null;
        try {
            String[] d = s.split(";");
            World w = Bukkit.getWorld(UUID.fromString(d[0]));
            if (w == null) return null; // that world is gone: they stay where they are rather than nowhere
            return new Location(w, Double.parseDouble(d[1]), Double.parseDouble(d[2]), Double.parseDouble(d[3]), Float.parseFloat(d[4]), Float.parseFloat(d[5]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
