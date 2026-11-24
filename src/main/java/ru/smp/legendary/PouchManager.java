package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Мешочек: предмет, внутри которого лежат 3 стака вещей. ПКМ — открыть. У одного игрока не больше
 * pouch.max_per_player мешочков. Оружие/броня/другие мешочки/шалкеры внутрь класть нельзя (чтобы не
 * обходить защиту от дюпа). Содержимое хранится в метках самого предмета.
 */
public final class PouchManager implements Listener {

    public static final String ID = "pouch";
    private static final int[] SLOTS = {1, 2, 3};

    private static final class View implements InventoryHolder {
        final String uid;
        Inventory inv;

        View(String uid) {
            this.uid = uid;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LegendaryPlugin plugin;
    private final NamespacedKey keyUid;
    private final NamespacedKey keyData;
    private final NamespacedKey recipeKey;
    private final Map<UUID, Long> warned = new HashMap<>();

    public PouchManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.keyUid = new NamespacedKey(plugin, "pouch_uid");
        this.keyData = new NamespacedKey(plugin, "pouch_data");
        this.recipeKey = new NamespacedKey(plugin, "pouch_recipe");
    }

    // ------------------------------------------------------------ предмет

    public boolean is(ItemStack s) {
        return ("f:" + ID).equals(plugin.items().typeId(s));
    }

    public ItemStack create() {
        ItemStack it = new ItemStack(Material.LEATHER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color("&6Мешочек"));
        m.setLore(List.of(Util.color("&7ПКМ — открыть. Вмещает &f3 стака&7."),
                Util.color("&7Оружие и броню класть нельзя."),
                Util.color("&8Не больше " + max() + " мешочков у одного игрока")));
        try {
            m.setMaxStackSize(1);
        } catch (Throwable ignored) {
        }
        m.getPersistentDataContainer().set(plugin.keyId, PersistentDataType.STRING, "f:" + ID);
        plugin.items().applyModel(m, ID);
        it.setItemMeta(m);
        return it;
    }

    private int max() {
        return Math.max(1, plugin.getConfig().getInt("pouch.max_per_player", 3));
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!plugin.getConfig().getBoolean("pouch.enabled", true) || !plugin.getConfig().getBoolean("pouch.craftable", true)) {
            return;
        }
        ShapedRecipe r = new ShapedRecipe(recipeKey, create());
        r.shape(" S ", "L L", "LLL");
        r.setIngredient('S', Material.STRING);
        r.setIngredient('L', Material.LEATHER);
        Bukkit.addRecipe(r);
    }

    public void unregisterRecipe() {
        Bukkit.removeRecipe(recipeKey);
    }

    private boolean allowedInside(ItemStack s) {
        if (s == null || s.getType() == Material.AIR) {
            return true;
        }
        String n = s.getType().name();
        if (n.endsWith("SHULKER_BOX") || n.equals("BUNDLE") || n.endsWith("_BUNDLE")) {
            return false;
        }
        String t = plugin.items().typeId(s);
        // метки легендарного оружия/брони и служебных предметов внутрь не кладём; компоненты крафта — можно
        return t == null || t.startsWith("c:");
    }

    // ------------------------------------------------------------ хранение

    private ItemStack[] load(ItemStack pouch) {
        ItemStack[] out = new ItemStack[SLOTS.length];
        String data = pouch.getItemMeta().getPersistentDataContainer().get(keyData, PersistentDataType.STRING);
        if (data == null || data.isEmpty()) {
            return out;
        }
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            int n = in.readInt();
            for (int i = 0; i < n && i < out.length; i++) {
                out[i] = (ItemStack) in.readObject();
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Pouch: cannot read contents: " + ex.getMessage());
        }
        return out;
    }

    private void store(ItemStack pouch, ItemStack[] items) {
        ItemMeta m = pouch.getItemMeta();
        try (ByteArrayOutputStream bo = new ByteArrayOutputStream(); BukkitObjectOutputStream out = new BukkitObjectOutputStream(bo)) {
            out.writeInt(items.length);
            for (ItemStack i : items) {
                out.writeObject(i);
            }
            out.flush();
            m.getPersistentDataContainer().set(keyData, PersistentDataType.STRING, Base64.getEncoder().encodeToString(bo.toByteArray()));
        } catch (Exception ex) {
            plugin.getLogger().warning("Pouch: cannot save contents: " + ex.getMessage());
            return;
        }
        int filled = 0;
        for (ItemStack i : items) {
            if (i != null && i.getType() != Material.AIR) {
                filled++;
            }
        }
        m.setLore(List.of(Util.color("&7ПКМ — открыть. Вмещает &f3 стака&7."),
                Util.color("&7Занято: &f" + filled + "/3"),
                Util.color("&7Оружие и броню класть нельзя."),
                Util.color("&8Не больше " + max() + " мешочков у одного игрока")));
        pouch.setItemMeta(m);
    }

    private String uidOf(ItemStack s) {
        return s.getItemMeta().getPersistentDataContainer().get(keyUid, PersistentDataType.STRING);
    }

    private ItemStack find(Player p, String uid) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getContents()) {
            if (is(s) && uid.equals(uidOf(s))) {
                return s;
            }
        }
        return null;
    }

    private void save(Player p, View v) {
        ItemStack pouch = find(p, v.uid);
        ItemStack[] items = new ItemStack[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++) {
            items[i] = v.inv.getItem(SLOTS[i]);
        }
        if (pouch == null) {
            // мешочек пропал из инвентаря — содержимое не должно исчезнуть
            for (ItemStack it : items) {
                if (it != null && it.getType() != Material.AIR) {
                    p.getWorld().dropItemNaturally(p.getLocation(), it);
                }
            }
            return;
        }
        store(pouch, items);
    }

    private int count(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (is(s)) {
                n++;
            }
        }
        return n;
    }

    private void warn(Player p) {
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 1500L) {
            warned.put(p.getUniqueId(), now);
            plugin.msg(p, "pouch_limit", "max", String.valueOf(max()));
        }
    }

    private boolean viewing(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder() instanceof View;
    }

    // ------------------------------------------------------------ открытие

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !is(e.getItem())) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("pouch.enabled", true)) {
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        String uid = uidOf(held);
        if (uid == null) {
            uid = UUID.randomUUID().toString();
            ItemMeta m = held.getItemMeta();
            m.getPersistentDataContainer().set(keyUid, PersistentDataType.STRING, uid);
            held.setItemMeta(m);
        }
        View v = new View(uid);
        v.inv = Bukkit.createInventory(v, InventoryType.HOPPER, Util.color("&6Мешочек"));
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.setDisplayName(" ");
        pane.setItemMeta(pm);
        v.inv.setItem(0, pane);
        v.inv.setItem(4, pane);
        ItemStack[] items = load(held);
        for (int i = 0; i < SLOTS.length; i++) {
            v.inv.setItem(SLOTS[i], items[i]);
        }
        p.openInventory(v.inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        boolean open = e.getView().getTopInventory().getHolder() instanceof View;
        int max = max();
        // лимит: перенос мешочка в инвентарь игрока
        if (!open) {
            ItemStack cur = e.getCurrentItem();
            ItemStack cursor = e.getCursor();
            boolean toPlayer = e.getClickedInventory() == p.getInventory();
            if (toPlayer && is(cursor) && count(p) >= max && !is(cur)) {
                e.setCancelled(true);
                warn(p);
            } else if (e.isShiftClick() && !toPlayer && is(cur) && count(p) >= max && e.getClickedInventory() != null) {
                e.setCancelled(true);
                warn(p);
            }
            return;
        }
        View v = (View) e.getView().getTopInventory().getHolder();
        ItemStack cur = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        // мешочки (в т.ч. тот, что открыт) трогать нельзя, пока открыто содержимое
        if (is(cur) || is(cursor)) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hot = p.getInventory().getItem(e.getHotbarButton());
            if (is(hot) || (e.getClickedInventory() == v.inv && !allowedInside(hot))) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getClickedInventory() == v.inv) {
            int s = e.getSlot();
            if (s == 0 || s == 4) {
                e.setCancelled(true);
                return;
            }
            if (!allowedInside(cursor)) {
                e.setCancelled(true);
                plugin.msg(p, "pouch_denied");
                return;
            }
        } else if (e.isShiftClick() && !allowedInside(cur)) {
            e.setCancelled(true);
            plugin.msg(p, "pouch_denied");
            return;
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND && e.getClickedInventory() == v.inv && !allowedInside(p.getInventory().getItemInOffHand())) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && viewing(p)) {
                save(p, v);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v) || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        boolean bad = !allowedInside(e.getOldCursor()) || is(e.getOldCursor());
        for (int raw : e.getRawSlots()) {
            if (raw < 5 && (bad || raw == 0 || raw == 4)) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && viewing(p)) {
                save(p, v);
            }
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof View v && e.getPlayer() instanceof Player p) {
            save(p, v);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (viewing(e.getPlayer()) && is(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** Закрыть все открытые мешочки с сохранением (выключение сервера). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (viewing(p)) {
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------ лимит

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !is(e.getItem().getItemStack())) {
            return;
        }
        if (count(p) >= max()) {
            e.setCancelled(true);
            warn(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && is(e.getRecipe().getResult()) && count(p) >= max()) {
            e.setCancelled(true);
            warn(p);
        }
    }

    /** Подстраховка: лишние мешочки (например из креатива) выбрасываются под ноги. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int max = max();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (count(p) <= max || viewing(p)) {
                    continue;
                }
                int seen = 0;
                PlayerInventory inv = p.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (is(s) && ++seen > max) {
                        inv.setItem(i, null);
                        p.getWorld().dropItemNaturally(p.getLocation(), s);
                        warn(p);
                    }
                }
            }
        }, 100L, 100L);
    }
}
