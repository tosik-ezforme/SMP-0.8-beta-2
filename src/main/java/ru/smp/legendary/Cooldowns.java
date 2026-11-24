package ru.smp.legendary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Кулдауны с сохранением в SQLite (переживают рестарт сервера). */
public final class Cooldowns {

    private final LegendaryPlugin plugin;
    private final Map<String, Long> map = new HashMap<>();

    public Cooldowns(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static String k(UUID u, String key) {
        return u + "|" + key;
    }

    public void load() {
        long now = System.currentTimeMillis();
        plugin.db().update("DELETE FROM cooldowns WHERE ready_at<=?", now);
        for (String[] row : plugin.db().query("SELECT player, ckey, ready_at FROM cooldowns",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)})) {
            map.put(row[0] + "|" + row[1], Long.parseLong(row[2]));
        }
    }

    /** Оставшееся время в миллисекундах (0 — готово). */
    public long remaining(UUID u, String key) {
        Long t = map.get(k(u, key));
        if (t == null) {
            return 0;
        }
        long r = t - System.currentTimeMillis();
        if (r <= 0) {
            map.remove(k(u, key));
            return 0;
        }
        return r;
    }

    public void set(UUID u, String key, long seconds) {
        long t = System.currentTimeMillis() + seconds * 1000L;
        map.put(k(u, key), t);
        plugin.db().update("INSERT OR REPLACE INTO cooldowns(player, ckey, ready_at) VALUES(?,?,?)", u.toString(), key, t);
    }

    public void clear(UUID u, String key) {
        map.remove(k(u, key));
        plugin.db().update("DELETE FROM cooldowns WHERE player=? AND ckey=?", u.toString(), key);
    }

    /** Сбросить все кулдауны игрока (админская команда). */
    public void clearPlayer(UUID u) {
        String prefix = u + "|";
        map.keySet().removeIf(k -> k.startsWith(prefix));
        plugin.db().update("DELETE FROM cooldowns WHERE player=?", u.toString());
    }
}
