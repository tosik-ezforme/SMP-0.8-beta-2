package ru.smp.legendary;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Временные состояния игроков (метки, баффы, связи). Живут только в памяти и сбрасываются при смерти. */
public final class Buffs {

    public static final class Entry {
        public final long until;
        public final Object data;

        Entry(long until, Object data) {
            this.until = until;
            this.data = data;
        }
    }

    private final Map<UUID, Map<String, Entry>> map = new HashMap<>();

    public void set(UUID u, String key, long millis, Object data) {
        map.computeIfAbsent(u, k -> new HashMap<>()).put(key, new Entry(System.currentTimeMillis() + millis, data));
    }

    public Entry get(UUID u, String key) {
        Map<String, Entry> m = map.get(u);
        if (m == null) {
            return null;
        }
        Entry e = m.get(key);
        if (e == null) {
            return null;
        }
        if (e.until <= System.currentTimeMillis()) {
            m.remove(key);
            return null;
        }
        return e;
    }

    public boolean active(UUID u, String key) {
        return get(u, key) != null;
    }

    public void clear(UUID u, String key) {
        Map<String, Entry> m = map.get(u);
        if (m != null) {
            m.remove(key);
        }
    }

    public void clearAll(UUID u) {
        map.remove(u);
    }

    public void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Map<String, Entry>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, Entry> m = it.next().getValue();
            m.values().removeIf(e -> e.until <= now);
            if (m.isEmpty()) {
                it.remove();
            }
        }
    }
}
