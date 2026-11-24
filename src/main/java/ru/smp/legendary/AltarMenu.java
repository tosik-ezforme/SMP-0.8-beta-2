package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Меню алтаря: слоты 0-44 — материалы, нижний ряд — управление. */
public final class AltarMenu implements InventoryHolder {

    public static final int INPUT = 45;
    public static final int INFO = 49;
    public static final int START = 53;

    private final LegendaryPlugin plugin;
    public final AltarManager.Altar altar;
    public final Weapon weapon;
    private final Inventory inv;

    public AltarMenu(LegendaryPlugin plugin, AltarManager.Altar altar, Weapon weapon) {
        this.plugin = plugin;
        this.altar = altar;
        this.weapon = weapon;
        this.inv = Bukkit.createInventory(this, 54, Util.color("&8" + weapon.altarName));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int s = INPUT; s < 54; s++) {
            inv.setItem(s, pane);
        }
        inv.setItem(START, named(Material.EMERALD_BLOCK, "&a&lНачать ритуал",
                List.of("&7Положите материалы в верхние слоты", "&7и нажмите, чтобы начать.")));
        refresh();
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private static ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Util.color(name));
        if (lore != null) {
            List<String> l = new ArrayList<>();
            for (String s : lore) {
                l.add(Util.color(s));
            }
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** Что сейчас лежит в слотах ввода (по ключам ингредиентов). */
    public Map<String, Integer> counts() {
        Map<String, Integer> have = new HashMap<>();
        for (int s = 0; s < INPUT; s++) {
            String k = plugin.items().ingredientKey(inv.getItem(s));
            if (k != null) {
                have.merge(k, inv.getItem(s).getAmount(), Integer::sum);
            }
        }
        return have;
    }

    /** Обновляет книгу с рецептом и текущим прогрессом. */
    public void refresh() {
        ItemRegistry.Recipe rec = plugin.items().recipe(weapon.id);
        List<String> lore = new ArrayList<>();
        if (rec == null || !rec.valid()) {
            lore.add("&cРецепт настроен неверно");
        } else {
            Map<String, Integer> have = counts();
            for (Map.Entry<String, Integer> e : rec.need().entrySet()) {
                int h = have.getOrDefault(e.getKey(), 0);
                String mark = h >= e.getValue() ? "&a✔ " : "&c✘ ";
                lore.add(mark + "&f" + ItemRegistry.displayName(e.getKey()) + " &7" + Math.min(h, e.getValue()) + "/" + e.getValue());
            }
            lore.add("");
            lore.add("&7Длительность ритуала: &f" + plugin.rituals().duration(weapon) + " с");
            if (weapon.unique()) {
                lore.add("&dУникальное оружие: можно создать один раз");
            }
        }
        inv.setItem(INFO, named(Material.BOOK, "&6Рецепт: " + weapon.name, lore));
    }

    /** Проверка условий и запуск ритуала. */
    public void tryStart(Player p) {
        ItemRegistry.Recipe rec = plugin.items().recipe(weapon.id);
        if (rec == null || !rec.valid()) {
            plugin.msg(p, "altar_recipe_invalid");
            return;
        }
        if (!Util.worldAllowed(p)) {
            plugin.msg(p, "world_altar_disabled");
            return;
        }
        List<String> problems = plugin.altars().validate(altar);
        if (!problems.isEmpty()) {
            plugin.msg(p, "altar_broken", "problem", problems.get(0));
            return;
        }
        RitualManager.Ritual running = plugin.rituals().active(altar.id);
        if (running != null) {
            plugin.msg(p, "altar_busy", "time", Util.fmtTime((running.endMs - System.currentTimeMillis()) / 1000));
            return;
        }
        Map<String, Integer> have = counts();
        for (Map.Entry<String, Integer> e : rec.need().entrySet()) {
            int h = have.getOrDefault(e.getKey(), 0);
            if (h < e.getValue()) {
                plugin.msg(p, "altar_missing", "item", ItemRegistry.displayName(e.getKey()),
                        "have", String.valueOf(h), "need", String.valueOf(e.getValue()));
                return;
            }
        }
        if (weapon.unique() && plugin.rituals().uniqueTaken(weapon.id)) {
            plugin.msg(p, "ritual_unique_taken");
            return;
        }
        if (!plugin.rituals().begin(p, altar, weapon, rec.need())) {
            plugin.msg(p, "ritual_unique_taken");
            return;
        }
        consume(rec.need());
        plugin.adminLog(p.getName(), "ritual_start", "altar " + altar.id + " weapon " + weapon.id);
        plugin.msg(p, "ritual_start", "time", Util.fmtTime(plugin.rituals().duration(weapon)));
        p.closeInventory(); // лишние предметы вернутся в обработчике закрытия
    }

    /** Списывает ровно нужное количество; остальное остаётся в слотах и вернётся игроку. */
    private void consume(Map<String, Integer> need) {
        Map<String, Integer> remaining = new HashMap<>(need);
        for (int s = 0; s < INPUT; s++) {
            ItemStack it = inv.getItem(s);
            String k = plugin.items().ingredientKey(it);
            if (k == null) {
                continue;
            }
            Integer r = remaining.get(k);
            if (r == null || r <= 0) {
                continue;
            }
            int take = Math.min(r, it.getAmount());
            if (take >= it.getAmount()) {
                inv.setItem(s, null);
            } else {
                it.setAmount(it.getAmount() - take);
                inv.setItem(s, it);
            }
            remaining.put(k, r - take);
        }
    }

    /** Возвращает игроку всё, что осталось в слотах ввода. */
    public void returnItems(Player p) {
        for (int s = 0; s < INPUT; s++) {
            ItemStack it = inv.getItem(s);
            if (it != null && it.getType() != Material.AIR) {
                RitualManager.give(p, it);
                inv.setItem(s, null);
            }
        }
    }

    /** Shift-клик из инвентаря игрока: кладём в слоты ввода вручную, не затрагивая нижний ряд. */
    public void deposit(org.bukkit.event.inventory.InventoryClickEvent e) {
        ItemStack cur = e.getCurrentItem();
        if (cur == null || cur.getType() == Material.AIR) {
            return;
        }
        int left = cur.getAmount();
        int max = cur.getMaxStackSize();
        for (int s = 0; s < INPUT && left > 0; s++) {
            ItemStack it = inv.getItem(s);
            if (it != null && it.isSimilar(cur) && it.getAmount() < max) {
                int add = Math.min(max - it.getAmount(), left);
                it.setAmount(it.getAmount() + add);
                left -= add;
            }
        }
        for (int s = 0; s < INPUT && left > 0; s++) {
            if (inv.getItem(s) == null) {
                ItemStack n = cur.clone();
                n.setAmount(Math.min(max, left));
                inv.setItem(s, n);
                left -= n.getAmount();
            }
        }
        if (left <= 0) {
            e.setCurrentItem(null);
        } else {
            cur.setAmount(left);
            e.setCurrentItem(cur);
        }
    }
}
