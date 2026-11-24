package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Невидимые барьеры (блок «барьер»): стена по периметру или сплошной объём в выбранной области.
 * Область задаётся координатами (чат) или «топором привата» (ЛКМ — первая точка, ПКМ — вторая).
 */
public final class BarrierManager implements Listener {

    public static final String WAND_ID = "f:barrier_wand";

    /** Выделение администратора. */
    public static final class Sel {
        public String world;
        public boolean hasA, hasB;
        public int ax, ay, az, bx, by, bz;
        /** true — по всей высоте мира, false — между высотами выделенных точек. */
        public boolean fullHeight = true;

        public boolean ready() {
            return hasA && hasB;
        }
    }

    public static final class Record {
        public final int id;
        public final String world, mode;
        public final int x1, z1, x2, z2, y1, y2;

        Record(int id, String world, String mode, int x1, int z1, int x2, int z2, int y1, int y2) {
            this.id = id;
            this.world = world;
            this.mode = mode;
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
            this.y1 = y1;
            this.y2 = y2;
        }
    }

    private final LegendaryPlugin plugin;
    private final Map<UUID, Sel> sels = new HashMap<>();
    private final Set<UUID> awaiting = new HashSet<>();
    private boolean busy = false;

    public BarrierManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ предмет-топор

    public ItemStack wand() {
        ItemStack it = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color("&6&lТопор привата"));
        m.setLore(List.of(Util.color("&7ЛКМ по блоку — первая точка"), Util.color("&7ПКМ по блоку — вторая точка"),
                Util.color("&7Затем: Звезда управления → Барьеры"), Util.color("&8Только для администраторов")));
        m.setEnchantmentGlintOverride(true);
        m.setUnbreakable(true);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        m.getPersistentDataContainer().set(plugin.keyId, PersistentDataType.STRING, WAND_ID);
        it.setItemMeta(m);
        return it;
    }

    private boolean isWand(ItemStack s) {
        return s != null && s.getType() == Material.GOLDEN_AXE && WAND_ID.equals(plugin.items().typeId(s));
    }

    private boolean admin(Player p) {
        return p.hasPermission("legendary.command.barrier") || p.hasPermission("legendary.admin");
    }

    public Sel sel(Player p) {
        return sels.computeIfAbsent(p.getUniqueId(), k -> new Sel());
    }

    public void clearSel(Player p) {
        sels.remove(p.getUniqueId());
    }

    public String describe(Sel s) {
        if (s == null || (!s.hasA && !s.hasB)) {
            return "не выбрано";
        }
        return (s.hasA ? "A(" + s.ax + " " + s.ay + " " + s.az + ")" : "A —") + " "
                + (s.hasB ? "B(" + s.bx + " " + s.by + " " + s.bz + ")" : "B —");
    }

    // ------------------------------------------------------------ события

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !isWand(e.getItem())) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!admin(p)) {
            p.sendMessage(Util.color("&cЭтот предмет работает только у администраторов."));
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        Sel s = sel(p);
        if (s.world != null && !s.world.equals(b.getWorld().getName())) {
            s.hasA = false;
            s.hasB = false;
        }
        s.world = b.getWorld().getName();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            s.hasA = true;
            s.ax = b.getX();
            s.ay = b.getY();
            s.az = b.getZ();
            p.sendMessage(Util.color("&6Точка A: &f" + s.ax + " " + s.ay + " " + s.az));
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            s.hasB = true;
            s.bx = b.getX();
            s.by = b.getY();
            s.bz = b.getZ();
            p.sendMessage(Util.color("&6Точка B: &f" + s.bx + " " + s.by + " " + s.bz));
        }
        if (s.ready()) {
            p.sendMessage(Util.color("&aОбласть выбрана: &f" + (Math.abs(s.ax - s.bx) + 1) + "x" + (Math.abs(s.az - s.bz) + 1)
                    + " &7(Звезда управления → Барьеры)"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sels.remove(e.getPlayer().getUniqueId());
        awaiting.remove(e.getPlayer().getUniqueId());
    }

    /** Ждём от админа координаты в чате. */
    public void awaitCoords(Player p) {
        awaiting.add(p.getUniqueId());
        p.sendMessage(Util.color("&6Введите в чат: &fx1 z1 x2 z2 &7[высота_от высота_до]"));
        p.sendMessage(Util.color("&7Пример: &f100 100 160 160 &7или &f100 100 160 160 60 90&7. Отмена: &fотмена"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!awaiting.contains(p.getUniqueId())) {
            return;
        }
        e.setCancelled(true);
        final String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            awaiting.remove(p.getUniqueId());
            if (msg.equalsIgnoreCase("отмена") || msg.equalsIgnoreCase("cancel")) {
                p.sendMessage(Util.color("&7Ввод отменён."));
                return;
            }
            String[] t = msg.split("\\s+");
            if (!setCoords(p, t, 0)) {
                p.sendMessage(Util.color("&cНе понял. Нужно: x1 z1 x2 z2 [высота_от высота_до]"));
            }
        });
    }

    /** Разбор x1 z1 x2 z2 [y1 y2] начиная с индекса from. */
    public boolean setCoords(Player p, String[] t, int from) {
        try {
            if (t.length - from < 4) {
                return false;
            }
            Sel s = sel(p);
            s.world = p.getWorld().getName();
            s.ax = Integer.parseInt(t[from]);
            s.az = Integer.parseInt(t[from + 1]);
            s.bx = Integer.parseInt(t[from + 2]);
            s.bz = Integer.parseInt(t[from + 3]);
            World w = p.getWorld();
            if (t.length - from >= 6) {
                s.ay = Integer.parseInt(t[from + 4]);
                s.by = Integer.parseInt(t[from + 5]);
                s.fullHeight = false;
            } else {
                s.ay = w.getMinHeight();
                s.by = w.getMaxHeight() - 1;
                s.fullHeight = true;
            }
            s.hasA = true;
            s.hasB = true;
            p.sendMessage(Util.color("&aОбласть задана: &f" + describe(s) + (s.fullHeight ? " &7(вся высота мира)" : "")));
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------ построение

    public enum Op { WALL, FILL, CLEAR_WALL, CLEAR_FILL }

    /** Выполнить операцию над выделением. */
    public void run(Player p, Op op) {
        Sel s = sel(p);
        if (!s.ready()) {
            p.sendMessage(Util.color("&cСначала выберите область: топором привата или по координатам."));
            return;
        }
        World w = Bukkit.getWorld(s.world);
        if (w == null) {
            p.sendMessage(Util.color("&cМир выделения не найден."));
            return;
        }
        int x1 = Math.min(s.ax, s.bx), x2 = Math.max(s.ax, s.bx);
        int z1 = Math.min(s.az, s.bz), z2 = Math.max(s.az, s.bz);
        int y1, y2;
        if (s.fullHeight) {
            y1 = w.getMinHeight();
            y2 = w.getMaxHeight() - 1;
        } else {
            y1 = Math.min(s.ay, s.by);
            y2 = Math.max(s.ay, s.by);
            if (y1 == y2) {
                y2 = Math.min(w.getMaxHeight() - 1, y1 + plugin.getConfig().getInt("barrier.default_wall_height", 6));
            }
        }
        execute(p, w, op, x1, z1, x2, z2, Math.max(y1, w.getMinHeight()), Math.min(y2, w.getMaxHeight() - 1), true);
    }

    private void execute(Player p, World w, Op op, int x1, int z1, int x2, int z2, int y1, int y2, boolean record) {
        if (busy) {
            p.sendMessage(Util.color("&cУже идёт другая операция с барьерами, подождите."));
            return;
        }
        final boolean wall = op == Op.WALL || op == Op.CLEAR_WALL;
        final boolean place = op == Op.WALL || op == Op.FILL;
        final int h = y2 - y1 + 1;
        final int dx = x2 - x1 + 1, dz = z2 - z1 + 1;
        // для стены — список колонок периметра
        final List<int[]> cols = new ArrayList<>();
        if (wall) {
            for (int x = x1; x <= x2; x++) {
                cols.add(new int[]{x, z1});
                if (z2 != z1) {
                    cols.add(new int[]{x, z2});
                }
            }
            for (int z = z1 + 1; z < z2; z++) {
                cols.add(new int[]{x1, z});
                if (x2 != x1) {
                    cols.add(new int[]{x2, z});
                }
            }
        }
        final long total = wall ? (long) cols.size() * h : (long) dx * dz * h;
        long max = plugin.getConfig().getLong("barrier.max_blocks", 3_000_000L);
        if (total > max) {
            p.sendMessage(Util.color("&cСлишком большая область: " + total + " блоков (максимум " + max + ", barrier.max_blocks)."));
            return;
        }
        busy = true;
        final int perTick = Math.max(500, plugin.getConfig().getInt("barrier.blocks_per_tick", 6000));
        p.sendMessage(Util.color("&7" + (place ? "Ставлю" : "Убираю") + " барьеры: " + total + " блоков проверяется..."));
        final long[] changed = {0};
        new BukkitRunnable() {
            long idx = 0;

            @Override
            public void run() {
                long end = Math.min(total, idx + perTick);
                for (; idx < end; idx++) {
                    int x, z, y;
                    if (wall) {
                        int[] c = cols.get((int) (idx / h));
                        x = c[0];
                        z = c[1];
                        y = y1 + (int) (idx % h);
                    } else {
                        long per = (long) dz * h;
                        x = x1 + (int) (idx / per);
                        long r = idx % per;
                        z = z1 + (int) (r / h);
                        y = y1 + (int) (r % h);
                    }
                    Block b = w.getBlockAt(x, y, z);
                    if (place) {
                        if (b.getType().isAir()) {
                            b.setType(Material.BARRIER, false);
                            changed[0]++;
                        }
                    } else if (b.getType() == Material.BARRIER) {
                        b.setType(Material.AIR, false);
                        changed[0]++;
                    }
                }
                if (idx >= total) {
                    cancel();
                    busy = false;
                    if (place && record && changed[0] > 0) {
                        plugin.db().insert("INSERT INTO barriers(world, mode, x1, z1, x2, z2, y1, y2, ts, actor) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                w.getName(), wall ? "wall" : "fill", x1, z1, x2, z2, y1, y2, System.currentTimeMillis(), p.getName());
                    }
                    plugin.adminLog(p.getName(), "barrier_" + op.name().toLowerCase(),
                            w.getName() + " " + x1 + "," + z1 + " .. " + x2 + "," + z2 + " y" + y1 + ".." + y2 + " changed=" + changed[0]);
                    if (p.isOnline()) {
                        p.sendMessage(Util.color("&aГотово: " + (place ? "поставлено " : "убрано ") + "&f" + changed[0] + " &aблоков-барьеров."));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------ журнал построенного

    public List<Record> records() {
        return plugin.db().query("SELECT id, world, mode, x1, z1, x2, z2, y1, y2 FROM barriers ORDER BY id DESC LIMIT 8",
                r -> new Record(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9)));
    }

    public Record record(int id) {
        List<Record> l = plugin.db().query("SELECT id, world, mode, x1, z1, x2, z2, y1, y2 FROM barriers WHERE id=?",
                r -> new Record(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9)), id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Убрать барьеры записи и саму запись. */
    public void removeRecord(Player p, Record r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) {
            p.sendMessage(Util.color("&cМир записи не найден."));
            return;
        }
        plugin.db().update("DELETE FROM barriers WHERE id=?", r.id);
        execute(p, w, r.mode.equals("wall") ? Op.CLEAR_WALL : Op.CLEAR_FILL, r.x1, r.z1, r.x2, r.z2, r.y1, r.y2, false);
    }

    public Location center(Record r) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) {
            return null;
        }
        int x = (r.x1 + r.x2) / 2, z = (r.z1 + r.z2) / 2;
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 2, z + 0.5);
    }
}
