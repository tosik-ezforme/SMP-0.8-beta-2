package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Босс-бар с перезарядкой способностей: пока в основной руке легендарное оружие, сверху висит полоса
 * с обеими способностями и временем до готовности. В полном медном комплекте показывается разряд.
 */
public final class CooldownBars {

    private final LegendaryPlugin plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public CooldownBars(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 4L);
    }

    public void shutdown() {
        for (BossBar b : bars.values()) {
            b.removeAll();
        }
        bars.clear();
    }

    private static String time(long ms) {
        if (ms >= 60_000L) {
            long s = (ms + 999) / 1000;
            return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }
        if (ms >= 10_000L) {
            return ((ms + 999) / 1000) + " с";
        }
        return String.format(Locale.ROOT, "%.1f с", ms / 1000.0);
    }

    private int total(String weaponId, String slot) {
        return Math.max(1, plugin.getConfig().getInt("abilities.cooldowns." + weaponId + "." + slot, 30));
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("bossbar.enabled", true)) {
            shutdown();
            return;
        }
        bars.entrySet().removeIf(en -> {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) {
                en.getValue().removeAll();
                return true;
            }
            return false;
        });
        for (Player p : Bukkit.getOnlinePlayers()) {
            Weapon w = plugin.items().weaponOf(p.getInventory().getItemInMainHand());
            if (w != null) {
                showWeapon(p, w);
            } else if (plugin.armor().fullSet(p)) {
                showSet(p);
            } else {
                hide(p);
            }
        }
    }

    private void hide(Player p) {
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) {
            b.removeAll();
        }
    }

    private BossBar bar(Player p) {
        BossBar b = bars.get(p.getUniqueId());
        if (b == null) {
            b = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10);
            b.addPlayer(p);
            bars.put(p.getUniqueId(), b);
        }
        return b;
    }

    private void showWeapon(Player p, Weapon w) {
        UUID u = p.getUniqueId();
        long r1 = plugin.cooldowns().remaining(u, w.id + ":primary");
        long r2 = plugin.cooldowns().remaining(u, w.id + ":secondary");
        boolean unlocked = plugin.progress().unlocked(u, w.id);

        String first = "&e[Shift+ПКМ] &f" + w.primaryName() + " " + (r1 > 0 ? "&c" + time(r1) : "&aГОТОВО");
        String second;
        if (!unlocked) {
            second = "&e[F] &7" + w.secondaryName() + " &c" + Math.min(plugin.progress().count(u, w.id), plugin.progress().need())
                    + "/" + plugin.progress().need() + " убийств";
        } else {
            second = "&e[F] &f" + w.secondaryName() + " " + (r2 > 0 ? "&c" + time(r2) : "&aГОТОВО");
        }
        String title = first + "   &8|   " + second;
        if (plugin.suppression().isSuppressed(p)) {
            title = "&c&lЗОНА ПОДАВЛЕНИЯ &7- способности недоступны";
        }

        // прогресс: насколько «медленнее» всех восстанавливается способность
        double progress = 1.0;
        if (r1 > 0) {
            progress = Math.min(progress, 1.0 - r1 / (total(w.id, "primary") * 1000.0));
        }
        if (unlocked && r2 > 0) {
            progress = Math.min(progress, 1.0 - r2 / (total(w.id, "secondary") * 1000.0));
        }
        boolean ready = r1 <= 0 && (!unlocked || r2 <= 0);
        apply(p, title, progress, ready ? BarColor.GREEN : BarColor.RED);
    }

    private void showSet(Player p) {
        UUID u = p.getUniqueId();
        long r = plugin.cooldowns().remaining(u, "copper_set:primary");
        int have = plugin.charges().get(u, ArmorSet.CHARGE_KEY);
        int need = plugin.getConfig().getInt("copper_armor.set.min_charges", 5);
        int max = plugin.getConfig().getInt("copper_armor.chestplate.max_charges", 10);
        String state = r > 0 ? "&c" + time(r) : have >= need ? "&aГОТОВО" : "&7нужно зарядов: " + need;
        String title = "&b[Shift+F] &fГальванический разряд " + state + "   &8|   &bЗаряд &f" + have + "/" + max;
        double progress = r > 0 ? Math.max(0, 1.0 - r / (plugin.getConfig().getInt("abilities.cooldowns.copper_set.primary", 45) * 1000.0))
                : Math.min(1.0, have / (double) Math.max(1, need));
        apply(p, title, progress, r <= 0 && have >= need ? BarColor.GREEN : BarColor.BLUE);
    }

    private void apply(Player p, String title, double progress, BarColor color) {
        BossBar b = bar(p);
        String t = Util.color(title);
        if (!t.equals(b.getTitle())) {
            b.setTitle(t);
        }
        b.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        if (b.getColor() != color) {
            b.setColor(color);
        }
    }
}
