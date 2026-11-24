package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Точка входа: LegendaryWeapons (Spigot 1.21.11). */
public final class LegendaryPlugin extends JavaPlugin {

    private static LegendaryPlugin instance;

    public static LegendaryPlugin inst() {
        return instance;
    }

    // ------------------------------------------------------------ общее состояние (используется оружием)

    public NamespacedKey keyId;
    public NamespacedKey keyInstance;
    /** &gt; 0, пока идёт урон от способности (такие удары не засчитываются как обычные). */
    public int abilityDepth = 0;
    /** true во время пробного события урона для проверки приватов. */
    public boolean fakeDamage = false;
    /** UUID стоек-«двойников» (Фантом), чтобы они не считались целями. */
    public final Set<UUID> decoys = new HashSet<>();

    /** Негативные эффекты, наложенные способностями легендарок: игрок -> эффект -> до какого времени. */
    private final java.util.Map<UUID, java.util.Map<String, Long>> legendaryFx = new java.util.HashMap<>();

    private Database db;
    private Fx fx;
    private Buffs buffs;
    private Cooldowns cooldowns;
    private Charges charges;
    private Progress progress;
    private Hearts hearts;
    private ItemRegistry items;
    private AltarManager altars;
    private RitualManager rituals;
    private Suppression suppression;
    private ArmorSet armor;
    private InstanceGuard guard;
    private FoodBucket food;
    private CooldownBars bars;
    private ProtectionListener protection;
    private AltarSpawner spawner;
    private AdminMenu adminMenu;
    private AdminGuard adminGuard;
    private PouchManager pouches;
    private StatsBoard stats;
    private DungeonManager dungeons;
    private BarrierManager barriers;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        keyId = new NamespacedKey(this, "item_id");
        keyInstance = new NamespacedKey(this, "instance_id");

        db = new Database(this);
        try {
            db.open();
        } catch (Exception e) {
            getLogger().severe("Не удалось открыть базу данных SQLite: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        fx = new Fx(this);
        buffs = new Buffs();
        cooldowns = new Cooldowns(this);
        cooldowns.load();
        charges = new Charges(this);
        progress = new Progress(this);
        hearts = new Hearts(this);
        items = new ItemRegistry(this);
        altars = new AltarManager(this);
        altars.load();
        suppression = new Suppression(this);
        rituals = new RitualManager(this);
        rituals.load();
        armor = new ArmorSet(this);
        guard = new InstanceGuard(this);
        food = new FoodBucket(this);
        bars = new CooldownBars(this);
        protection = new ProtectionListener(this);
        spawner = new AltarSpawner(this);
        adminMenu = new AdminMenu(this);
        stats = new StatsBoard(this);
        dungeons = new DungeonManager(this);
        dungeons.load();
        barriers = new BarrierManager(this);

        Bukkit.getPluginManager().registerEvents(new AbilityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AltarListener(this), this);
        Bukkit.getPluginManager().registerEvents(armor, this);
        Bukkit.getPluginManager().registerEvents(food, this);
        Bukkit.getPluginManager().registerEvents(protection, this);
        Bukkit.getPluginManager().registerEvents(adminMenu, this);
        Bukkit.getPluginManager().registerEvents(new PackListener(this), this);
        Bukkit.getPluginManager().registerEvents(stats, this);
        Bukkit.getPluginManager().registerEvents(dungeons, this);
        Bukkit.getPluginManager().registerEvents(barriers, this);
        adminGuard = new AdminGuard(this);
        Bukkit.getPluginManager().registerEvents(adminGuard, this);
        adminGuard.start();
        pouches = new PouchManager(this);
        Bukkit.getPluginManager().registerEvents(pouches, this);
        pouches.start();
        pouches.registerRecipe();
        stats.start();
        dungeons.start();
        armor.start();
        guard.start();
        bars.start();
        food.registerRecipe();
        protection.applyRecipeBlock();
        protection.start();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            buffs.purge();
            purgeEffects();
        }, 200L, 200L);

        LegendaryCommand cmd = new LegendaryCommand(this);
        PluginCommand pc = getCommand("legendary");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        PluginCommand sc = getCommand("stats");
        if (sc != null) {
            sc.setExecutor((sender, command, label, args) -> {
                if (sender instanceof org.bukkit.entity.Player sp) {
                    boolean on = stats.toggle(sp);
                    sp.sendMessage(Util.color(getConfig().getString("messages.prefix", "")
                            + (on ? "&aТабло статистики включено." : "&7Табло статистики выключено. Включить: &f/stats")));
                } else {
                    sender.sendMessage("Только для игроков.");
                }
                return true;
            });
        }

        // применяем сердца уже подключённым игрокам (например, после /reload)
        for (var p : Bukkit.getOnlinePlayers()) {
            hearts.apply(p);
        }
        getLogger().info("LegendaryWeapons включён: оружие " + items.weapons().size()
                + ", алтарей " + altars.all().size() + ".");
    }

    @Override
    public void onDisable() {
        if (altars != null) {
            altars.closeMenus();
        }
        if (suppression != null) {
            suppression.clear();
        }
        if (pouches != null) {
            pouches.closeAll();
            pouches.unregisterRecipe();
        }
        if (stats != null) {
            stats.shutdown();
        }
        if (bars != null) {
            bars.shutdown();
        }
        if (rituals != null) {
            rituals.removeAllHolos();
        }
        if (food != null) {
            food.unregisterRecipe();
        }
        // стойки двойников Фантома не должны остаться в мире
        for (var w : Bukkit.getWorlds()) {
            for (var e : w.getEntities()) {
                if (decoys.contains(e.getUniqueId())) {
                    e.remove();
                }
            }
        }
        decoys.clear();
        Bukkit.getScheduler().cancelTasks(this);
        if (db != null) {
            db.close();
        }
        instance = null;
    }

    /** Перечитать config.yml и рецепты. */
    public void reloadAll() {
        reloadConfig();
        items.reloadRecipes();
        food.registerRecipe();
        pouches.registerRecipe();
        protection.applyRecipeBlock();
    }

    public PouchManager pouches() {
        return pouches;
    }

    // ------------------------------------------------------------ доступ к менеджерам

    public Database db() {
        return db;
    }

    public Fx fx() {
        return fx;
    }

    public Buffs buffs() {
        return buffs;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    public Charges charges() {
        return charges;
    }

    public Progress progress() {
        return progress;
    }

    public Hearts hearts() {
        return hearts;
    }

    public ItemRegistry items() {
        return items;
    }

    public AltarManager altars() {
        return altars;
    }

    public RitualManager rituals() {
        return rituals;
    }

    public Suppression suppression() {
        return suppression;
    }

    public ArmorSet armor() {
        return armor;
    }

    public InstanceGuard guard() {
        return guard;
    }

    public AltarSpawner spawner() {
        return spawner;
    }

    public AdminMenu adminMenu() {
        return adminMenu;
    }

    public StatsBoard stats() {
        return stats;
    }

    public BarrierManager barriers() {
        return barriers;
    }

    public DungeonManager dungeons() {
        return dungeons;
    }

    public FoodBucket food() {
        return food;
    }

    // ------------------------------------------------------------ эффекты легендарок

    public void markLegendaryEffect(UUID player, String key, int ticks) {
        legendaryFx.computeIfAbsent(player, k -> new java.util.HashMap<>())
                .put(key, System.currentTimeMillis() + ticks * 50L + 750L);
    }

    public boolean isLegendaryEffect(UUID player, String key) {
        java.util.Map<String, Long> m = legendaryFx.get(player);
        Long until = m == null ? null : m.get(key);
        return until != null && until > System.currentTimeMillis();
    }

    private void purgeEffects() {
        long now = System.currentTimeMillis();
        legendaryFx.values().forEach(m -> m.values().removeIf(t -> t <= now));
        legendaryFx.values().removeIf(java.util.Map::isEmpty);
    }

    // ------------------------------------------------------------ сообщения

    /** Текст сообщения из config.yml с подстановкой пар {ключ}=значение, без префикса. */
    public String text(String key, String... kv) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return Util.color(s);
    }

    /** Отправить сообщение с префиксом. */
    public void msg(CommandSender to, String key, String... kv) {
        to.sendMessage(Util.color(getConfig().getString("messages.prefix", "")) + text(key, kv));
    }

    /** Запись в журнал административных действий (БД + консоль). */
    public void adminLog(String actor, String action, String details) {
        db.update("INSERT INTO admin_log(ts, actor, action, details) VALUES(?,?,?,?)",
                System.currentTimeMillis(), actor, action, details);
        getLogger().info("[admin] " + actor + " " + action + ": " + details);
    }

    /** Запомнить экземпляр оружия (для защиты от дюпа). */
    public void registerInstance(String weaponId, String instanceId) {
        db.update("INSERT OR IGNORE INTO instances(id, weapon, created) VALUES(?,?,?)",
                instanceId, weaponId, System.currentTimeMillis());
    }
}
