package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Данжи «Проклятый замок»: редкие локации с компонентами для крафта (навершие, голова топора,
 * основа кинжала, пластина). Хранит список в БД, строит замок пакетами блоков, наполняет сундуки,
 * ставит босса и стражу, периодически обновляет добычу.
 */
public final class DungeonManager implements Listener {

    public static final class Dungeon {
        public final int id;
        public final String world;
        public final int x, y, z;
        /** Какой ивентовый предмет лежит в этом данже (id компонента). */
        public final String loot;

        public String title() {
            return DungeonBuilder.THEMES.get(loot)[0];
        }

        public String itemName() {
            return DungeonBuilder.THEMES.get(loot)[3];
        }

        Dungeon(int id, String world, int x, int y, int z, String loot) {
            this.loot = loot;
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final LegendaryPlugin plugin;
    private final Map<Integer, Dungeon> byId = new LinkedHashMap<>();
    private final Random rnd = new Random();
    private final NamespacedKey keyBoss;
    private boolean busy = false;

    public DungeonManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.keyBoss = new NamespacedKey(plugin, "dungeon_boss");
    }

    // ------------------------------------------------------------ данные

    public void load() {
        byId.clear();
        try {
            plugin.db().exec("ALTER TABLE dungeons ADD COLUMN loot TEXT");
        } catch (Exception ignored) {
            // колонка уже есть
        }
        for (Dungeon d : plugin.db().query("SELECT id, world, x, y, z, loot FROM dungeons ORDER BY id",
                r -> {
                    String l = r.getString(6);
                    if (l == null || !DungeonBuilder.THEMES.containsKey(l)) {
                        l = "mace_head";
                    }
                    return new Dungeon(r.getInt(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), l);
                })) {
            byId.put(d.id, d);
        }
    }

    public Collection<Dungeon> all() {
        return byId.values();
    }

    public Dungeon byId(int id) {
        return byId.get(id);
    }

    public boolean busy() {
        return busy;
    }

    public boolean remove(int id) {
        if (byId.remove(id) == null) {
            return false;
        }
        plugin.db().update("DELETE FROM dungeons WHERE id=?", id);
        return true;
    }

    /** Запуск таймеров: автообновление добычи/босса и автосоздание, если данжей ещё нет. */
    public void start() {
        long min = Math.max(1, plugin.getConfig().getInt("dungeon.refill_minutes", 60));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Dungeon d : new ArrayList<>(byId.values())) {
                refill(d);
            }
        }, min * 1200L, min * 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::bossTick, 100L, 80L);
        int auto = plugin.getConfig().getInt("dungeon.auto_spawn_count", 0);
        if (auto > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (byId.isEmpty() && !Bukkit.getWorlds().isEmpty()) {
                    plugin.getLogger().info("Данжей нет — создаю " + auto + " случайных.");
                    random(null, auto, Bukkit.getWorlds().get(0));
                }
            }, 200L);
        }
    }

    private void say(CommandSender s, String text) {
        if (s != null) {
            s.sendMessage(Util.color(plugin.getConfig().getString("messages.prefix", "")) + Util.color(text));
        }
    }

    // ------------------------------------------------------------ постройка

    /** Тип предмета для нового данжа: тот, чьих данжей на карте меньше всего (при равенстве — случайный). */
    private String pickType() {
        List<String> types = new ArrayList<>(DungeonBuilder.THEMES.keySet());
        java.util.Collections.shuffle(types, rnd);
        String best = types.get(0);
        long bestN = Long.MAX_VALUE;
        for (String t : types) {
            long n = byId.values().stream().filter(d -> d.loot.equals(t)).count();
            if (n < bestN) {
                bestN = n;
                best = t;
            }
        }
        return best;
    }

    public Dungeon spawnAt(CommandSender by, World w, int x, int z) {
        return spawnAt(by, w, x, z, null);
    }

    /** Строит замок на поверхности в точке (x, z) и регистрирует его. Возвращает null, если место занято. */
    public Dungeon spawnAt(CommandSender by, World w, int x, int z, String type) {
        for (Dungeon d : byId.values()) {
            if (d.world.equals(w.getName()) && Math.abs(d.x - x) < 45 && Math.abs(d.z - z) < 45) {
                say(by, "&cРядом уже стоит данж #" + d.id + ".");
                return null;
            }
        }
        int ch = DungeonBuilder.HALF >> 4;
        for (int cxk = (x >> 4) - ch - 1; cxk <= (x >> 4) + ch + 1; cxk++) {
            for (int czk = (z >> 4) - ch - 1; czk <= (z >> 4) + ch + 1; czk++) {
                w.getChunkAt(cxk, czk).load(true);
            }
        }
        int gy = w.getHighestBlockYAt(x, z);
        if (gy < w.getMinHeight() + 40 || gy > w.getMaxHeight() - 25) {
            say(by, "&cСлишком высоко или низко для замка.");
            return null;
        }
        final String loot = type != null && DungeonBuilder.THEMES.containsKey(type) ? type : pickType();
        long id = plugin.db().insert("INSERT INTO dungeons(world, x, y, z, loot) VALUES(?,?,?,?,?)", w.getName(), x, gy, z, loot);
        if (id <= 0) {
            say(by, "&cНе удалось записать данж в базу.");
            return null;
        }
        Dungeon d = new Dungeon((int) id, w.getName(), x, gy, z, loot);
        byId.put(d.id, d);
        plugin.adminLog(by == null ? "system" : by.getName(), "dungeon_spawn", "#" + d.id + " " + w.getName() + " " + x + " " + gy + " " + z);

        DungeonBuilder b = new DungeonBuilder(w, x, gy, z, loot);
        b.plan();
        final int total = b.ops.size();
        final int perTick = Math.max(500, plugin.getConfig().getInt("dungeon.blocks_per_tick", 4000));
        say(by, "&7Строю «" + d.title() + "» #" + d.id + " (" + total + " блоков), внутри: &6" + d.itemName() + "&7...");
        new BukkitRunnable() {
            int idx = 0;

            @Override
            public void run() {
                int end = Math.min(total, idx + perTick);
                for (; idx < end; idx++) {
                    DungeonBuilder.Op op = b.ops.get(idx);
                    w.getBlockAt(op.x(), op.y(), op.z()).setBlockData(op.data(), false);
                }
                if (idx >= total) {
                    cancel();
                    for (Runnable r : b.post) {
                        r.run();
                    }
                    fillChests(d);
                    spawnGuards(d);
                    spawnBoss(d);
                    say(by, "&aДанж «" + d.title() + "» &f#" + d.id + " &aпостроен: &f" + x + " " + gy + " " + z
                            + " &7(предмет: &6" + d.itemName() + "&7)");
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return d;
    }

    // ------------------------------------------------------------ поиск места

    private boolean suitable(World w, int x, int z) {
        int R = DungeonBuilder.HALF;
        int y = w.getHighestBlockYAt(x, z);
        Block g = w.getBlockAt(x, y, z);
        Material m = g.getType();
        if (g.isLiquid() || m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE
                || m.name().endsWith("LEAVES") || m.name().contains("LOG") || m.name().contains("MUSHROOM")) {
            return false;
        }
        String biome = g.getBiome().name();
        if (biome.contains("OCEAN") || biome.contains("RIVER") || biome.contains("BEACH") || biome.contains("SWAMP")) {
            return false;
        }
        if (y < w.getMinHeight() + 40 || y > w.getMaxHeight() - 40) {
            return false;
        }
        int slope = plugin.getConfig().getInt("dungeon.random.max_slope", 7);
        int[][] pts = {{R, 0}, {-R, 0}, {0, R}, {0, -R}, {R, R}, {-R, -R}, {R, -R}, {-R, R}};
        for (int[] c : pts) {
            int cy = w.getHighestBlockYAt(x + c[0], z + c[1]);
            if (w.getBlockAt(x + c[0], cy, z + c[1]).isLiquid() || Math.abs(cy - y) > slope) {
                return false;
            }
        }
        return true;
    }

    private boolean farEnough(World w, int x, int z, int minDist, int minAltar) {
        for (Dungeon d : byId.values()) {
            if (d.world.equals(w.getName())) {
                long dx = d.x - x, dz = d.z - z;
                if (dx * dx + dz * dz < (long) minDist * minDist) {
                    return false;
                }
            }
        }
        for (AltarManager.Altar a : plugin.altars().all()) {
            if (a.world.equals(w.getName())) {
                long dx = a.x - x, dz = a.z - z;
                if (dx * dx + dz * dz < (long) minAltar * minAltar) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Случайная расстановка нескольких данжей (по одной попытке за 2 тика, чтобы не вешать сервер). */
    public void random(CommandSender by, int count, World w) {
        if (busy) {
            say(by, "&cРасстановка уже идёт, подождите.");
            return;
        }
        final int radius = plugin.getConfig().getInt("dungeon.random.radius", 3000);
        final int minFromSpawn = plugin.getConfig().getInt("dungeon.random.min_from_spawn", 400);
        final int minDist = plugin.getConfig().getInt("dungeon.random.min_distance", 900);
        final int minAltar = plugin.getConfig().getInt("dungeon.random.min_altar_distance", 150);
        final int maxTries = plugin.getConfig().getInt("dungeon.random.max_tries", 500);
        final int cx = w.getSpawnLocation().getBlockX();
        final int cz = w.getSpawnLocation().getBlockZ();
        busy = true;
        say(by, "&7Ищу места для данжей (" + count + " шт.)...");
        new BukkitRunnable() {
            int tries = 0;
            int left = count;
            boolean building = false;

            @Override
            public void run() {
                if (building) {
                    if (!pendingBuild()) {
                        building = false;
                    }
                    return;
                }
                if (left <= 0 || tries >= maxTries) {
                    busy = false;
                    cancel();
                    if (left > 0) {
                        say(by, "&eНашлось места не для всех (осталось " + left + "). Увеличьте dungeon.random.radius.");
                    }
                    say(by, "&aГотово. Список: &f/legendary dungeon list");
                    return;
                }
                tries++;
                double ang = rnd.nextDouble() * Math.PI * 2;
                double dist = minFromSpawn + rnd.nextDouble() * Math.max(1, radius - minFromSpawn);
                int x = cx + (int) Math.round(Math.cos(ang) * dist);
                int z = cz + (int) Math.round(Math.sin(ang) * dist);
                if (!farEnough(w, x, z, minDist, minAltar)) {
                    return;
                }
                w.getChunkAt(x >> 4, z >> 4).load(true);
                if (!suitable(w, x, z)) {
                    return;
                }
                Dungeon d = spawnAt(by, w, x, z);
                if (d != null) {
                    left--;
                    tries = 0;
                    building = true;
                    buildStart = System.currentTimeMillis();
                }
            }

            private long buildStart;

            /** Ждём окончания постройки: стража и босс появляются в конце, подождём фиксированное время. */
            private boolean pendingBuild() {
                return System.currentTimeMillis() - buildStart < 8000L;
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    // ------------------------------------------------------------ сундуки

    private static final Material[][] EXTRAS = {
            {Material.BREAD, Material.ARROW, Material.IRON_INGOT, Material.COAL, Material.TORCH},
            {Material.GOLD_INGOT, Material.IRON_INGOT, Material.GOLDEN_APPLE, Material.EMERALD, Material.ENDER_PEARL},
            {Material.DIAMOND, Material.GOLDEN_APPLE, Material.EXPERIENCE_BOTTLE, Material.NETHERITE_SCRAP, Material.EMERALD}
    };

    /** Добыча сундука: ТОЛЬКО ивентовый предмет этого данжа (если повезёт) и обычные припасы. */
    private ItemStack[] rollLoot(Dungeon d, int tier) {
        List<ItemStack> out = new ArrayList<>();
        String base = "dungeon.loot.tier" + tier + ".";
        // тиры 0 и 1 (башни, зал, галерея, подвал, темница) — только припасы; ивентовый предмет лежит в самом низу
        double chance = tier >= 2 ? plugin.getConfig().getDouble(base + "chance", tier == 3 ? 1.0 : 0.20) : 0;
        int min = Math.max(1, plugin.getConfig().getInt(base + "min", 1));
        int max = Math.max(min, plugin.getConfig().getInt(base + "max", 1));
        if (rnd.nextDouble() < chance) {
            ItemStack comp = plugin.items().createComponent(d.loot, min + rnd.nextInt(max - min + 1));
            if (comp != null) {
                out.add(comp);
            }
        }
        // чертёж с рецептом оружия — только в нижних сундуках (за боссом 100%, по бокам 20%)
        double bp = tier >= 2 ? plugin.getConfig().getDouble(base + "recipe_chance", 0.0) : 0;
        if (rnd.nextDouble() < bp) {
            ItemStack book = plugin.items().createBlueprint(d.loot);
            if (book != null) {
                out.add(book);
            }
        }
        int extras = 2 + Math.min(tier, 2) * 2;
        for (int i = 0; i < extras; i++) {
            Material m = EXTRAS[Math.min(tier, 2)][rnd.nextInt(EXTRAS[Math.min(tier, 2)].length)];
            int amt = switch (m) {
                case ARROW -> 8 + rnd.nextInt(17);
                case BREAD, COAL, TORCH -> 4 + rnd.nextInt(9);
                case IRON_INGOT, GOLD_INGOT -> 2 + rnd.nextInt(5);
                default -> 1 + rnd.nextInt(2);
            };
            out.add(new ItemStack(m, amt));
        }
        return out.toArray(new ItemStack[0]);
    }

    private void fillChests(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) {
            return;
        }
        for (DungeonBuilder.ChestSpot c : DungeonBuilder.CHESTS) {
            Block b = w.getBlockAt(d.x + c.dx(), d.y + c.dy(), d.z + c.dz());
            if (b.getState() instanceof Chest ch) {
                Inventory inv = ch.getBlockInventory();
                inv.clear();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < inv.getSize(); i++) {
                    slots.add(i);
                }
                java.util.Collections.shuffle(slots, rnd);
                int k = 0;
                for (ItemStack it : rollLoot(d, c.tier())) {
                    if (k < slots.size()) {
                        inv.setItem(slots.get(k++), it);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ мобы

    private void equip(LivingEntity e, Material helm, Material chest, Material weapon) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) {
            return;
        }
        if (helm != null) {
            eq.setHelmet(new ItemStack(helm));
            eq.setHelmetDropChance(0f);
        }
        if (chest != null) {
            eq.setChestplate(new ItemStack(chest));
            eq.setChestplateDropChance(0f);
        }
        if (weapon != null) {
            eq.setItemInMainHand(new ItemStack(weapon));
            eq.setItemInMainHandDropChance(0f);
        }
    }

    private void keep(LivingEntity e, String name) {
        e.setCustomName(Util.color(name));
        e.setCustomNameVisible(false);
        e.setRemoveWhenFarAway(false);
        e.setPersistent(true);
    }

    private <T extends LivingEntity> void mob(World w, Location l, Class<T> type, String name, double hp,
                                              Material helm, Material chest, Material weapon, byte tag, boolean invisible) {
        w.spawn(l, type, e -> {
            keep(e, name);
            AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                a.setBaseValue(hp);
            }
            e.setHealth(hp);
            equip(e, helm, chest, weapon);
            if (invisible) {
                Util.addEffect(e, "invisibility", 1_000_000, 0);
            }
            if (tag != 0) {
                e.getPersistentDataContainer().set(keyBoss, PersistentDataType.BYTE, tag);
            }
        });
    }

    private Location at(World w, Dungeon d, int dx, int dy, int dz) {
        return new Location(w, d.x + dx + 0.5, d.y + dy, d.z + dz + 0.5);
    }

    /** Стража замка: двор, лучники на башнях и галерее, капитан с охраной в зале и нежить трёх подземных уровней. */
    private void spawnGuards(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null || !plugin.getConfig().getBoolean("dungeon.guards", true)) {
            return;
        }
        double hp = plugin.getConfig().getDouble("dungeon.guard_health", 30);
        // тема задаёт оружие и характер бойцов
        Material melee, brute;
        String meleeName, bruteName;
        switch (d.loot) {
            case "mace_head" -> {
                melee = Material.MACE;
                brute = Material.MACE;
                meleeName = "&7Булавоносец";
                bruteName = "&cМолотобоец";
            }
            case "axe_head" -> {
                melee = Material.IRON_AXE;
                brute = Material.DIAMOND_AXE;
                meleeName = "&7Дровосек";
                bruteName = "&4Палач";
            }
            case "dagger_base" -> {
                melee = Material.IRON_SWORD;
                brute = Material.IRON_SWORD;
                meleeName = "&8Тень";
                bruteName = "&5Убийца";
            }
            default -> {
                melee = Material.IRON_SWORD;
                brute = Material.DIAMOND_SWORD;
                meleeName = "&7Рыцарь";
                bruteName = "&9Латник";
            }
        }
        boolean shadow = d.loot.equals("dagger_base");
        int n = 0;
        for (int[] g : DungeonBuilder.GUARDS) {
            Location l = at(w, d, g[0], 1, g[1]);
            switch (n++ % 4) {
                case 0 -> mob(w, l, Zombie.class, meleeName, hp, Material.IRON_HELMET, Material.IRON_CHESTPLATE, melee, (byte) 0, shadow);
                case 1 -> mob(w, l, Skeleton.class, "&7Лучник замка", hp * 0.7, Material.IRON_HELMET, null, Material.BOW, (byte) 0, false);
                case 2 -> mob(w, l, Vindicator.class, bruteName, hp, Material.CHAINMAIL_HELMET, null, brute == Material.MACE ? Material.IRON_AXE : brute, (byte) 0, shadow);
                default -> mob(w, l, Pillager.class, "&7Арбалетчик", hp * 0.7, Material.CHAINMAIL_HELMET, null, null, (byte) 0, false);
            }
        }
        for (int[] a : DungeonBuilder.ARCHERS) {
            mob(w, at(w, d, a[0], a[1], a[2]), Skeleton.class, "&8Часовой", hp * 0.8, Material.IRON_HELMET, null, Material.BOW, (byte) 0, false);
        }
        int h = 0;
        for (int[] g : DungeonBuilder.HALL) {
            Location l = at(w, d, g[0], g[1], g[2]);
            if (h++ == 0) {
                mob(w, l, WitherSkeleton.class, "&4Капитан стражи", hp * 2.5, Material.DIAMOND_HELMET, Material.IRON_CHESTPLATE, brute, (byte) 0, false);
            } else {
                mob(w, l, Vindicator.class, bruteName, hp * 1.3, Material.IRON_HELMET, Material.CHAINMAIL_CHESTPLATE, brute == Material.MACE ? Material.IRON_AXE : brute, (byte) 0, shadow);
            }
        }
        for (int[] g : DungeonBuilder.UNDER) {
            Location l = at(w, d, g[0], g[1], g[2]);
            switch (g[3]) {
                case 2 -> mob(w, l, WitherSkeleton.class, "&8Вассал склепа", hp * 1.5, Material.IRON_HELMET, null, Material.STONE_SWORD, (byte) 2, false);
                case 1 -> mob(w, l, Zombie.class, bruteName, hp * 1.2, Material.IRON_HELMET, Material.CHAINMAIL_CHESTPLATE, brute, (byte) 0, shadow);
                default -> mob(w, l, Skeleton.class, "&8Костяной страж", hp, Material.CHAINMAIL_HELMET, null, Material.BOW, (byte) 0, false);
            }
        }
    }

    private boolean bossAlive(Dungeon d, World w) {
        Location c = new Location(w, d.x + 0.5, d.y - 21, d.z + 8.5);
        for (Entity e : w.getNearbyEntities(c, 14, 8, 14)) {
            Byte tag = e.getPersistentDataContainer().get(keyBoss, PersistentDataType.BYTE);
            if (tag != null && tag == 1 && !e.isDead()) {
                return true;
            }
        }
        return false;
    }

    private void spawnBoss(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null || !plugin.getConfig().getBoolean("dungeon.boss.enabled", true) || bossAlive(d, w)) {
            return;
        }
        Location l = new Location(w, d.x + DungeonBuilder.BOSS[0] + 0.5, d.y + DungeonBuilder.BOSS[1], d.z + DungeonBuilder.BOSS[2] + 2.5);
        w.spawn(l, WitherSkeleton.class, b -> {
            keep(b, plugin.getConfig().getString("dungeon.boss.name", "&4&lРыцарь-страж склепа"));
            b.setCustomNameVisible(true);
            double hp = plugin.getConfig().getDouble("dungeon.boss.health", 250);
            AttributeInstance a = b.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                a.setBaseValue(hp);
            }
            b.setHealth(hp);
            AttributeInstance dm = b.getAttribute(Attribute.ATTACK_DAMAGE);
            if (dm != null) {
                dm.setBaseValue(plugin.getConfig().getDouble("dungeon.boss.damage", 12));
            }
            equip(b, Material.NETHERITE_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_SWORD);
            b.getPersistentDataContainer().set(keyBoss, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Обновить добычу в сундуках и вернуть босса, если он погиб. Не трогает незагруженные чанки без нужды. */
    public void refill(Dungeon d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) {
            return;
        }
        w.getChunkAt(d.x >> 4, d.z >> 4).load(true);
        w.getChunkAt((d.x + 16) >> 4, (d.z + 16) >> 4).load(true);
        w.getChunkAt((d.x - 16) >> 4, (d.z - 16) >> 4).load(true);
        w.getChunkAt((d.x) >> 4, (d.z + 8) >> 4).load(true);
        fillChests(d);
        spawnBoss(d);
    }

    // ------------------------------------------------------------ убийство босса

    /** Способности босса: удар по земле, вызов вассалов, череп Иссушителя; при <50% здоровья — ярость. */
    private void bossTick() {
        for (Dungeon d : new ArrayList<>(byId.values())) {
            World w = Bukkit.getWorld(d.world);
            if (w == null || !w.isChunkLoaded((d.x) >> 4, (d.z + 8) >> 4)) {
                continue;
            }
            Location c = new Location(w, d.x + 0.5, d.y - 21, d.z + 8.5);
            LivingEntity boss = null;
            int minions = 0;
            for (Entity e : w.getNearbyEntities(c, 14, 8, 14)) {
                Byte tag = e.getPersistentDataContainer().get(keyBoss, PersistentDataType.BYTE);
                if (tag == null || e.isDead() || !(e instanceof LivingEntity le)) {
                    continue;
                }
                if (tag == 1) {
                    boss = le;
                } else {
                    minions++;
                }
            }
            if (boss == null) {
                continue;
            }
            List<Player> near = new ArrayList<>();
            for (Entity e : boss.getNearbyEntities(14, 8, 14)) {
                if (e instanceof Player p && p.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                    near.add(p);
                }
            }
            if (near.isEmpty()) {
                continue;
            }
            AttributeInstance mx = boss.getAttribute(Attribute.MAX_HEALTH);
            if (mx != null && boss.getHealth() < mx.getValue() * 0.5) {
                Util.addEffect(boss, "speed", 120, 1);
                Util.addEffect(boss, "strength", 120, 1);
            }
            Location bl = boss.getLocation();
            int roll = rnd.nextInt(3);
            if (roll == 0) {
                bl.getWorld().playSound(bl, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.6f);
                bl.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, bl.clone().add(0, 0.3, 0), 6, 2, 0.2, 2, 0);
                for (Player p : near) {
                    if (p.getLocation().distance(bl) <= 6) {
                        p.damage(plugin.getConfig().getDouble("dungeon.boss.slam_damage", 8), boss);
                        p.setVelocity(p.getLocation().toVector().subtract(bl.toVector()).setY(0.1).normalize().multiply(1.1).setY(0.5));
                    }
                }
            } else if (roll == 1 && minions < 4) {
                bl.getWorld().playSound(bl, org.bukkit.Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.8f);
                for (int k = 0; k < 2; k++) {
                    mob(w, bl.clone().add(rnd.nextInt(5) - 2, 0, rnd.nextInt(5) - 2), WitherSkeleton.class, "&8Вассал склепа",
                            plugin.getConfig().getDouble("dungeon.guard_health", 30) * 1.5,
                            Material.IRON_HELMET, null, Material.STONE_SWORD, (byte) 2, false);
                }
            } else {
                Player target = near.get(rnd.nextInt(near.size()));
                org.bukkit.util.Vector dir = target.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector()).normalize();
                boss.launchProjectile(WitherSkull.class, dir.multiply(1.2));
            }
        }
    }

    /** Мобы (зомби и т.д.) не должны наступать на нажимные плиты ловушек в данжах. */
    @EventHandler(ignoreCancelled = true)
    public void onMobPlate(org.bukkit.event.entity.EntityInteractEvent e) {
        if (e.getEntity() instanceof Player) {
            return;
        }
        Material t = e.getBlock().getType();
        if (t != Material.STONE_PRESSURE_PLATE && t != Material.POLISHED_BLACKSTONE_PRESSURE_PLATE) {
            return;
        }
        Location l = e.getBlock().getLocation();
        for (Dungeon d : byId.values()) {
            if (d.world.equals(l.getWorld().getName()) && Math.abs(d.x - l.getX()) < 40 && Math.abs(d.z - l.getZ()) < 40
                    && l.getY() < d.y + 20 && l.getY() > d.y - 40) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Byte tag = e.getEntity().getPersistentDataContainer().get(keyBoss, PersistentDataType.BYTE);
        if (tag == null || tag != 1) {
            return;
        }
        Dungeon near = null;
        for (Dungeon d : byId.values()) {
            if (d.world.equals(e.getEntity().getWorld().getName())
                    && Math.abs(d.x - e.getEntity().getLocation().getX()) < 30 && Math.abs(d.z - e.getEntity().getLocation().getZ()) < 30) {
                near = d;
            }
        }
        String killer = e.getEntity().getKiller() == null ? "?" : e.getEntity().getKiller().getName();
        int min = Math.max(1, plugin.getConfig().getInt("dungeon.boss.drop_min", 1));
        int max = Math.max(min, plugin.getConfig().getInt("dungeon.boss.drop_max", 2));
        String comp = near == null ? "armor_plate" : near.loot;
        ItemStack drop = plugin.items().createComponent(comp, min + rnd.nextInt(max - min + 1));
        if (drop != null) {
            e.getDrops().add(drop);
        }
        e.setDroppedExp(300);
        Bukkit.broadcastMessage(plugin.text("dungeon_boss_dead", "player", killer));
    }
}
