package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Регистрация алтарей, проверка конструкции, привязка к оружию. */
public final class AltarManager {

    /**
     * Тема алтаря: центральный блок (его и регистрируем), колонны, пол, кайма, цветной акцент и светящийся блок.
     * Для проверки алтаря важен только центральный блок: остальная постройка может быть повреждена.
     */
    public record Theme(Material center, Material pillar, Material floor, Material trim, Material accent,
                        Material glow, String particle, String sound) {
    }

    public static final class Altar {
        public final int id;
        public final String type;
        public final String world;
        public final int x, y, z;

        Altar(int id, String type, String world, int x, int y, int z) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        public String key() {
            return AltarManager.key(world, x, y, z);
        }
    }

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private final LegendaryPlugin plugin;
    private final Map<String, Altar> byKey = new LinkedHashMap<>();
    private final Map<Integer, Altar> byId = new LinkedHashMap<>();
    private final Map<String, Theme> themes = new HashMap<>();
    /** Кто сейчас держит открытое меню алтаря. */
    private final Map<Integer, UUID> viewers = new HashMap<>();

    public AltarManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
        themes.put("life_stealer", new Theme(Material.RESPAWN_ANCHOR, Material.DEEPSLATE_BRICKS, Material.SOUL_SOIL,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.WARPED_WART_BLOCK, Material.VERDANT_FROGLIGHT, "SOUL", "particle.soul_escape"));
        themes.put("light_blade", new Theme(Material.SEA_LANTERN, Material.QUARTZ_PILLAR, Material.SMOOTH_QUARTZ,
                Material.CHISELED_QUARTZ_BLOCK, Material.GOLD_BLOCK, Material.GLOWSTONE, "END_ROD", "block.beacon.ambient"));
        themes.put("ender_blade", new Theme(Material.END_PORTAL_FRAME, Material.PURPUR_PILLAR, Material.END_STONE_BRICKS,
                Material.OBSIDIAN, Material.AMETHYST_BLOCK, Material.PEARLESCENT_FROGLIGHT, "PORTAL", "block.end_portal.spawn"));
        themes.put("ban_hammer", new Theme(Material.CRYING_OBSIDIAN, Material.CHISELED_POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS,
                Material.BLACKSTONE, Material.RED_NETHER_BRICKS, Material.OCHRE_FROGLIGHT, "SOUL_FIRE_FLAME", "block.anvil.land"));
        themes.put("thunderer", new Theme(Material.WAXED_CHISELED_COPPER, Material.WAXED_CUT_COPPER, Material.WAXED_COPPER_BLOCK,
                Material.WAXED_OXIDIZED_CUT_COPPER, Material.WAXED_OXIDIZED_COPPER, Material.GLOWSTONE, "ELECTRIC_SPARK", "entity.lightning_bolt.impact"));
        themes.put("tecton", new Theme(Material.REINFORCED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.COBBLED_DEEPSLATE,
                Material.POLISHED_DEEPSLATE, Material.SCULK, Material.VERDANT_FROGLIGHT, "SCULK_SOUL", "block.sculk_catalyst.bloom"));
        themes.put("inferno", new Theme(Material.BLAST_FURNACE, Material.NETHER_BRICKS, Material.NETHERRACK,
                Material.CHISELED_NETHER_BRICKS, Material.MAGMA_BLOCK, Material.SHROOMLIGHT, "FLAME", "block.fire.ambient"));
        themes.put("frost_axe", new Theme(Material.BLUE_ICE, Material.PACKED_ICE, Material.SNOW_BLOCK,
                Material.QUARTZ_BRICKS, Material.LIGHT_BLUE_CONCRETE, Material.SEA_LANTERN, "SNOWFLAKE", "block.glass.step"));
        themes.put("crusher", new Theme(Material.SMITHING_TABLE, Material.CHISELED_STONE_BRICKS, Material.SMOOTH_STONE,
                Material.STONE_BRICKS, Material.IRON_BLOCK, Material.GLOWSTONE, "CRIT", "block.anvil.use"));
        themes.put("blood_oath", new Theme(Material.REDSTONE_BLOCK, Material.RED_NETHER_BRICKS, Material.NETHER_WART_BLOCK,
                Material.CRIMSON_PLANKS, Material.RED_CONCRETE, Material.SHROOMLIGHT, "CRIMSON_SPORE", "entity.witch.ambient"));
        themes.put("phantom", new Theme(Material.BONE_BLOCK, Material.POLISHED_DIORITE, Material.CALCITE,
                Material.DIORITE, Material.SOUL_SAND, Material.PEARLESCENT_FROGLIGHT, "SOUL", "block.sculk_catalyst.bloom"));
        themes.put("copper_helmet", new Theme(Material.COPPER_BULB, Material.EXPOSED_CUT_COPPER, Material.EXPOSED_COPPER,
                Material.CUT_COPPER, Material.AMETHYST_BLOCK, Material.SEA_LANTERN, "END_ROD", "block.beacon.activate"));
        themes.put("copper_chestplate", new Theme(Material.CHISELED_COPPER, Material.WEATHERED_CUT_COPPER, Material.WEATHERED_COPPER,
                Material.EXPOSED_CUT_COPPER, Material.OXIDIZED_COPPER, Material.GLOWSTONE, "ELECTRIC_SPARK", "entity.lightning_bolt.thunder"));
        themes.put("copper_leggings", new Theme(Material.COPPER_GRATE, Material.OXIDIZED_CUT_COPPER, Material.OXIDIZED_COPPER,
                Material.WEATHERED_CUT_COPPER, Material.PRISMARINE_BRICKS, Material.SEA_LANTERN, "ELECTRIC_SPARK", "block.beacon.power_select"));
        themes.put("copper_boots", new Theme(Material.COPPER_BLOCK, Material.CUT_COPPER, Material.SMOOTH_STONE,
                Material.COPPER_BLOCK, Material.GOLD_BLOCK, Material.GLOWSTONE, "FIREWORK", "entity.firework_rocket.launch"));
        themes.put("prisoner", new Theme(Material.POLISHED_BLACKSTONE, Material.IRON_BARS, Material.BLACKSTONE,
                Material.POLISHED_BLACKSTONE_BRICKS, Material.GILDED_BLACKSTONE, Material.VERDANT_FROGLIGHT, "SOUL_FIRE_FLAME", "block.chain.place"));
    }

    public Theme theme(String type) {
        return themes.get(type);
    }

    public void load() {
        byKey.clear();
        byId.clear();
        for (Altar a : plugin.db().query("SELECT id, type, world, x, y, z FROM altars",
                rs -> new Altar(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)))) {
            byKey.put(a.key(), a);
            byId.put(a.id, a);
        }
    }

    public Collection<Altar> all() {
        return byId.values();
    }

    public Altar byId(int id) {
        return byId.get(id);
    }

    public Altar at(Block b) {
        if (b == null) {
            return null;
        }
        return byKey.get(key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
    }

    public Altar create(String type, Block b) {
        if (at(b) != null) {
            return null;
        }
        long id = plugin.db().insert("INSERT INTO altars(type, world, x, y, z) VALUES(?,?,?,?,?)",
                type, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (id < 0) {
            return null;
        }
        Altar a = new Altar((int) id, type, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        byKey.put(a.key(), a);
        byId.put(a.id, a);
        return a;
    }

    public void remove(Altar a) {
        plugin.rituals().cancel(a, "altar removed");
        plugin.db().update("DELETE FROM altars WHERE id=?", a.id);
        byKey.remove(a.key());
        byId.remove(a.id);
        viewers.remove(a.id);
    }

    /** @return список проблем конструкции; пустой список — алтарь в порядке. */
    public List<String> validate(Altar a) {
        List<String> problems = new ArrayList<>();
        Theme t = themes.get(a.type);
        Location l = a.location();
        if (t == null) {
            problems.add("неизвестный тип алтаря " + a.type);
            return problems;
        }
        if (l == null) {
            problems.add("мир " + a.world + " не загружен");
            return problems;
        }
        // Проверяется только центральный блок: если сломан любой смежный блок постройки, алтарь всё равно работает.
        if (l.getBlock().getType() != t.center()) {
            problems.add("центральный блок должен быть " + t.center().name());
        }
        return problems;
    }

    // ------------------------------------------------------------ viewers

    public UUID viewer(int altarId) {
        return viewers.get(altarId);
    }

    public void setViewer(int altarId, UUID player) {
        if (player == null) {
            viewers.remove(altarId);
        } else {
            viewers.put(altarId, player);
        }
    }

    public void closeMenus() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof AltarMenu) {
                p.closeInventory();
            }
        }
    }
}
