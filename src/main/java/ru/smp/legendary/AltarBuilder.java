package ru.smp.legendary;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Строит большой тематический алтарь. Радиус платформы задаётся в config.yml (altars.radius, по умолчанию 7),
 * то есть площадка 15x15 против прежних 5x5 (примерно в 9 раз по площади), плюс колонны до 12 блоков высотой.
 *
 * Состав: круглая площадка с лучами-акцентами и каймой, ступенчатое основание, возвышение (дais),
 * на котором стоит центральный блок, 8 колонн (4 высоких по сторонам света и 4 пониже по диагоналям)
 * с цветным навершием и светящимся блоком, а над центром — парящее светящееся кольцо.
 * Постройка заменяет блоки в радиусе: воздух над площадкой очищается.
 */
public final class AltarBuilder {

    private AltarBuilder() {
    }

    /** Масштаб алтаря (config altars.scale; 1.2 = на 20% больше прежнего размера). */
    public static double scale(LegendaryPlugin plugin) {
        return Math.max(0.8, Math.min(2.0, plugin.getConfig().getDouble("altars.scale", 1.2)));
    }

    /** Радиус платформы в блоках (округлён вверх). */
    public static int radius(LegendaryPlugin plugin) {
        double base = Math.max(5, Math.min(12, plugin.getConfig().getDouble("altars.radius", 7)));
        return (int) Math.ceil(base * scale(plugin));
    }

    private static int sc(LegendaryPlugin plugin, int v) {
        return (int) Math.round(v * scale(plugin));
    }

    private static void data(World w, int x, int y, int z, String bd) {
        try {
            w.getBlockAt(x, y, z).setBlockData(org.bukkit.Bukkit.createBlockData(bd), false);
        } catch (IllegalArgumentException ignored) {
            // блок отсутствует в этой версии — пропускаем деталь
        }
    }

    private static void put(Block b, Material m) {
        b.setType(m, false);
    }

    private static boolean replaceable(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || b.isPassable();
    }

    /**
     * @param ground блок земли, на который встаёт площадка (на его уровне лежит пол)
     * @return центральный блок алтаря
     */
    public static Block build(LegendaryPlugin plugin, Block ground, AltarManager.Theme t, String theme) {
        final int R = radius(plugin);
        final int tallH = sc(plugin, 12);
        final int lowH = sc(plugin, 8);
        final int ringY = sc(plugin, 8);
        final World w = ground.getWorld();
        final int cx = ground.getX();
        final int cy = ground.getY();
        final int cz = ground.getZ();

        // 1. Очистка объёма над площадкой и основание под ней
        for (int dx = -(R + 1); dx <= R + 1; dx++) {
            for (int dz = -(R + 1); dz <= R + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 1.5) {
                    continue;
                }
                for (int dy = 1; dy <= tallH + 6; dy++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!b.getType().isAir()) {
                        put(b, Material.AIR);
                    }
                }
                // основание: заполняем пустоты под площадкой, чтобы она не висела в воздухе
                if (d <= R + 1.0) {
                    for (int dy = -1; dy >= -4; dy--) {
                        Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                        if (replaceable(b)) {
                            put(b, dy == -1 && d > R - 0.5 ? t.trim() : t.pillar() == Material.IRON_BARS ? t.trim() : t.pillar());
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        // 2. Площадка с лучами, кольцами и каймой
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 0.5) {
                    continue;
                }
                Material m;
                boolean spoke = dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz);
                if (d > R - 1.0) {
                    m = t.trim();
                } else if (d <= 1.5) {
                    m = t.accent();
                } else if (d <= 3.0) {
                    m = t.floor();
                } else if (d <= 4.0) {
                    m = t.trim();
                } else if (spoke) {
                    m = t.accent();
                } else {
                    m = t.floor();
                }
                put(w.getBlockAt(cx + dx, cy, cz + dz), m);
            }
        }
        // ступенька по периметру (визуальный «пьедестал»)
        for (int dx = -(R + 1); dx <= R + 1; dx++) {
            for (int dz = -(R + 1); dz <= R + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 0.5 && d <= R + 1.4) {
                    put(w.getBlockAt(cx + dx, cy, cz + dz), t.pillar() == Material.IRON_BARS ? t.floor() : t.pillar());
                }
            }
        }

        // 3. Возвышение под центральным блоком
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= 2.3) {
                    put(w.getBlockAt(cx + dx, cy + 1, cz + dz), d <= 1.5 ? t.accent() : t.trim());
                }
            }
        }
        // 4 угловых светильника возвышения
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                put(w.getBlockAt(cx + sx * 2, cy + 2, cz + sz * 2), t.glow());
            }
        }
        Block center = w.getBlockAt(cx, cy + 2, cz);
        put(center, t.center());

        // 4. Колонны
        int pr = R - 2;
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4.0;
            int px = cx + (int) Math.round(Math.cos(ang) * pr);
            int pz = cz + (int) Math.round(Math.sin(ang) * pr);
            boolean tall = k % 2 == 0;
            int h = tall ? tallH : lowH;
            put(w.getBlockAt(px, cy + 1, pz), t.trim());
            for (int dy = 2; dy <= h - 2; dy++) {
                put(w.getBlockAt(px, cy + dy, pz), t.pillar());
            }
            put(w.getBlockAt(px, cy + h - 1, pz), t.accent());
            put(w.getBlockAt(px, cy + h, pz), t.glow());
            if (tall) {
                // у высоких колонн — подпорки-«плечи» и тонкий шпиль
                for (int sx = -1; sx <= 1; sx++) {
                    for (int sz = -1; sz <= 1; sz++) {
                        if ((sx == 0) != (sz == 0)) {
                            put(w.getBlockAt(px + sx, cy + 1, pz + sz), t.trim());
                        }
                    }
                }
                put(w.getBlockAt(px, cy + h + 1, pz), t.trim());
                put(w.getBlockAt(px, cy + h + 2, pz), t.accent());
            }
        }

        // 5. Парящее кольцо над центром
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= 3.2 && d <= 4.1) {
                    boolean mark = (Math.abs(dx) + Math.abs(dz)) % 3 == 0;
                    put(w.getBlockAt(cx + dx, cy + ringY, cz + dz), mark ? t.glow() : t.accent());
                }
            }
        }

        // 6. Тематическое убранство
        decorate(plugin, w, cx, cy, cz, R, tallH, lowH, t, theme);

        // 7. Эффект завершения стройки
        Location c = center.getLocation().add(0.5, 1.2, 0.5);
        plugin.fx().particle(c, t.particle(), 120, 2.5, 2.0, 2.5, 0.2);
        plugin.fx().sound(c, "block.beacon.activate", 1.5f, 0.7f);
        return center;
    }

    // ------------------------------------------------------------ тематическое убранство

    /** Точки на окружности радиуса r с шагом 45°, сдвинутые на offDeg (между колоннами при 22.5). */
    private static int[][] ring(int cx, int cz, double r, int n, double offDeg) {
        int[][] pts = new int[n][2];
        for (int k = 0; k < n; k++) {
            double a = Math.toRadians(offDeg + k * 360.0 / n);
            pts[k][0] = cx + (int) Math.round(Math.cos(a) * r);
            pts[k][1] = cz + (int) Math.round(Math.sin(a) * r);
        }
        return pts;
    }

    private static void decorate(LegendaryPlugin plugin, World w, int cx, int cy, int cz, int R, int tallH, int lowH,
                                 AltarManager.Theme t, String type) {
        int y = cy + 1;
        int[][] between = ring(cx, cz, R - 2, 8, 22.5);   // между колоннами
        int[][] outer = ring(cx, cz, R - 0.5, 16, 0);     // по краю площадки
        int[][] inner = ring(cx, cz, 3.5, 8, 22.5);       // вокруг возвышения
        java.util.Random rnd = new java.util.Random(cx * 73856093L ^ cz * 19349663L);
        switch (type) {
            case "life_stealer" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:soul_campfire[lit=true]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:soul_lantern");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:warped_fungus");
                }
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4.0;
                    int px = cx + (int) Math.round(Math.cos(a) * (R - 2));
                    int pz = cz + (int) Math.round(Math.sin(a) * (R - 2));
                    int h = k % 2 == 0 ? tallH : lowH;
                    for (int d = 1; d <= 3; d++) {
                        data(w, px + 1, cy + h - d, pz, "minecraft:twisting_vines");
                    }
                }
            }
            case "light_blade" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:lantern");
                    data(w, p[0], y + 1, p[1], "minecraft:end_rod[facing=up]");
                    data(w, p[0], y + 2, p[1], "minecraft:end_rod[facing=up]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:white_candle[candles=4,lit=true]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:quartz_slab");
                }
            }
            case "ender_blade" -> {
                for (int[] p : between) {
                    int h = 2 + rnd.nextInt(4);
                    for (int d = 0; d < h; d++) {
                        data(w, p[0], y + d, p[1], d == h - 1 ? "minecraft:crying_obsidian" : "minecraft:obsidian");
                    }
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:end_rod[facing=up]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:purple_candle[candles=3,lit=true]");
                }
                data(w, cx + R - 4, y, cz, "minecraft:chorus_flower[age=5]");
                data(w, cx - R + 4, y, cz, "minecraft:chorus_flower[age=5]");
            }
            case "ban_hammer" -> {
                for (int k = 0; k < 4; k++) {
                    double a = Math.PI / 4.0 + k * Math.PI / 2.0;
                    int ax = cx + (int) Math.round(Math.cos(a) * (R - 4));
                    int az = cz + (int) Math.round(Math.sin(a) * (R - 4));
                    data(w, ax, y, az, "minecraft:anvil[facing=" + (k % 2 == 0 ? "north" : "east") + "]");
                }
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:iron_bars");
                    data(w, p[0], y + 1, p[1], "minecraft:iron_bars");
                    data(w, p[0], y + 2, p[1], "minecraft:soul_lantern");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:chain[axis=y]");
                }
            }
            case "thunderer" -> {
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4.0;
                    int px = cx + (int) Math.round(Math.cos(a) * (R - 2));
                    int pz = cz + (int) Math.round(Math.sin(a) * (R - 2));
                    int h = (k % 2 == 0 ? tallH : lowH) + (k % 2 == 0 ? 3 : 1);
                    for (int d = 1; d <= 3; d++) {
                        data(w, px, cy + h + d, pz, "minecraft:lightning_rod[facing=up]");
                    }
                }
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:waxed_copper_bulb[lit=true]");
                    data(w, p[0], y + 1, p[1], "minecraft:waxed_copper_grate");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:lightning_rod[facing=up]");
                }
            }
            case "tecton" -> {
                for (int dx = -R; dx <= R; dx++) {
                    for (int dz = -R; dz <= R; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d > 3.5 && d < R - 1 && rnd.nextInt(5) == 0) {
                            data(w, cx + dx, y, cz + dz, "minecraft:sculk_vein[down=true]");
                        }
                    }
                }
                for (int i = 0; i < between.length; i++) {
                    int[] p = between[i];
                    data(w, p[0], y, p[1], i % 2 == 0 ? "minecraft:sculk_shrieker[can_summon=false]" : "minecraft:sculk_catalyst");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:sculk_sensor");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:soul_lantern");
                }
            }
            case "inferno" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:lava_cauldron");
                    data(w, p[0], y + 1, p[1], "minecraft:nether_brick_fence");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:campfire[lit=true]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y - 1, p[1], "minecraft:magma_block");
                    data(w, p[0], y, p[1], "minecraft:fire");
                }
            }
            case "frost_axe" -> {
                for (int[] p : between) {
                    int h = 3 + rnd.nextInt(5);
                    for (int d = 0; d < h; d++) {
                        data(w, p[0], y + d, p[1], d >= h - 2 ? "minecraft:blue_ice" : "minecraft:packed_ice");
                    }
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:snow[layers=3]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:sea_lantern");
                }
            }
            case "crusher" -> {
                for (int i = 0; i < between.length; i++) {
                    int[] p = between[i];
                    data(w, p[0], y, p[1], i % 2 == 0 ? "minecraft:anvil[facing=north]" : "minecraft:grindstone[face=floor,facing=north]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:iron_bars");
                    data(w, p[0], y + 1, p[1], "minecraft:chain[axis=y]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:stonecutter[facing=north]");
                }
            }
            case "blood_oath" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:red_candle[candles=4,lit=true]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:crimson_fungus");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:nether_wart[age=3]");
                }
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4.0;
                    int px = cx + (int) Math.round(Math.cos(a) * (R - 2));
                    int pz = cz + (int) Math.round(Math.sin(a) * (R - 2));
                    int h = k % 2 == 0 ? tallH : lowH;
                    for (int d = 1; d <= 4; d++) {
                        data(w, px + 1, cy + h - d, pz, "minecraft:weeping_vines");
                    }
                }
            }
            case "phantom" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:bone_block[axis=y]");
                    data(w, p[0], y + 1, p[1], "minecraft:bone_block[axis=y]");
                    data(w, p[0], y + 2, p[1], "minecraft:skeleton_skull[rotation=" + rnd.nextInt(16) + "]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:white_candle[candles=2,lit=true]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:cobweb");
                }
            }
            case "copper_helmet" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:copper_bulb[lit=true]");
                    data(w, p[0], y + 1, p[1], "minecraft:amethyst_cluster[facing=up]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:end_rod[facing=up]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:lightning_rod[facing=up]");
                }
            }
            case "copper_chestplate" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:waxed_copper_bulb[lit=true]");
                    data(w, p[0], y + 1, p[1], "minecraft:lightning_rod[facing=up]");
                    data(w, p[0], y + 2, p[1], "minecraft:lightning_rod[facing=up]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:lightning_rod[facing=up]");
                }
            }
            case "copper_leggings" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:copper_grate");
                    data(w, p[0], y + 1, p[1], "minecraft:chain[axis=y]");
                    data(w, p[0], y + 2, p[1], "minecraft:lantern[hanging=true]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:sea_pickle[pickles=4,waterlogged=false]");
                }
            }
            case "copper_boots" -> {
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:cut_copper_slab");
                    data(w, p[0], y + 1, p[1], "minecraft:copper_bulb[lit=true]");
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:lightning_rod[facing=up]");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:gold_block");
                }
            }
            case "prisoner" -> {
                // клетка из прутьев вокруг возвышения с проёмами по сторонам света
                for (int[] p : ring(cx, cz, 4.0, 24, 7.5)) {
                    for (int d = 0; d < 4; d++) {
                        data(w, p[0], y + d, p[1], "minecraft:iron_bars");
                    }
                }
                for (int[] p : outer) {
                    data(w, p[0], y, p[1], "minecraft:chain[axis=y]");
                }
                for (int[] p : between) {
                    data(w, p[0], y, p[1], "minecraft:soul_lantern");
                }
                for (int[] p : inner) {
                    data(w, p[0], y, p[1], "minecraft:polished_blackstone_button[face=floor,facing=north]");
                }
            }
            default -> {
            }
        }
    }
}
