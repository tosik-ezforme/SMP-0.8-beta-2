package ru.smp.legendary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Прогресс разблокировки второй способности: UUID уникальных убитых игроков по каждому оружию. */
public final class Progress {

    private final LegendaryPlugin plugin;
    private final Map<String, Set<UUID>> cache = new HashMap<>();

    public Progress(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private Set<UUID> load(UUID player, String weapon) {
        String key = player + "|" + weapon;
        Set<UUID> set = cache.get(key);
        if (set == null) {
            set = new HashSet<>();
            for (String s : plugin.db().query("SELECT victim FROM progress WHERE player=? AND weapon=?",
                    rs -> rs.getString(1), player.toString(), weapon)) {
                try {
                    set.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
            cache.put(key, set);
        }
        return set;
    }

    public int need() {
        return Math.max(1, plugin.getConfig().getInt("abilities.unlock.unique_player_kills", 5));
    }

    public int count(UUID player, String weapon) {
        return load(player, weapon).size();
    }

    public boolean unlocked(UUID player, String weapon) {
        return count(player, weapon) >= need();
    }

    /** @return true, если убийство засчитано как новое. */
    public boolean add(UUID player, String weapon, UUID victim) {
        if (player.equals(victim)) {
            return false;
        }
        Set<UUID> set = load(player, weapon);
        if (!set.add(victim)) {
            return false;
        }
        plugin.db().update("INSERT OR IGNORE INTO progress(player, weapon, victim, ts) VALUES(?,?,?,?)",
                player.toString(), weapon, victim.toString(), System.currentTimeMillis());
        return true;
    }

    /** Админская установка числа (лишние записи удаляются, недостающие добавляются как служебные UUID). */
    public void set(UUID player, String weapon, int n) {
        Set<UUID> set = load(player, weapon);
        n = Math.max(0, n);
        Iterator<UUID> it = set.iterator();
        while (set.size() > n && it.hasNext()) {
            UUID rm = it.next();
            it.remove();
            plugin.db().update("DELETE FROM progress WHERE player=? AND weapon=? AND victim=?",
                    player.toString(), weapon, rm.toString());
        }
        long k = 1;
        while (set.size() < n) {
            UUID fake = new UUID(0L, k++);
            if (set.add(fake)) {
                plugin.db().update("INSERT OR IGNORE INTO progress(player, weapon, victim, ts) VALUES(?,?,?,?)",
                        player.toString(), weapon, fake.toString(), System.currentTimeMillis());
            }
        }
    }
}
