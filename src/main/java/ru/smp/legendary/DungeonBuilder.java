package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Чертёж средневековой цитадели «Проклятый замок»: стены с зубцами, привратная башня,
 * четыре угловые башни, двор с колодцем и кузней, донжон с тронным залом и склеп с клетками и боссом.
 * Все координаты относительные: x, z — от центра, y — от уровня земли (0 = поверхность).
 */
final class DungeonBuilder {

    record Op(int x, int y, int z, BlockData data) {
    }

    /** Сундук с лутом: относительная позиция и уровень добычи (0 — башни, 1 — зал/камеры, 2 — сокровищница). */
    record ChestSpot(int dx, int dy, int dz, int tier) {
    }

    static final int HALF = 19;

    /** Тема данжа = какой ивентовый предмет в нём лежит. {название, цвет знамён, блок короны трона, описание} */
    static final java.util.Map<String, String[]> THEMES = new java.util.LinkedHashMap<>();

    static {
        THEMES.put("mace_head", new String[]{"Цитадель Молота", "red", "heavy_core", "Навершие булавы"});
        THEMES.put("axe_head", new String[]{"Крепость Палача", "orange", "iron_block", "Голова топора"});
        THEMES.put("dagger_base", new String[]{"Логово Убийц", "purple", "amethyst_block", "Основа кинжала"});
        THEMES.put("armor_plate", new String[]{"Оружейная Рыцарей", "blue", "copper_block", "Ивентовая пластина"});
    } // половина стороны площадки

    private static final String[] STONE = {"stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks",
            "mossy_stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "cracked_stone_bricks"};
    private static final String[] DARK = {"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks",
            "cracked_deepslate_bricks", "cracked_deepslate_bricks", "deepslate_tiles", "deepslate_tiles"};
    private static final String[] COBBLE = {"cobblestone", "cobblestone", "cobblestone", "mossy_cobblestone", "andesite", "stone"};
    private static final String[] GROUND = {"grass_block", "grass_block", "grass_block", "coarse_dirt", "coarse_dirt", "gravel", "cobblestone"};
    private static final String[] PATH = {"stone_bricks", "cobblestone", "stone_bricks", "gravel", "mossy_cobblestone"};
    private static final String[] HALLFLOOR = {"polished_andesite", "polished_andesite", "polished_andesite", "stone_bricks", "smooth_stone"};

    static final List<ChestSpot> CHESTS = List.of(
            // верхние этажи: только припасы (ивентового предмета тут нет)
            new ChestSpot(16, 7, 16, 0), new ChestSpot(-16, 7, 16, 0), new ChestSpot(16, 7, -16, 0), new ChestSpot(-16, 7, -16, 0),
            new ChestSpot(-5, 6, 5, 0),
            new ChestSpot(5, 1, -5, 1), new ChestSpot(5, 1, 5, 1),
            // подвал 1 и темница: тоже без предмета
            new ChestSpot(-6, -7, 13, 1), new ChestSpot(6, -7, 13, 1), new ChestSpot(7, -15, 5, 1),
            // самый низ: боковые хранилища (шанс) и сокровищница за боссом (гарантия)
            new ChestSpot(-7, -23, 13, 2), new ChestSpot(7, -23, 13, 2), new ChestSpot(0, -23, 6, 3));

    /** Двор: стража (dx, dz). */
    static final int[][] GUARDS = {{-4, 10}, {4, 10}, {-9, -6}, {9, -9}, {0, 12}, {-10, 8}, {11, 2}, {-11, -11},
            {-6, 0}, {6, 0}, {0, -9}, {-12, -2}, {12, -5}, {3, 14}, {-3, 14}, {0, 4}};
    /** Лучники: башни и галерея (dx, dy, dz). */
    static final int[][] ARCHERS = {{15, 7, 17}, {-15, 7, 17}, {15, 7, -15}, {-15, 7, -15}, {5, 6, -5}, {-5, 6, 0}};
    /** Тронный зал (dx, dy, dz): первая — капитан. */
    static final int[][] HALL = {{0, 1, -3}, {-2, 1, 2}, {2, 1, 2}, {5, 1, 0}};
    /** Подземелья (dx, dy, dz, вид): 0 — скелет-страж, 1 — тематический воин, 2 — вассал босса. */
    static final int[][] UNDER = {
            {-5, -7, 8, 1}, {5, -7, 8, 0}, {0, -7, 6, 1}, {-6, -7, 11, 0}, {6, -7, 11, 1},
            {0, -15, 13, 1}, {-4, -15, 13, 0}, {4, -15, 9, 1}, {-4, -15, 9, 0}, {0, -15, 5, 1}, {5, -15, 5, 0},
            {-5, -23, 8, 2}, {5, -23, 8, 2}, {-5, -23, 5, 0}, {5, -23, 5, 1}, {-7, -23, 11, 0}, {7, -23, 11, 1}};
    /** Босс на дне. */
    static final int[] BOSS = {0, -23, 8};

    final List<Op> ops = new ArrayList<>();
    final List<Runnable> post = new ArrayList<>();
    private final World w;
    private final int cx, gy, cz;
    private final Random rnd = new Random();
    private final Map<String, BlockData> cache = new HashMap<>();
    private final String main;
    private final String crown;
    private final String theme;
    private final String[] stone, dark, floor;
    private final String lampBlock;

    DungeonBuilder(World w, int cx, int gy, int cz, String themeId) {
        String theme = themeId;
        String[] t = THEMES.getOrDefault(theme, THEMES.get("mace_head"));
        this.main = t[1];
        this.crown = t[2];
        this.theme = THEMES.containsKey(theme) ? theme : "mace_head";
        String[] st, dk, fl;
        String lb = "soul_lantern[hanging=false]";
        switch (this.theme) {
            case "axe_head" -> {
                st = new String[]{"stone_bricks", "stone_bricks", "polished_blackstone_bricks", "polished_blackstone_bricks", "red_nether_bricks", "cracked_polished_blackstone_bricks"};
                dk = new String[]{"blackstone", "blackstone", "polished_blackstone_bricks", "polished_blackstone_bricks", "red_nether_bricks", "cracked_polished_blackstone_bricks"};
                fl = new String[]{"polished_blackstone", "polished_blackstone", "blackstone"};
                lb = "lantern[hanging=false]";
            }
            case "dagger_base" -> {
                st = new String[]{"deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "cracked_deepslate_bricks", "polished_deepslate", "stone_bricks"};
                dk = new String[]{"polished_blackstone", "polished_blackstone", "deepslate_tiles", "obsidian", "deepslate_bricks", "cracked_deepslate_bricks"};
                fl = new String[]{"deepslate_tiles", "polished_deepslate", "polished_blackstone"};
            }
            case "armor_plate" -> {
                st = new String[]{"stone_bricks", "stone_bricks", "stone_bricks", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "waxed_weathered_cut_copper"};
                dk = new String[]{"waxed_cut_copper", "waxed_exposed_cut_copper", "waxed_weathered_cut_copper", "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "deepslate_bricks"};
                fl = new String[]{"waxed_cut_copper", "waxed_exposed_cut_copper", "polished_deepslate"};
                lb = "lantern[hanging=false]";
            }
            default -> {
                st = STONE;
                dk = DARK;
                fl = new String[]{"polished_deepslate", "polished_deepslate", "deepslate_tiles"};
            }
        }
        this.stone = st;
        this.dark = dk;
        this.floor = fl;
        this.lampBlock = lb;
        this.w = w;
        this.cx = cx;
        this.gy = gy;
        this.cz = cz;
    }

    // ------------------------------------------------------------ примитивы

    private BlockData bd(String s) {
        return cache.computeIfAbsent(s, k -> {
            try {
                return Bukkit.createBlockData(k.contains(":") ? k : "minecraft:" + k);
            } catch (IllegalArgumentException ex) {
                // неизвестный блок в этой версии: не роняем всю постройку
                Bukkit.getLogger().warning("[LegendaryWeapons] Данж: неизвестный блок '" + k + "', заменён камнем");
                return Bukkit.createBlockData(Material.STONE);
            }
        });
    }

    private void set(int x, int y, int z, String b) {
        ops.add(new Op(cx + x, gy + y, cz + z, bd(b)));
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String... pal) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    set(x, y, z, pal.length == 1 ? pal[0] : pal[rnd.nextInt(pal.length)]);
                }
            }
        }
    }

    /** Зубцы по периметру квадрата радиуса r вокруг (ox, oz) на высоте y. */
    private void crenel(int ox, int oz, int r, int y, String block) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r && ((dx + dz) & 1) == 0) {
                    set(ox + dx, y, oz + dz, block);
                }
            }
        }
    }

    private void lampPost(int x, int z) {
        set(x, 1, z, "cobblestone_wall");
        set(x, 2, z, "cobblestone_wall");
        set(x, 3, z, "lantern[hanging=false]");
    }

    // ------------------------------------------------------------ чертёж

    void plan() {
        terrain();
        walls();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                tower(sx, sz);
            }
        }
        gatehouse();
        courtyard();
        keep();
        underground();
    }

    private void terrain() {
        fill(-HALF, 1, -HALF, HALF, 18, HALF, "air");
        fill(-HALF, -9, -HALF, HALF, -1, HALF, COBBLE);
        fill(-HALF, 0, -HALF, HALF, 0, HALF, GROUND);
        fill(-1, 0, 7, 1, 0, HALF, PATH); // дорога от ворот к донжону
        fill(-6, 0, 12, 6, 0, 12, PATH);  // поперечная дорожка
        // ров вокруг стен: у каждой темы свой
        String bridge = theme.equals("axe_head") ? "polished_blackstone" : "dark_oak_planks";
        for (int r = 17; r <= 18; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    switch (theme) {
                        case "axe_head" -> { // огненный ров
                            set(dx, 0, dz, "lava");
                            set(dx, -1, dz, "lava");
                            set(dx, -2, dz, "blackstone");
                        }
                        case "dagger_base" -> { // ров-ловушка: яма с шипами, затянутая паутиной
                            fill(dx, -3, dz, dx, 0, dz, "air");
                            set(dx, -3, dz, "pointed_dripstone[vertical_direction=up,thickness=tip]");
                            set(dx, -4, dz, "stone");
                            if (rnd.nextInt(3) > 0) {
                                set(dx, 0, dz, "cobweb");
                            }
                        }
                        default -> {
                            set(dx, 0, dz, "water");
                            set(dx, -1, dz, "water");
                            set(dx, -2, dz, "mud_bricks");
                        }
                    }
                }
            }
        }
        fill(-1, 0, 17, 1, 0, 18, bridge); // подъёмный мост
        set(-2, 1, 18, "dark_oak_fence");
        set(2, 1, 18, "dark_oak_fence");
    }

    private void walls() {
        fill(-16, 1, -16, 16, 6, 16, stone);
        fill(-14, 1, -14, 14, 6, 14, "air");
        crenel(0, 0, 16, 7, "stone_bricks");
        // лестницы на стену
        for (int x : new int[]{10, 11, -11, -10}) {
            for (int j = 0; j <= 5; j++) {
                int z = -9 - j;
                if (j >= 1) {
                    fill(x, 1, z, x, j, z, stone);
                }
                set(x, 1 + j, z, "stone_brick_stairs[facing=north,half=bottom]");
            }
        }
        // знамёна на внутренней стороне северной стены
        for (int x : new int[]{-6, 0, 6}) {
            set(x, 4, -14, (x == 0 ? "black" : main) + "_wall_banner[facing=south]");
        }
        // бойницы
        for (int k = -10; k <= 10; k += 5) {
            set(k, 3, -16, "iron_bars");
            set(-16, 3, k, "iron_bars");
            set(16, 3, k, "iron_bars");
        }
    }

    private void tower(int sx, int sz) {
        int ox = 16 * sx, oz = 16 * sz;
        fill(ox - 3, 1, oz - 3, ox + 3, 12, oz + 3, stone);
        fill(ox - 2, 1, oz - 2, ox + 2, 11, oz + 2, "air");
        fill(ox - 2, 6, oz - 2, ox + 2, 6, oz + 2, "dark_oak_planks");
        crenel(ox, oz, 3, 13, "stone_bricks");
        // дверь во двор (через грань, обращённую к центру) и лестница на верх
        set(13 * sx, 1, 14 * sz, "air");
        set(13 * sx, 2, 14 * sz, "air");
        set(ox, 6, oz - 2, "air");
        for (int y = 1; y <= 7; y++) {
            set(ox, y, oz - 2, "ladder[facing=south]");
        }
        // бойницы и светильники
        for (int y : new int[]{3, 9}) {
            set(19 * sx, y, oz, "iron_bars");
            set(ox, y, 19 * sz, "iron_bars");
        }
        set(ox + 1, 7, oz + 1, "lantern[hanging=false]");
        set(ox - 1, 7, oz + 1, "lantern[hanging=false]");
        set(ox, 7, oz, "chest[facing=" + (sz > 0 ? "north" : "south") + "]");
        // знамя на внешней грани
        set(ox, 10, 19 * sz + sz, (sx == sz ? main : "black") + "_wall_banner[facing=" + (sz > 0 ? "south" : "north") + "]");
    }

    private void gatehouse() {
        fill(-4, 1, 14, 4, 9, 17, stone);
        fill(-1, 1, 14, 1, 4, 17, "air");
        fill(-4, 7, 15, 4, 8, 16, "air"); // переход по верху ворот
        for (int x = -1; x <= 1; x++) {
            set(x, 4, 17, "iron_bars"); // поднятая решётка
            set(x, 4, 14, "iron_bars");
        }
        // арка
        set(-2, 4, 17, "stone_brick_stairs[facing=east,half=top]");
        set(2, 4, 17, "stone_brick_stairs[facing=west,half=top]");
        for (int z = 14; z <= 17; z++) {
            if ((z & 1) == 0) {
                set(-4, 10, z, "stone_bricks");
                set(4, 10, z, "stone_bricks");
            }
        }
        for (int x = -4; x <= 4; x++) {
            set(x, 10, 17, ((x & 1) == 0) ? "stone_bricks" : "air");
            set(x, 10, 14, ((x & 1) == 0) ? "stone_bricks" : "air");
        }
        set(-2, 6, 18, main + "_wall_banner[facing=south]");
        set(2, 6, 18, main + "_wall_banner[facing=south]");
        set(0, 7, 18, "black_wall_banner[facing=south]");
        lampPost(-3, 19);
        lampPost(3, 19);
        lampPost(-3, 11);
        lampPost(3, 11);
    }

    private void courtyard() {
        // колодец
        fill(-9, 1, 5, -7, 1, 7, "stone_bricks");
        set(-8, 1, 6, "air");
        set(-8, 0, 6, "water");
        for (int[] c : new int[][]{{-9, 5}, {-7, 5}, {-9, 7}, {-7, 7}}) {
            set(c[0], 2, c[1], "dark_oak_fence");
            set(c[0], 3, c[1], "dark_oak_fence");
        }
        fill(-9, 4, 5, -7, 4, 7, "dark_oak_slab[type=bottom]");
        // кузня
        set(9, 1, 5, "smithing_table");
        set(9, 1, 6, "blast_furnace[facing=west,lit=false]");
        set(9, 1, 7, "anvil[facing=north]");
        set(10, 1, 6, "cauldron");
        set(10, 1, 5, "grindstone[face=floor,facing=north]");
        // рынок
        fill(-11, 1, -8, -9, 1, -6, "hay_block");
        set(-10, 2, -7, "barrel[facing=up]");
        set(-9, 2, -8, "barrel[facing=up]");
        set(-11, 2, -6, main + "_carpet");
        // лагерь стражи
        set(-4, 1, -12, "campfire[lit=true,facing=north]");
        set(-3, 1, -12, "hay_block");
        set(-5, 1, -12, "barrel[facing=up]");
        // знамя у донжона
        lampPost(-2, 9);
        lampPost(2, 9);
        statue();
        // эшафот
        fill(5, 1, 9, 7, 1, 10, "dark_oak_planks");
        set(6, 2, 10, "dark_oak_fence");
        set(6, 3, 10, "dark_oak_fence");
        set(6, 4, 10, "dark_oak_fence");
        set(7, 4, 10, "dark_oak_fence");
        set(7, 3, 10, "lantern[hanging=true]");
    }

    private void keep() {
        fill(-7, 1, -7, 7, 10, 7, stone);
        fill(-6, 1, -6, 6, 9, 6, "air");
        fill(-6, 0, -6, 6, 0, 6, HALLFLOOR);
        crenel(0, 0, 7, 11, "stone_bricks");
        for (int[] c : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) {
            set(c[0], 11, c[1], "stone_bricks");
            set(c[0], 12, c[1], "cobblestone_wall");
            set(c[0], 13, c[1], "cobblestone_wall");
            set(c[0], 14, c[1], "lantern[hanging=false]");
        }
        // вход и бойницы
        fill(-1, 1, 7, 1, 3, 7, "air");
        set(-2, 3, 7, "stone_brick_stairs[facing=east,half=top]");
        set(2, 3, 7, "stone_brick_stairs[facing=west,half=top]");
        for (int y = 4; y <= 6; y++) {
            for (int k : new int[]{-4, 4}) {
                set(7, y, k, "iron_bars");
                set(-7, y, k, "iron_bars");
                set(k, y, 7, "iron_bars");
            }
        }
        set(-6, 5, 7, main + "_wall_banner[facing=north]");
        // колонны
        for (int[] c : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
            fill(c[0], 1, c[1], c[0], 8, c[1], "stripped_dark_oak_log[axis=y]");
            set(c[0], 9, c[1], "chiseled_stone_bricks");
            set(c[0], 1, c[1], "polished_andesite");
        }
        // дорожка и трон
        fill(-1, 1, -4, 1, 1, 6, main + "_carpet");
        fill(-3, 1, -6, 3, 1, -4, "polished_andesite");
        set(0, 2, -5, "dark_oak_stairs[facing=north,half=bottom]");
        set(0, 2, -6, "dark_oak_planks");
        set(0, 3, -6, "dark_oak_planks");
        set(0, 4, -6, crown);
        for (int x : new int[]{-2, 2}) {
            set(x, 4, -6, main + "_wall_banner[facing=south]");
            set(x, 2, -5, "candle[candles=3,lit=true]");
        }
        set(-5, 4, -6, "black_wall_banner[facing=south]");
        set(5, 4, -6, "black_wall_banner[facing=south]");
        // стол
        fill(4, 1, -1, 5, 1, 3, "spruce_slab[type=bottom]");
        set(4, 2, 0, "candle[candles=2,lit=true]");
        set(5, 2, 2, "candle[candles=4,lit=true]");
        // люстры
        for (int[] c : new int[][]{{0, 0}, {0, 2}, {0, -2}, {2, 0}, {-2, 0}}) {
            set(c[0], 9, c[1], "dark_oak_fence");
            set(c[0], 8, c[1], "dark_oak_fence");
            set(c[0], 7, c[1], "lantern[hanging=true]");
        }
        // сундуки донжона
        set(5, 1, -5, "chest[facing=west]");
        set(5, 1, 5, "chest[facing=west]");
        gallery();
        // лестница в склеп
        for (int k = 0; k <= 7; k++) {
            int z = -5 + k;
            if (k >= 1) {
                fill(-5, -k + 1, z, -5, -k + 3, z, "air");
            }
            set(-5, -k, z, "stone_brick_stairs[facing=north,half=bottom]");
        }
        set(-5, 1, -6, "wall_torch[facing=south]");
    }

    // ------------------------------------------------------------ второй этаж донжона

    private void gallery() {
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) >= 4 && !(x == 6 && z >= 2)) {
                    set(x, 5, z, "spruce_planks");
                    if (Math.max(Math.abs(x), Math.abs(z)) == 4 && Math.abs(x) <= 4 && Math.abs(z) <= 4) {
                        set(x, 6, z, "dark_oak_fence"); // перила
                    }
                }
            }
        }
        // лестница на галерею
        for (int k = 0; k <= 3; k++) {
            if (k >= 1) {
                fill(6, 1, 5 - k, 6, k, 5 - k, stone);
            }
            set(6, 1 + k, 5 - k, "stone_brick_stairs[facing=north,half=bottom]");
        }
        for (int x = -5; x <= 5; x++) {
            set(x, 6, -6, "bookshelf");
            set(x, 7, -6, "bookshelf");
        }
        for (int z = -5; z <= 5; z++) {
            set(-6, 6, z, "bookshelf");
            if (z <= 0) {
                set(6, 6, z, "bookshelf");
            }
        }
        for (int[] c : new int[][]{{-5, -5}, {5, -5}, {-5, 5}}) {
            set(c[0], 9, c[1], "lantern[hanging=true]");
        }
        set(-5, 6, 5, "chest[facing=east]");
        switch (theme) {
            case "mace_head" -> {
                set(-5, 6, -5, "anvil[facing=east]");
                set(5, 6, -5, "iron_block");
            }
            case "axe_head" -> {
                set(-5, 6, -5, "grindstone[face=floor,facing=north]");
                set(5, 6, -4, "red_carpet");
            }
            case "dagger_base" -> {
                for (int i = 0; i < 10; i++) {
                    set(-5 + rnd.nextInt(11), 6, -5 + rnd.nextInt(11), "cobweb");
                }
            }
            default -> {
                armorStand(-5, 6, -3, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                armorStand(5, 6, 3, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
            }
        }
    }

    // ------------------------------------------------------------ статуя на дворе

    private void statue() {
        switch (theme) {
            case "mace_head" -> { // исполинская булава
                fill(-2, 1, -13, 2, 1, -9, "stone_bricks");
                fill(0, 2, -11, 0, 8, -11, "stripped_dark_oak_log[axis=y]");
                fill(-1, 9, -12, 1, 11, -10, "iron_block");
                set(0, 10, -11, "heavy_core");
                for (int[] c : new int[][]{{-2, 10, -11}, {2, 10, -11}, {0, 10, -13}, {0, 10, -9}}) {
                    set(c[0], c[1], c[2], "iron_bars");
                }
                set(0, 12, -11, "lightning_rod[facing=up]");
                set(-3, 1, -9, "anvil[facing=north]");
                set(3, 1, -9, "anvil[facing=south]");
            }
            case "axe_head" -> { // исполинский топор в плахе
                fill(-2, 1, -13, 2, 1, -9, "polished_blackstone_bricks");
                fill(0, 2, -11, 0, 9, -11, "stripped_dark_oak_log[axis=y]");
                fill(1, 6, -11, 3, 9, -11, "iron_block");
                fill(4, 6, -11, 4, 9, -11, "iron_bars");
                set(0, 10, -11, "dark_oak_fence");
                fill(-3, 1, -7, -2, 1, -6, "red_terracotta");
                set(-2, 2, -6, "stripped_oak_log[axis=y]");
                set(-3, 2, -7, "grindstone[face=floor,facing=east]");
            }
            case "dagger_base" -> { // кинжал, вонзённый в землю
                fill(-2, 1, -13, 2, 1, -9, "deepslate_tiles");
                fill(0, 2, -11, 0, 7, -11, "quartz_pillar[axis=y]");
                fill(-2, 8, -11, 2, 8, -11, "dark_oak_planks");
                fill(0, 9, -11, 0, 11, -11, "stripped_dark_oak_log[axis=y]");
                set(0, 12, -11, "amethyst_block");
                set(-3, 1, -9, "amethyst_cluster[facing=up]");
                set(3, 1, -9, "amethyst_cluster[facing=up]");
            }
            default -> { // рыцарь из меди
                fill(-2, 1, -13, 2, 1, -9, "waxed_cut_copper");
                fill(-1, 2, -11, -1, 4, -11, "waxed_oxidized_cut_copper");
                fill(1, 2, -11, 1, 4, -11, "waxed_oxidized_cut_copper");
                fill(-1, 5, -11, 1, 7, -11, "waxed_weathered_cut_copper");
                set(-2, 6, -11, "waxed_cut_copper");
                set(2, 6, -11, "waxed_cut_copper");
                fill(0, 8, -11, 0, 9, -11, "copper_block");
                set(0, 10, -11, "red_wool");
                set(0, 11, -11, "lightning_rod[facing=up]");
                set(-3, 6, -11, "iron_bars");
            }
        }
    }

    // ------------------------------------------------------------ подземелья: подвал, темница, склеп (3 уровня)

    private void tntPlate(int x, int floorY, int z) {
        set(x, floorY - 1, z, "tnt");
        set(x, floorY, z, "polished_deepslate");
        set(x, floorY + 1, z, "stone_pressure_plate");
    }

    private void lamp(int x, int y, int z) {
        set(x, y, z, lampBlock);
    }

    private void stairRun(int x, int y0, int z0) {
        for (int k = 0; k <= 7; k++) {
            fill(x, y0 - k + 1, z0 + k, x, y0 - k + 3, z0 + k, "air");
            set(x, y0 - k, z0 + k, "deepslate_brick_stairs[facing=north,half=bottom]");
        }
        fill(x, y0 - 7, z0 + 8, x, y0 - 5, z0 + 8, "air");
    }

    private void spawner(int x, int y, int z, EntityType type) {
        set(x, y, z, "spawner");
        final int ax = cx + x, ay = gy + y, az = cz + z;
        post.add(() -> setSpawner(w.getBlockAt(ax, ay, az), type));
    }

    private void armorStand(int x, int y, int z, Material h, Material c, Material l, Material b) {
        final Location loc = new Location(w, cx + x + 0.5, gy + y, cz + z + 0.5, rnd.nextInt(4) * 90f, 0f);
        post.add(() -> w.spawn(loc, ArmorStand.class, a -> {
            a.getEquipment().setHelmet(new ItemStack(h));
            a.getEquipment().setChestplate(new ItemStack(c));
            a.getEquipment().setLeggings(new ItemStack(l));
            a.getEquipment().setBoots(new ItemStack(b));
            a.setArms(true);
            a.setPersistent(true);
        }));
    }

    private void underground() {
        // общий массив: три уровня комнат 17x11 (x -8..8, z 4..14), соединённых лестницами
        fill(-10, -26, 3, 10, -2, 15, dark);
        for (int[] lv : new int[][]{{-7, -3, -8}, {-15, -11, -16}, {-23, -19, -24}}) {
            fill(-8, lv[0], 4, 8, lv[1], 14, "air");
            fill(-8, lv[2], 4, 8, lv[2], 14, floor);
        }
        fill(-5, -7, 3, -5, -5, 3, "air"); // выход лестницы из зала
        stairRun(9, -8, 4);    // подвал -> темница
        stairRun(-9, -16, 4);  // темница -> склеп

        // ===== Уровень 1: казармы и погреба
        for (int x : new int[]{-4, 4}) {
            for (int z : new int[]{7, 11}) {
                fill(x, -7, z, x, -3, z, "polished_deepslate");
            }
        }
        for (int[] c : new int[][]{{-2, 6}, {2, 6}, {-6, 9}, {6, 9}, {-2, 12}, {2, 12}}) {
            lamp(c[0], -7, c[1]);
        }
        set(-8, -7, 6, "barrel[facing=up]");
        set(-8, -7, 7, "barrel[facing=up]");
        set(8, -7, 6, "barrel[facing=up]");
        set(-6, -7, 13, "chest[facing=north]");
        set(6, -7, 13, "chest[facing=north]");
        set(-5, -7, 13, "wither_rose");
        set(5, -7, 13, "wither_rose");
        spawner(-8, -7, 14, EntityType.ZOMBIE);
        spawner(8, -7, 14, EntityType.SKELETON);
        // ловушки: шипастая яма у входа, скрытый динамит под нажимными плитами, огонь, ягодные кусты
        fill(-6, -8, 5, -4, -8, 6, "air");
        fill(-6, -9, 5, -4, -9, 6, "pointed_dripstone[vertical_direction=up,thickness=tip]");
        tntPlate(-2, -8, 6);
        tntPlate(2, -8, 9);
        tntPlate(-3, -8, 12);
        tntPlate(1, -8, 4);
        for (int x = 0; x <= 4; x++) {
            set(x, -8, 13, "netherrack");
            set(x, -7, 13, "fire");
        }
        for (int x = 5; x <= 7; x++) {
            for (int z = 7; z <= 8; z++) {
                set(x, -7, z, "sweet_berry_bush[age=3]");
            }
        }
        for (int i = 0; i < 14; i++) {
            set(-8 + rnd.nextInt(17), -3, 4 + rnd.nextInt(11), "cobweb");
        }

        // ===== Уровень 2: темница-змейка (вход справа, выход слева)
        fill(-8, -15, 11, 8, -11, 11, dark);
        fill(-8, -15, 11, -6, -11, 11, "air");
        fill(-8, -15, 7, 8, -11, 7, dark);
        fill(6, -15, 7, 8, -11, 7, "air");
        for (int x = -4; x <= 4; x += 2) {
            set(x, -13, 11, "iron_bars");
            set(x, -13, 7, "iron_bars");
        }
        for (int[] c : new int[][]{{-4, 13}, {4, 13}, {-4, 9}, {4, 9}, {-4, 5}, {4, 5}}) {
            lamp(c[0], -15, c[1]);
        }
        tntPlate(2, -16, 13);
        tntPlate(-3, -16, 12);
        tntPlate(-3, -16, 14);
        tntPlate(0, -16, 5);
        tntPlate(4, -16, 5);
        for (int x = -4; x <= 4; x++) { // канатоходец: лава по бокам, узкая дорожка по центру
            set(x, -16, 8, "lava");
            set(x, -16, 10, "lava");
        }
        for (int x = -3; x <= 1; x++) {
            set(x, -16, 6, "netherrack");
            set(x, -15, 6, "fire");
        }
        for (int i = 0; i < 18; i++) {
            set(-8 + rnd.nextInt(17), -11, 4 + rnd.nextInt(11), "pointed_dripstone[vertical_direction=down,thickness=tip]");
        }
        for (int i = 0; i < 14; i++) {
            set(-8 + rnd.nextInt(17), -15, 4 + rnd.nextInt(11), "cobweb");
        }
        set(7, -15, 5, "chest[facing=west]");
        set(6, -15, 5, "wither_rose");
        spawner(8, -15, 14, EntityType.ZOMBIE);
        spawner(-8, -15, 8, EntityType.SPIDER);
        spawner(8, -15, 4, EntityType.CAVE_SPIDER);

        // ===== Уровень 3: склеп босса и хранилища (низ данжа)
        for (int x : new int[]{-5, 5}) {
            for (int z : new int[]{7, 11}) {
                fill(x, -23, z, x, -19, z, "polished_deepslate");
            }
        }
        for (int[] c : new int[][]{{-7, 7}, {7, 7}, {-7, 11}, {7, 11}, {-2, 13}, {2, 13}}) {
            lamp(c[0], -23, c[1]);
        }
        fill(-1, -23, 7, 1, -23, 9, "polished_blackstone_bricks");
        set(0, -22, 8, "chiseled_polished_blackstone");
        set(-1, -22, 7, "soul_campfire[lit=true]");
        set(1, -22, 9, "soul_campfire[lit=true]");
        for (int x = -3; x <= 3; x++) { // лавовый ров вокруг босса, мостик с севера
            for (int z = 5; z <= 11; z++) {
                if ((Math.abs(x) == 3 || z == 5 || z == 11) && !(x == 0 && z == 5)) {
                    set(x, -24, z, "lava");
                }
            }
        }
        set(0, -24, 5, "polished_blackstone_bricks");
        set(0, -23, 6, "chest[facing=north]");
        set(-7, -23, 13, "chest[facing=north]");
        set(7, -23, 13, "chest[facing=north]");
        set(-6, -23, 13, "wither_rose");
        set(6, -23, 13, "wither_rose");
        tntPlate(-5, -24, 13);
        tntPlate(5, -24, 13);
        fill(-7, -29, 9, -5, -24, 10, "air");  // глубокая яма с шипами на пути
        fill(-7, -29, 9, -5, -29, 10, "pointed_dripstone[vertical_direction=up,thickness=tip]");
        spawner(-8, -23, 5, EntityType.VINDICATOR);
        spawner(8, -23, 5, EntityType.WITHER_SKELETON);
        for (int i = 0; i < 8; i++) {
            set(-8 + rnd.nextInt(17), -23, 4 + rnd.nextInt(7), "skeleton_skull[rotation=" + rnd.nextInt(16) + "]");
        }
        underDecor();
    }

    /** Тематические детали подземелий: у каждого предмета свой «характер». */
    private void underDecor() {
        switch (theme) {
            case "mace_head" -> { // молотобойня: наковальни, железные блоки, громоотводы
                set(-7, -7, 9, "anvil[facing=east]");
                set(7, -7, 9, "anvil[facing=west]");
                set(-7, -7, 10, "iron_block");
                set(-7, -6, 10, "heavy_core");
                set(7, -7, 10, "iron_block");
                set(7, -6, 10, "heavy_core");
                for (int[] c : new int[][]{{-6, 6}, {6, 6}, {-6, 10}, {6, 10}}) {
                    set(c[0], -23, c[1], "iron_block");
                    set(c[0], -22, c[1], "lightning_rod[facing=up]");
                }
                for (int x = -6; x <= 6; x += 3) {
                    set(x, -15, 12, "anvil[facing=north]");
                }
            }
            case "axe_head" -> { // мясницкая: кровь, плахи, клетки, лавовые озёра
                fill(-2, -8, 8, 2, -8, 10, "red_nether_bricks");
                set(-7, -7, 9, "cauldron");
                set(7, -7, 9, "grindstone[face=floor,facing=north]");
                set(-2, -7, 9, "stripped_oak_log[axis=y]");
                set(2, -7, 9, "stripped_oak_log[axis=y]");
                for (int[] c : new int[][]{{-7, 7}, {7, 7}}) {
                    fill(c[0] - 1, -7, c[1], c[0] + 1, -5, c[1], "iron_bars");
                }
                for (int x = -6; x <= 6; x += 4) {
                    set(x, -16, 9, "red_nether_bricks");
                }
                set(-7, -24, 8, "lava");
                set(7, -24, 8, "lava");
            }
            case "dagger_base" -> { // логово теней: скалк, паутина, аметисты
                for (int i = 0; i < 40; i++) {
                    set(-8 + rnd.nextInt(17), -8, 4 + rnd.nextInt(11), "sculk");
                }
                for (int i = 0; i < 20; i++) {
                    set(-8 + rnd.nextInt(17), -16, 4 + rnd.nextInt(11), "sculk");
                }
                for (int i = 0; i < 4; i++) {
                    set(-8 + rnd.nextInt(17), -7, 4 + rnd.nextInt(11), "sculk_sensor");
                }
                for (int i = 0; i < 10; i++) {
                    set(-8 + rnd.nextInt(17), -15, 4 + rnd.nextInt(11), "amethyst_cluster[facing=up]");
                }
                for (int[] c : new int[][]{{-5, 7}, {5, 7}, {-5, 11}, {5, 11}}) {
                    set(c[0], -23, c[1], "crying_obsidian");
                }
            }
            default -> { // арсенал рыцарей: доспехи на стойках, кузнечные столы, медные громоотводы
                armorStand(-7, -7, 8, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                armorStand(7, -7, 8, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                armorStand(-3, -7, 5, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                set(-7, -7, 10, "smithing_table");
                set(7, -7, 10, "anvil[facing=east]");
                for (int[] c : new int[][]{{-6, 6}, {6, 6}, {-6, 10}, {6, 10}}) {
                    set(c[0], -23, c[1], "copper_block");
                    set(c[0], -22, c[1], "lightning_rod[facing=up]");
                }
                fill(-2, -23, 4, 2, -23, 4, "waxed_cut_copper_slab[type=bottom]");
            }
        }
    }

    private static void setSpawner(Block b, EntityType type) {
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            cs.setRequiredPlayerRange(16);
            cs.setMaxNearbyEntities(4);
            cs.update(true, false);
        }
    }
}
