package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.UUID;

public final class AltarListener implements Listener {

    private final LegendaryPlugin plugin;

    public AltarListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        AltarManager.Altar a = plugin.altars().at(e.getClickedBlock());
        if (a == null) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        Weapon w = plugin.items().ritualItem(a.type);
        if (w == null) {
            return;
        }
        if (!Util.worldAllowed(p)) {
            plugin.msg(p, "world_altar_disabled");
            return;
        }
        RitualManager.Ritual r = plugin.rituals().active(a.id);
        if (r != null) {
            plugin.msg(p, "altar_busy", "time", Util.fmtTime((r.endMs - System.currentTimeMillis()) / 1000));
            return;
        }
        List<String> problems = plugin.altars().validate(a);
        if (!problems.isEmpty()) {
            plugin.msg(p, "altar_broken", "problem", problems.get(0));
            return;
        }
        UUID viewer = plugin.altars().viewer(a.id);
        if (viewer != null && !viewer.equals(p.getUniqueId())) {
            Player vp = Bukkit.getPlayer(viewer);
            if (vp != null && vp.getOpenInventory().getTopInventory().getHolder() instanceof AltarMenu) {
                plugin.msg(p, "altar_in_use");
                return;
            }
        }
        AltarMenu menu = new AltarMenu(plugin, a, w);
        plugin.altars().setViewer(a.id, p.getUniqueId());
        p.openInventory(menu.getInventory());
        plugin.fx().sound(p.getLocation(), "block.enchantment_table.use", 1f, 0.8f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof AltarMenu menu)) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getClickedInventory() == top) {
            if (e.getSlot() >= AltarMenu.INPUT) {
                e.setCancelled(true);
                if (e.getSlot() == AltarMenu.START) {
                    menu.tryStart(p);
                }
                return;
            }
        } else if (e.getClickedInventory() != null) {
            if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
                menu.deposit(e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, menu::refresh);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof AltarMenu menu)) {
            return;
        }
        for (int slot : e.getRawSlots()) {
            if (slot >= AltarMenu.INPUT && slot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, menu::refresh);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof AltarMenu menu)) {
            return;
        }
        if (e.getPlayer() instanceof Player p) {
            menu.returnItems(p);
        }
        if (e.getPlayer().getUniqueId().equals(plugin.altars().viewer(menu.altar.id))) {
            plugin.altars().setViewer(menu.altar.id, null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof AltarMenu) {
            e.getPlayer().closeInventory();
        }
    }

    // ------------------------------------------------------------ protection of altar blocks

    /** Главный блок алтаря сломать нельзя никому (убрать алтарь можно только командой /legendary altar remove). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.altars().at(e.getBlock()) == null) {
            return;
        }
        e.setCancelled(true);
        plugin.msg(e.getPlayer(), "altar_unbreakable");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (org.bukkit.block.Block b : e.getBlocks()) {
            if (plugin.altars().at(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (org.bukkit.block.Block b : e.getBlocks()) {
            if (plugin.altars().at(b) != null) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (plugin.altars().at(e.getBlock()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.altars().at(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.altars().at(b) != null);
    }
}
