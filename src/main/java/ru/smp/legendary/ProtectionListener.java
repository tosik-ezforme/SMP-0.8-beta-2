package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.entity.Player;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/**
 * - Отключает крафт ванильной булавы (сама булава и компоненты остаются в игре и нужны для ритуалов).
 * - Легендарный предмет нельзя уничтожить: на земле он не исчезает по таймеру, не горит, не тонет в лаве, не
 *   взрывается; его нельзя переплавить, перековать, переименовать, зачаровать или использовать в крафте.
 *   Единственный способ избавиться от него — выбросить в пустоту (тогда оружие можно создать заново).
 * - Предметы на земле всегда подсвечены (контур).
 */
public final class ProtectionListener implements Listener {

    private final LegendaryPlugin plugin;

    public ProtectionListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** Убирает ванильный рецепт булавы (вызывается при включении и перезагрузке конфига). */
    public void applyRecipeBlock() {
        if (plugin.getConfig().getBoolean("general.disable_mace_craft", true)) {
            Bukkit.removeRecipe(NamespacedKey.minecraft("mace"));
        }
    }

    private boolean blocked(ItemStack result) {
        return plugin.getConfig().getBoolean("general.disable_mace_craft", true)
                && result != null && result.getType() == Material.MACE && plugin.items().typeId(result) == null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        if (blocked(e.getInventory().getResult())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (blocked(e.getRecipe().getResult())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (blocked(e.getRecipe().getResult())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ предметы на земле

    private boolean legendary(ItemStack it) {
        return it != null && (plugin.items().weaponId(it) != null || plugin.items().armorId(it) != null);
    }

    private boolean legendary(Item item) {
        return legendary(item.getItemStack());
    }

    private final Set<UUID> lostHandled = new HashSet<>();
    /** Кто выбросил предмет (запасной способ, если у сущности нет thrower). */
    private final java.util.Map<UUID, UUID> thrown = new java.util.HashMap<>();
    /** instance-id легендарки -> ник игрока, при смерти которого она выпала. */
    private final java.util.Map<String, String> deathOwner = new java.util.HashMap<>();

    /** Игрок погиб и выронил легендарку — сообщение в общий чат. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathDrop(org.bukkit.event.entity.PlayerDeathEvent e) {
        for (ItemStack st : e.getDrops()) {
            String t = plugin.items().typeId(st);
            if (t == null || !(t.startsWith("w:") || t.startsWith("a:"))) {
                continue;
            }
            String inst = plugin.items().instanceId(st);
            if (inst != null) {
                deathOwner.put(inst, e.getEntity().getName());
            }
            String nm = st.hasItemMeta() && st.getItemMeta().hasDisplayName() ? st.getItemMeta().getDisplayName() : st.getType().name();
            Bukkit.broadcastMessage(Util.color(plugin.getConfig().getString("messages.prefix", ""))
                    + plugin.text("legendary_death_drop", "player", e.getEntity().getName(), "item", nm));
            plugin.adminLog("system", "legendary_death_drop", e.getEntity().getName() + " " + t + " " + inst);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (legendary(e.getItemDrop())) {
            thrown.put(e.getItemDrop().getUniqueId(), e.getPlayer().getUniqueId());
        }
    }

    /** Любой выпавший легендарный предмет получает контур (подсветка всегда). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (legendary(e.getEntity())) {
            e.getEntity().setGlowing(true);
            e.getEntity().setInvulnerable(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDespawn(ItemDespawnEvent e) {
        if (legendary(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item item) || !legendary(item)) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            lost(item);
            return; // пустота — единственное, что уничтожает легендарку
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent e) {
        if (e.getEntity() instanceof Item item && legendary(item)) {
            e.setCancelled(true);
        }
    }

    /** Предмет улетел в пустоту: экземпляр аннулируется, а уникальное оружие можно создать заново. */
    private void lost(Item item) {
        if (!lostHandled.add(item.getUniqueId())) {
            return;
        }
        ItemStack st = item.getItemStack();
        String inst = plugin.items().instanceId(st);
        String wid = plugin.items().weaponId(st);
        if (inst != null) {
            plugin.guard().voidInstance(inst);
        }
        if (wid != null) {
            plugin.db().update("DELETE FROM unique_items WHERE weapon=?", wid);
        }
        plugin.adminLog("system", "legendary_lost_void", (wid != null ? wid : plugin.items().armorId(st)) + " instance " + inst);
        String who = "неизвестно";
        java.util.UUID th = item.getThrower();
        if (th == null) {
            th = thrown.get(item.getUniqueId());
        }
        if (th == null && inst != null && deathOwner.containsKey(inst)) {
            who = deathOwner.get(inst) + " (выпала при смерти)";
        }
        if (th != null) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(th);
            who = op.getName() != null ? op.getName() : th.toString();
        }
        String itemName = st.hasItemMeta() && st.getItemMeta().hasDisplayName() ? st.getItemMeta().getDisplayName() : st.getType().name();
        Bukkit.broadcastMessage(Util.color(plugin.getConfig().getString("messages.prefix", "")) + plugin.text("legendary_void", "item", itemName, "player", who));
        Bukkit.getScheduler().runTaskLater(plugin, () -> lostHandled.remove(item.getUniqueId()), 200L);
    }

    // ------------------------------------------------------------ запрет изменять легендарки

    private boolean noModify() {
        return plugin.getConfig().getBoolean("protect.block_modify", true);
    }

    /** Крафт с легендаркой в сетке (например, склейка двух мечей) уничтожил бы её. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftGrid(PrepareItemCraftEvent e) {
        if (!noModify()) {
            return;
        }
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (legendary(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        if (noModify() && (legendary(e.getInventory().getItem(0)) || legendary(e.getInventory().getItem(1)))) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmithing(PrepareSmithingEvent e) {
        if (!noModify()) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (legendary(e.getInventory().getItem(i))) {
                e.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent e) {
        if (noModify() && (legendary(e.getInventory().getItem(0)) || legendary(e.getInventory().getItem(1)))) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchantPrepare(PrepareItemEnchantEvent e) {
        if (noModify() && legendary(e.getItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (noModify() && legendary(e.getItem())) {
            e.setCancelled(true);
        }
    }

    /** Железные кинжалы нельзя переплавить в самородки. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent e) {
        if (legendary(e.getSource())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent e) {
        if (legendary(e.getSource())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ подсветка владельца (по желанию)

    public void start() {
        // предметы в пустоте удаляются игрой без события урона, поэтому следим за падением сами
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                double limit = w.getMinHeight() - 8;
                for (Item it : w.getEntitiesByClass(Item.class)) {
                    if (it.getLocation().getY() < limit && legendary(it)) {
                        lost(it);
                        it.remove();
                    }
                }
            }
        }, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("legendary_glow.holder", false)) {
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.items().weaponOf(p.getInventory().getItemInMainHand()) != null) {
                    Util.addEffect(p, "glowing", 60, 0);
                }
            }
        }, 20L, 20L);
    }
}
