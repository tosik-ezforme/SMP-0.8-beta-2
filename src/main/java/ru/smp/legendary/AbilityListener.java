package ru.smp.legendary;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Управление: Shift+ПКМ — первая способность, F — вторая. */
public final class AbilityListener implements Listener {

    private final LegendaryPlugin plugin;
    private final Set<UUID> using = new HashSet<>();

    public AbilityListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = e.getPlayer();
        if (!p.isSneaking()) {
            return;
        }
        Weapon w = plugin.items().weaponOf(p.getInventory().getItemInMainHand());
        if (w == null) {
            return;
        }
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && plugin.altars().at(e.getClickedBlock()) != null) {
            return;
        }
        e.setCancelled(true);
        use(p, w, false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        Weapon w = plugin.items().weaponOf(p.getInventory().getItemInMainHand());
        if (w != null) {
            boolean unlocked = plugin.progress().unlocked(p.getUniqueId(), w.id);
            String behavior = plugin.getConfig().getString("general.locked_f_behavior", "MESSAGE").toUpperCase(Locale.ROOT);
            if (!unlocked && behavior.equals("ALLOW_SWAP")) {
                return;
            }
            e.setCancelled(true);
            use(p, w, true);
            return;
        }
        if (p.isSneaking() && plugin.armor().fullSet(p)) {
            e.setCancelled(true);
            plugin.armor().useSetAbility(p);
        }
    }

    private void use(Player p, Weapon w, boolean secondary) {
        if (!Util.worldAllowed(p)) {
            plugin.msg(p, "world_disabled");
            return;
        }
        if (plugin.suppression().isSuppressed(p)) {
            plugin.msg(p, "suppressed");
            return;
        }
        UUID uid = p.getUniqueId();
        if (secondary && !plugin.progress().unlocked(uid, w.id)) {
            plugin.msg(p, "locked", "count", String.valueOf(plugin.progress().count(uid, w.id)),
                    "need", String.valueOf(plugin.progress().need()));
            return;
        }
        String slot = secondary ? "secondary" : "primary";
        String key = w.id + ":" + slot;
        long rem = plugin.cooldowns().remaining(uid, key);
        if (rem > 0) {
            Util.actionbar(p, plugin.text("cooldown", "time", String.format(Locale.ROOT, "%.1f", rem / 1000.0)));
            return;
        }
        if (!using.add(uid)) {
            return;
        }
        try {
            boolean ok = secondary ? w.secondary(p) : w.primary(p);
            if (ok) {
                int cd = plugin.getConfig().getInt("abilities.cooldowns." + w.id + "." + slot, 30);
                plugin.cooldowns().set(uid, key, cd);
                Util.actionbar(p, plugin.text("used", "ability", secondary ? w.secondaryName() : w.primaryName()));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Ability " + key + " failed", ex);
        } finally {
            using.remove(uid);
        }
    }
}
