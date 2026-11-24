package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Защита от дюпа: у каждого экземпляра легендарного предмета есть уникальный instance-ID.
 * Раз в несколько секунд сверяем инвентари и эндер-сундуки онлайн-игроков:
 *  - вторая копия того же экземпляра удаляется;
 *  - экземпляры, помеченные админом как voided, удаляются;
 *  - (опционально) удаляются экземпляры, которых нет в базе (подделка через NBT/креатив).
 * Предметы, лежащие в контейнерах и на земле, проверяются в момент, когда игрок их берёт.
 */
public final class InstanceGuard {

    private final LegendaryPlugin plugin;

    public InstanceGuard(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long every = Math.max(20L, plugin.getConfig().getLong("guard.interval_ticks", 100L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, every, every);
    }

    /** Пометить экземпляр недействительным: при ближайшей проверке он исчезнет у владельца. */
    public void voidInstance(String instanceId) {
        plugin.db().update("UPDATE instances SET voided=1 WHERE id=?", instanceId);
    }

    public void scan() {
        if (!plugin.getConfig().getBoolean("guard.enabled", true) || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        boolean strict = plugin.getConfig().getBoolean("guard.remove_unregistered", false);
        Set<String> voided = new HashSet<>(plugin.db().query("SELECT id FROM instances WHERE voided=1", rs -> rs.getString(1)));
        Set<String> known = new HashSet<>(plugin.db().query("SELECT id FROM instances", rs -> rs.getString(1)));
        Map<String, String> seen = new HashMap<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            check(p, p.getInventory(), voided, known, seen, strict);
            check(p, p.getEnderChest(), voided, known, seen, strict);
            ItemStack cursor = p.getItemOnCursor();
            if (verdict(p.getName(), p.getUniqueId().toString(), cursor, voided, known, seen, strict, "cursor")) {
                p.setItemOnCursor(null);
            }
        }
        // предметы на земле: копия экземпляра, который уже есть у игрока или на земле, удаляется
        for (World w : Bukkit.getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                String where = "ground " + w.getName() + " " + item.getLocation().getBlockX() + " "
                        + item.getLocation().getBlockY() + " " + item.getLocation().getBlockZ();
                if (verdict("ground", item.getUniqueId().toString(), item.getItemStack(), voided, known, seen, strict, where)) {
                    item.remove();
                }
            }
        }
    }

    private void check(Player p, Inventory inv, Set<String> voided, Set<String> known,
                       Map<String, String> seen, boolean strict) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (verdict(p.getName(), p.getUniqueId().toString(), contents[i], voided, known, seen, strict, "slot " + i)) {
                inv.setItem(i, null);
            }
        }
    }

    /** @return true, если предмет нужно удалить. */
    private boolean verdict(String who, String holderId, ItemStack it, Set<String> voided, Set<String> known,
                            Map<String, String> seen, boolean strict, String where) {
        String type = plugin.items().typeId(it);
        if (type == null || !(type.startsWith("w:") || type.startsWith("a:"))) {
            return false;
        }
        String inst = plugin.items().instanceId(it);
        if (inst == null) {
            // легендарная метка без экземпляра — подделка
            plugin.adminLog("guard", "removed_no_instance", who + " " + type + " (" + where + ")");
            return true;
        }
        if (voided.contains(inst)) {
            plugin.adminLog("guard", "removed_voided", who + " " + type + " " + inst);
            return true;
        }
        String holder = holderId + ":" + where;
        String prev = seen.putIfAbsent(inst, holder);
        if (prev != null) {
            plugin.adminLog("guard", "removed_duplicate", who + " " + type + " " + inst + " (first seen at " + prev + ")");
            return true;
        }
        if (!known.contains(inst)) {
            if (strict) {
                plugin.adminLog("guard", "removed_unregistered", who + " " + type + " " + inst);
                return true;
            }
            plugin.registerInstance(type.substring(2), inst);
            known.add(inst);
        }
        return false;
    }
}
