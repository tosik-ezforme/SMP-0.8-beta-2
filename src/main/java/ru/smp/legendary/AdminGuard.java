package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Защита админских предметов (Звезда управления, жезл барьеров): у обычного игрока они не работают,
 * не подбираются и изымаются из инвентаря. Остальные проверки прав — в самих предметах и командах.
 */
public final class AdminGuard implements Listener {

    private final LegendaryPlugin plugin;

    public AdminGuard(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isAdminItem(ItemStack s) {
        String t = plugin.items().typeId(s);
        return t != null && (t.equals("f:" + AdminMenu.ID) || t.equals(BarrierManager.WAND_ID));
    }

    static boolean isAdmin(Player p) {
        return p.hasPermission("legendary.admin") || p.isOp();
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("admin_guard.enabled", true)) {
            return;
        }
        long iv = Math.max(20L, plugin.getConfig().getLong("admin_guard.interval_ticks", 100L));
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (isAdmin(p)) {
                        continue;
                    }
                    PlayerInventory inv = p.getInventory();
                    ItemStack[] c = inv.getContents();
                    boolean removed = false;
                    for (int i = 0; i < c.length; i++) {
                        if (isAdminItem(c[i])) {
                            inv.setItem(i, null);
                            removed = true;
                        }
                    }
                    if (isAdminItem(p.getItemOnCursor())) {
                        p.setItemOnCursor(null);
                        removed = true;
                    }
                    if (removed) {
                        plugin.adminLog("guard", "admin_item_removed", p.getName());
                        p.sendMessage(Util.color("&cЭтот предмет только для администраторов — он изъят."));
                    }
                }
            }
        }.runTaskTimer(plugin, iv, iv);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || isAdmin(p)) {
            return;
        }
        Item it = e.getItem();
        if (isAdminItem(it.getItemStack())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || isAdmin(p)) {
            return;
        }
        if (isAdminItem(e.getCurrentItem()) || isAdminItem(e.getCursor())) {
            e.setCancelled(true);
        }
    }
}
