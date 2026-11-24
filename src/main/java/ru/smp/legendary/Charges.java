package ru.smp.legendary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Накопленные заряды (Инферно, медный комплект) с сохранением в БД. */
public final class Charges {

    private final LegendaryPlugin plugin;
    private final Map<String, Integer> cache = new HashMap<>();

    public Charges(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static String k(UUID u, String key) {
        return u + "|" + key;
    }

    public int get(UUID u, String key) {
        String ck = k(u, key);
        Integer v = cache.get(ck);
        if (v == null) {
            var rows = plugin.db().query("SELECT amount FROM charges WHERE player=? AND ckey=?", rs -> rs.getInt(1), u.toString(), key);
            v = rows.isEmpty() ? 0 : rows.get(0);
            cache.put(ck, v);
        }
        return v;
    }

    public void set(UUID u, String key, int amount) {
        int v = Math.max(0, amount);
        cache.put(k(u, key), v);
        plugin.db().update("INSERT OR REPLACE INTO charges(player, ckey, amount) VALUES(?,?,?)", u.toString(), key, v);
    }

    /** Добавляет заряд с ограничением максимума. Возвращает новое значение. */
    public int add(UUID u, String key, int delta, int max) {
        int v = Math.min(max, get(u, key) + delta);
        set(u, key, v);
        return v;
    }
}
