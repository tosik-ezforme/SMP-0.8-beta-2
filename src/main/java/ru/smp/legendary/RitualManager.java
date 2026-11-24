package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ритуалы алтарей. Состояние хранится в БД: при рестарте ритуал продолжается/завершается,
 * награда выдаётся ровно один раз (через таблицу pending_rewards).
 */
public final class RitualManager {

    public static final class Ritual {
        public final int altarId;
        public final String weapon;
        public final UUID owner;
        public final long startMs;
        public final long endMs;
        public final Map<String, Integer> reserved;
        BukkitTask task;
        /** Последняя объявленная в чате стадия раскрытия координат (-1 — ещё ничего не объявлено). */
        int stage = -1;

        Ritual(int altarId, String weapon, UUID owner, long startMs, long endMs, Map<String, Integer> reserved) {
            this.startMs = startMs;
            this.altarId = altarId;
            this.weapon = weapon;
            this.owner = owner;
            this.endMs = endMs;
            this.reserved = reserved;
        }
    }

    private final LegendaryPlugin plugin;
    private final Map<Integer, Ritual> active = new HashMap<>();

    public RitualManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ serialization

    private static String serialize(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Integer> parse(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) {
            return m;
        }
        for (String part : s.split(";")) {
            int i = part.lastIndexOf('=');
            if (i > 0) {
                try {
                    m.put(part.substring(0, i), Integer.parseInt(part.substring(i + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return m;
    }

    // ------------------------------------------------------------ state

    public Ritual active(int altarId) {
        return active.get(altarId);
    }

    public int duration(Weapon w) {
        String path = switch (w.category()) {
            case NORMAL -> "crafting.ritual.normal_duration";
            case SPECIAL -> "crafting.ritual.special_duration";
            case UNIQUE -> "crafting.ritual.unique_duration";
        };
        return Math.max(1, plugin.getConfig().getInt(path, 900));
    }

    /** Максимум экземпляров на карте: значение админа (меню) или items.<id>.max_count из конфига (по умолчанию 1). */
    public int limit(String weaponId) {
        List<String> v = plugin.db().query("SELECT value FROM settings WHERE key=?", rs -> rs.getString(1), "limit:" + weaponId);
        if (!v.isEmpty()) {
            try {
                return Math.max(0, Integer.parseInt(v.get(0)));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(0, plugin.getConfig().getInt("items." + weaponId + ".max_count", 1));
    }

    public void setLimit(String weaponId, int n) {
        plugin.db().update("INSERT OR REPLACE INTO settings(key, value) VALUES(?,?)", "limit:" + weaponId, String.valueOf(Math.max(0, n)));
    }

    /** Сколько экземпляров существует (включая ожидающие выдачи) и сколько ритуалов уже идёт. */
    public int copies(String weaponId) {
        int inst = plugin.db().query("SELECT id FROM instances WHERE weapon=? AND voided=0", rs -> rs.getString(1), weaponId).size();
        int rit = plugin.db().query("SELECT altar_id FROM rituals WHERE weapon=?", rs -> rs.getInt(1), weaponId).size();
        return inst + rit;
    }

    public boolean uniqueTaken(String weaponId) {
        return copies(weaponId) >= limit(weaponId);
    }

    /** Удалить оружие с карты: экземпляры аннулируются (исчезнут у игроков и на земле), ритуалы отменяются. */
    public int removeFromMap(String weaponId, String actor) {
        for (AltarManager.Altar a : new java.util.ArrayList<>(plugin.altars().all())) {
            Ritual r = active.get(a.id);
            if (r != null && r.weapon.equals(weaponId)) {
                cancel(a, actor);
            }
        }
        List<String> ids = plugin.db().query("SELECT id FROM instances WHERE weapon=? AND voided=0", rs -> rs.getString(1), weaponId);
        for (String id : ids) {
            plugin.guard().voidInstance(id);
        }
        plugin.db().update("DELETE FROM pending_rewards WHERE weapon=?", weaponId);
        plugin.db().update("DELETE FROM unique_items WHERE weapon=?", weaponId);
        plugin.adminLog(actor, "remove_from_map", weaponId + " x" + ids.size());
        plugin.guard().scan();
        return ids.size();
    }

    public void load() {
        List<String[]> rows = plugin.db().query("SELECT altar_id, weapon, owner, started_ms, end_ms, reserved FROM rituals",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
        for (String[] r : rows) {
            try {
                Ritual ritual = new Ritual(Integer.parseInt(r[0]), r[1], UUID.fromString(r[2]), Long.parseLong(r[3]), Long.parseLong(r[4]), parse(r[5]));
                active.put(ritual.altarId, ritual);
                schedule(ritual);
            } catch (Exception ex) {
                plugin.getLogger().warning("Bad ritual row skipped: " + String.join(",", r));
            }
        }
        if (!rows.isEmpty()) {
            plugin.getLogger().info("Restored " + active.size() + " active ritual(s).");
        }
    }

    /** Фиксирует начало ритуала в БД (резервирование материалов). Предметы из меню списывает вызывающий код. */
    public boolean begin(Player p, AltarManager.Altar altar, Weapon w, Map<String, Integer> reserved) {
        final long now = System.currentTimeMillis();
        final long end = now + duration(w) * 1000L;
        boolean ok = plugin.db().transaction(() -> {
            plugin.db().exec("INSERT INTO rituals(altar_id, weapon, owner, started_ms, end_ms, reserved) VALUES(?,?,?,?,?,?)",
                    altar.id, w.id, p.getUniqueId().toString(), now, end, serialize(reserved));
        });
        if (!ok) {
            return false;
        }
        Ritual r = new Ritual(altar.id, w.id, p.getUniqueId(), now, end, new LinkedHashMap<>(reserved));
        active.put(altar.id, r);
        schedule(r);
        return true;
    }

    private void schedule(Ritual r) {
        r.task = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (now >= r.endMs) {
                    cancel();
                    finish(r);
                    return;
                }
                tick++;
                AltarManager.Altar altar = plugin.altars().byId(r.altarId);
                if (altar == null) {
                    return;
                }
                Location l = altar.location();
                AltarManager.Theme theme = plugin.altars().theme(altar.type);
                if (l == null || theme == null) {
                    return;
                }
                Location c = l.clone().add(0.5, 1.2, 0.5);
                int rad = AltarBuilder.radius(plugin);
                double a = tick * 0.35;
                // вращающиеся спирали вокруг центра
                for (int k = 0; k < 4; k++) {
                    double ang = a + k * (Math.PI / 2);
                    double rr = 1.5 + (tick % 24) * 0.12;
                    Location pt = c.clone().add(Math.cos(ang) * rr, ((tick % 20) / 20.0) * 3.0, Math.sin(ang) * rr);
                    plugin.fx().particle(pt, theme.particle(), 3, 0.05, 0.05, 0.05, 0.01);
                }
                // широкое кольцо по площадке алтаря
                for (int k = 0; k < 16; k++) {
                    double ang = -a * 0.6 + k * (Math.PI / 8);
                    Location pt = c.clone().add(Math.cos(ang) * (rad - 1.5), -0.8, Math.sin(ang) * (rad - 1.5));
                    plugin.fx().particle(pt, theme.particle(), 1, 0.1, 0.2, 0.1, 0.01);
                }
                // световой столб над алтарём
                if (tick % 2 == 0) {
                    for (int y = 1; y <= 14; y += 2) {
                        plugin.fx().particle(c.clone().add(0, y, 0), theme.particle(), 2, 0.15, 0.3, 0.15, 0.02);
                    }
                }
                plugin.fx().particle(c, theme.particle(), 6, 0.5, 0.6, 0.5, 0.05);
                if (tick % 6 == 0) {
                    plugin.fx().sound(c, theme.sound(), 0.6f, 0.7f + (tick % 12) / 30f);
                }
                announce(r, altar, now);
                updateHolo(altar, r, now);
            }
        }.runTaskTimer(plugin, 1L, 5L);
    }

    // ------------------------------------------------------------ оповещение игроков о координатах алтаря

    private static String worldTitle(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "Незер";
            case THE_END -> "Энд";
            default -> "Обычный мир";
        };
    }

    private static final String HIDDEN = "▒▒▒";

    /**
     * Оповещение всем игрокам. revealed: сколько координат уже открыто (0..3: сначала X, затем Y, затем Z).
     * Процент прогресса, время и расстояние до алтаря не показываются.
     */
    private void send(Weapon w, AltarManager.Altar altar, int revealed, boolean done) {
        World world = Bukkit.getWorld(altar.world);
        if (world == null) {
            return;
        }
        String wname = w == null ? "легендарное оружие" : w.name;
        String aname = w == null ? altar.type : w.altarName;
        String line = "&8&m                                                ";
        int rv = done ? 3 : revealed;
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(line).append("\n");
        sb.append(done ? "&a&l✦ РИТУАЛ ЗАВЕРШЁН ✦\n" : "&6&l⚔ ЛЕГЕНДАРНЫЙ РИТУАЛ ⚔\n");
        sb.append(done ? "&7Оружие &r" + wname + " &7лежит на алтаре &f" + aname + "&7! Успей забрать!\n"
                : "&7Кто-то пробуждает &r" + wname + " &7на алтаре &f" + aname + "&7.\n");
        sb.append("&7Мир: &f").append(worldTitle(world)).append("\n");
        // порядок раскрытия: сначала X, потом Z, в конце высота Y
        sb.append("&7Координаты: &cX &f").append(rv >= 1 ? String.valueOf(altar.x) : HIDDEN)
                .append("  &cZ &f").append(rv >= 2 ? String.valueOf(altar.z) : HIDDEN)
                .append("  &cY &f").append(rv >= 3 ? String.valueOf(altar.y) : HIDDEN).append("\n");
        sb.append(line);
        String text = Util.color(sb.toString());
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(text);
            p.playSound(p.getLocation(), done ? "ui.toast.challenge_complete" : "block.bell.use", 1f, done ? 1f : 0.6f);
        }
    }

    private void announce(Ritual r, AltarManager.Altar altar, long now) {
        if (!plugin.getConfig().getBoolean("crafting.announce.enabled", true)) {
            return;
        }
        long every = Math.max(10L, plugin.getConfig().getLong("crafting.announce.reveal_every_seconds", 180L)) * 1000L;
        // стадия 0 — старт ритуала, 1 — открыт X, 2 — открыт Z, 3 — открыт Y
        int idx = (int) Math.min(3L, Math.max(0L, (now - r.startMs) / every));
        if (idx <= r.stage) {
            return;
        }
        r.stage = idx;
        Weapon w = plugin.items().ritualItem(r.weapon);
        send(w, altar, idx, false);
        if (idx == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(Util.color("&6&lЛегендарный ритуал"), Util.color(w == null ? "" : w.name), 10, 60, 20);
            }
        }
    }

    // ------------------------------------------------------------ голограмма-таймер над алтарём

    private final Map<Integer, UUID> holos = new HashMap<>();
    private final Map<Integer, String> holoText = new HashMap<>();

    private void updateHolo(AltarManager.Altar altar, Ritual r, long now) {
        Location at = altar.location();
        if (at == null || at.getWorld() == null) {
            return;
        }
        at.add(0.5, plugin.getConfig().getDouble("crafting.hologram_height", 4.5), 0.5);
        World w = at.getWorld();
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        TextDisplay td = null;
        UUID known = holos.get(altar.id);
        if (known != null) {
            Entity e = Bukkit.getEntity(known);
            if (e instanceof TextDisplay t && !t.isDead()) {
                td = t;
            }
        }
        if (td == null) {
            td = w.spawn(at, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setSeeThrough(false); // текст не просвечивает сквозь блоки
                d.setShadowed(true);
                d.setViewRange(3.0f);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(2.5f, 2.5f, 2.5f), new AxisAngle4f()));
            });
            holos.put(altar.id, td.getUniqueId());
            holoText.remove(altar.id);
        }
        Weapon wp = plugin.items().ritualItem(r.weapon);
        String text = Util.color("&6&lРИТУАЛ\n" + (wp == null ? "" : wp.name + "\n") + "&f⏳ "
                + Util.fmtTime((r.endMs - now + 999) / 1000));
        if (!text.equals(holoText.get(altar.id))) {
            td.setText(text);
            holoText.put(altar.id, text);
        }
    }

    private void removeHolo(int altarId) {
        UUID id = holos.remove(altarId);
        holoText.remove(altarId);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Убрать все голограммы (выключение плагина). */
    public void removeAllHolos() {
        for (Integer id : new java.util.ArrayList<>(holos.keySet())) {
            removeHolo(id);
        }
    }

    /** Выбрасывает оружие на центральный блок алтаря. @return true, если предмет выпал. */
    private boolean dropOnAltar(AltarManager.Altar altar, ItemStack stack, Weapon w) {
        Location base = altar.location();
        if (base == null || base.getWorld() == null || stack == null) {
            return false;
        }
        Location at = base.clone().add(0.5, 1.15, 0.5);
        at.getChunk().load();
        Item item = base.getWorld().dropItem(at, stack);
        item.setVelocity(new Vector(0, 0.2, 0));
        item.setInvulnerable(true);
        item.setGlowing(true);
        item.setPickupDelay(40);
        if (w != null) {
            item.setCustomName(Util.color(w.name));
            item.setCustomNameVisible(true);
        }
        base.getWorld().strikeLightningEffect(at);
        return true;
    }

    private void finish(Ritual r) {
        active.remove(r.altarId);
        removeHolo(r.altarId);
        final String instance = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        final Weapon w = plugin.items().ritualItem(r.weapon);
        boolean ok = plugin.db().transaction(() -> {
            plugin.db().exec("DELETE FROM rituals WHERE altar_id=?", r.altarId);
            plugin.db().exec("INSERT INTO pending_rewards(id, owner, weapon, ts) VALUES(?,?,?,?)",
                    instance, r.owner.toString(), r.weapon, now);
            plugin.db().exec("INSERT INTO craft_log(ts, player, weapon, instance, altar_id) VALUES(?,?,?,?,?)",
                    now, r.owner.toString(), r.weapon, instance, r.altarId);
            plugin.db().exec("INSERT OR REPLACE INTO instances(id, weapon, created) VALUES(?,?,?)",
                    instance, r.weapon, now);
        });
        if (!ok) {
            plugin.getLogger().severe("Ritual finish failed, will retry in 10s (altar " + r.altarId + ")");
            Ritual retry = new Ritual(r.altarId, r.weapon, r.owner, r.startMs, System.currentTimeMillis() + 10_000L, r.reserved);
            retry.stage = r.stage;
            active.put(r.altarId, retry);
            schedule(retry);
            return;
        }
        AltarManager.Altar altar = plugin.altars().byId(r.altarId);
        if (altar != null && altar.location() != null) {
            Location c = altar.location().add(0.5, 1.5, 0.5);
            plugin.fx().particle(c, "END_ROD", 120, 1.5, 2.0, 1.5, 0.4);
            plugin.fx().particle(c, "EXPLOSION_EMITTER", 3, 1.0, 0.5, 1.0, 0);
            plugin.fx().sound(c, "entity.player.levelup", 1.5f, 0.7f);
            plugin.fx().sound(c, "ui.toast.challenge_complete", 1.5f, 1f);
            ItemStack stack = plugin.items().createWeapon(r.weapon, instance);
            if (stack != null && dropOnAltar(altar, stack, w)) {
                // предмет выпал на алтарь — запись об ожидающей награде больше не нужна
                plugin.db().update("DELETE FROM pending_rewards WHERE id=?", instance);
                send(w, altar, 3, true);
                plugin.adminLog("system", "ritual_done", "altar " + altar.id + " weapon " + r.weapon + " dropped on altar");
                return;
            }
        }
        // запасной путь: алтарь недоступен — награду получит владелец при входе/сейчас
        Player p = Bukkit.getPlayer(r.owner);
        if (p != null) {
            claimPending(p);
        }
    }

    /** Выдаёт все ожидающие награды игрока. Строка удаляется ДО выдачи — предмет не может выдаться дважды. */
    public void claimPending(Player p) {
        List<String[]> rows = plugin.db().query("SELECT id, weapon FROM pending_rewards WHERE owner=?",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, p.getUniqueId().toString());
        for (String[] row : rows) {
            if (plugin.db().update("DELETE FROM pending_rewards WHERE id=?", row[0]) < 1) {
                continue;
            }
            ItemStack it = plugin.items().createWeapon(row[1], row[0]);
            if (it == null) {
                plugin.getLogger().severe("Pending reward " + row[0] + " has unknown weapon " + row[1]);
                continue;
            }
            give(p, it);
            Weapon w = plugin.items().ritualItem(row[1]);
            plugin.msg(p, "ritual_done", "weapon", w == null ? row[1] : w.plainName());
        }
    }

    public static void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /** Отмена ритуала с возвратом материалов (процент берётся из crafting.cancel_refund_percent). */
    public boolean cancel(AltarManager.Altar altar, String actor) {
        Ritual r = active.remove(altar.id);
        if (r == null) {
            return false;
        }
        removeHolo(altar.id);
        if (r.task != null) {
            r.task.cancel();
        }
        plugin.db().update("DELETE FROM rituals WHERE altar_id=?", altar.id);
        Weapon w = plugin.items().ritualItem(r.weapon);
        if (w != null && w.unique()) {
            plugin.db().update("DELETE FROM unique_items WHERE weapon=? AND state='ritual'", r.weapon);
        }
        int percent = Math.max(0, Math.min(100, plugin.getConfig().getInt("crafting.cancel_refund_percent", 100)));
        Player owner = Bukkit.getPlayer(r.owner);
        Location drop = altar.location() == null ? null : altar.location().add(0.5, 1.2, 0.5);
        for (Map.Entry<String, Integer> e : r.reserved.entrySet()) {
            int amount = (int) Math.floor(e.getValue() * percent / 100.0);
            for (ItemStack st : stacks(e.getKey(), amount)) {
                if (owner != null) {
                    give(owner, st);
                } else if (drop != null && drop.getWorld() != null) {
                    drop.getWorld().dropItemNaturally(drop, st);
                }
            }
        }
        plugin.adminLog(actor, "ritual_cancel", "altar " + altar.id + " weapon " + r.weapon + " refund " + percent + "%");
        return true;
    }

    /** Предметы для возврата по ключу ингредиента с разбивкой на стаки. */
    private List<ItemStack> stacks(String key, int amount) {
        List<ItemStack> out = new java.util.ArrayList<>();
        if (amount <= 0) {
            return out;
        }
        if (key.startsWith("custom:")) {
            int left = amount;
            while (left > 0) {
                ItemStack it = plugin.items().createComponent(key.substring(7), left);
                if (it == null) {
                    break;
                }
                left -= it.getAmount();
                out.add(it);
            }
            return out;
        }
        Material m = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
        if (m == null) {
            return out;
        }
        int left = amount;
        int max = Math.max(1, m.getMaxStackSize());
        while (left > 0) {
            int n = Math.min(max, left);
            out.add(new ItemStack(m, n));
            left -= n;
        }
        return out;
    }
}
