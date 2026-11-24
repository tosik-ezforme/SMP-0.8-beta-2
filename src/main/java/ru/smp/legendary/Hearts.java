package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Постоянные сердца игроков (хранятся в БД, переживают смерть, выход и рестарт). */
public final class Hearts {

    private final LegendaryPlugin plugin;
    private final Map<UUID, Integer> cache = new HashMap<>();

    public Hearts(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public int defaultHearts() {
        return Math.max(1, plugin.getConfig().getInt("hearts.default", 10));
    }

    public int min() {
        return Math.max(1, plugin.getConfig().getInt("hearts.min", 1));
    }

    public int maxFor() {
        return defaultHearts() + Math.max(0, plugin.getConfig().getInt("items.life_stealer.max_bonus_hearts", 10));
    }

    public int get(UUID u) {
        Integer v = cache.get(u);
        if (v == null) {
            var rows = plugin.db().query("SELECT hearts FROM hearts WHERE uuid=?", rs -> rs.getInt(1), u.toString());
            v = rows.isEmpty() ? defaultHearts() : rows.get(0);
            cache.put(u, v);
        }
        return v;
    }

    public void set(UUID u, int hearts) {
        int v = Math.max(min(), Math.min(hearts, maxFor()));
        cache.put(u, v);
        plugin.db().update("INSERT OR REPLACE INTO hearts(uuid, hearts) VALUES(?,?)", u.toString(), v);
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            apply(p);
        }
    }

    /** Применить число сердец к атрибуту максимального здоровья. */
    public void apply(Player p) {
        if (!plugin.getConfig().getBoolean("hearts.enabled", true)) {
            return;
        }
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) {
            return;
        }
        double target = get(p.getUniqueId()) * 2.0;
        if (a.getBaseValue() != target) {
            a.setBaseValue(target);
        }
        if (!p.isDead() && p.getHealth() > target) {
            p.setHealth(target);
        }
    }

    /** Похищение сердца: у жертвы -1, у убийцы +1. Не ниже минимума, не выше максимума. */
    public boolean steal(Player killer, Player victim) {
        int vh = get(victim.getUniqueId());
        int kh = get(killer.getUniqueId());
        if (vh <= min()) {
            plugin.msg(killer, "heart_min");
            return false;
        }
        if (kh >= maxFor()) {
            plugin.msg(killer, "heart_max");
            return false;
        }
        set(victim.getUniqueId(), vh - 1);
        set(killer.getUniqueId(), kh + 1);
        plugin.msg(killer, "heart_gain", "player", victim.getName(), "hearts", String.valueOf(kh + 1));
        plugin.msg(victim, "heart_lost", "hearts", String.valueOf(vh - 1));
        return true;
    }

    // ------------------------------------------------------------ выбывание (Похититель жизней)

    public boolean isEliminated(UUID u) {
        return !plugin.db().query("SELECT value FROM settings WHERE key=?", rs -> rs.getString(1), "elim:" + u).isEmpty();
    }

    public void eliminate(Player victim) {
        plugin.db().update("INSERT OR REPLACE INTO settings(key, value) VALUES(?,?)", "elim:" + victim.getUniqueId(), "1");
        Bukkit.broadcastMessage(Util.color(plugin.getConfig().getString("messages.prefix", "")) + plugin.text("eliminated", "player", victim.getName()));
        plugin.adminLog("system", "eliminated", victim.getName());
    }

    /** Вернуть выбывшего: сердца = hearts.revive_hearts, режим выживания. */
    public void revive(java.util.UUID u) {
        plugin.db().update("DELETE FROM settings WHERE key=?", "elim:" + u);
        set(u, Math.max(min(), plugin.getConfig().getInt("hearts.revive_hearts", 3)));
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.setHealth(Math.min(p.getMaxHealth(), 2.0 * get(u)));
            p.teleport(p.getRespawnLocation() != null ? p.getRespawnLocation() : p.getWorld().getSpawnLocation());
        }
    }

    /** Применить статус выбывшего (наблюдатель). */
    public void enforce(Player p) {
        if (isEliminated(p.getUniqueId()) && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            p.setGameMode(org.bukkit.GameMode.SPECTATOR);
            p.sendMessage(Util.color(plugin.text("eliminated_self")));
        }
    }
}
