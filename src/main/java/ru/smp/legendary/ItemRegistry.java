package ru.smp.legendary;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реестр легендарных предметов, компонентов и медной брони.
 * Подлинность определяется ТОЛЬКО по PersistentDataContainer, а не по имени/лору/модели.
 */
public final class ItemRegistry {

    public record Component(String id, String name, Material material, String lore) {
    }

    public record Recipe(Map<String, Integer> need, boolean valid) {
    }

    public static final List<String> ARMOR_IDS = List.of("copper_helmet", "copper_chestplate", "copper_leggings", "copper_boots");

    private static final Map<String, String> RU = new LinkedHashMap<>();

    static {
        String[][] names = {
                {"NETHERITE_SWORD", "Незеритовый меч"}, {"NETHERITE_AXE", "Незеритовый топор"}, {"NETHERITE_SPEAR", "Незеритовое копьё"},
                {"MACE", "Булава"}, {"TRIDENT", "Трезубец"}, {"ECHO_SHARD", "Осколок эха"}, {"GHAST_TEAR", "Слеза гаста"},
                {"NETHERITE_BLOCK", "Незеритовый блок"}, {"WITHER_ROSE", "Иссушённая роза"}, {"NETHER_STAR", "Звезда Незера"},
                {"AMETHYST_SHARD", "Осколок аметиста"}, {"SEA_LANTERN", "Морской фонарь"}, {"DRAGON_BREATH", "Дыхание дракона"},
                {"GOLD_BLOCK", "Золотой блок"}, {"ENDER_PEARL", "Жемчуг Края"}, {"ENDER_EYE", "Око Эндера"},
                {"CHORUS_FRUIT", "Плод хоруса"}, {"SHULKER_SHELL", "Панцирь шалкера"}, {"DRAGON_HEAD", "Голова дракона"},
                {"END_ROD", "Стержень Эндера"}, {"OBSIDIAN", "Обсидиан"}, {"OMINOUS_TRIAL_KEY", "Зловещий ключ"},
                {"CRYING_OBSIDIAN", "Плачущий обсидиан"}, {"TOTEM_OF_UNDYING", "Тотем бессмертия"}, {"DRAGON_EGG", "Яйцо дракона"},
                {"BREEZE_ROD", "Стержень вихря"}, {"HEART_OF_THE_SEA", "Сердце моря"}, {"COPPER_BLOCK", "Медный блок"},
                {"LIGHTNING_ROD", "Громоотвод"}, {"PRISMARINE_CRYSTALS", "Призмариновый кристалл"}, {"HEAVY_CORE", "Тяжёлое ядро"},
                {"ANCIENT_DEBRIS", "Древние обломки"}, {"MAGMA_BLOCK", "Магматический блок"}, {"NETHERITE_INGOT", "Незеритовый слиток"},
                {"BLAZE_ROD", "Огненный стержень"}, {"MAGMA_CREAM", "Сгусток магмы"}, {"BLUE_ICE", "Синий лёд"},
                {"PACKED_ICE", "Плотный лёд"}, {"SNOW_BLOCK", "Снежный блок"}, {"IRON_BLOCK", "Железный блок"},
                {"DIAMOND_BLOCK", "Алмазный блок"}, {"ANVIL", "Наковальня"}, {"SPIDER_EYE", "Паучий глаз"},
                {"PHANTOM_MEMBRANE", "Фантомная мембрана"}, {"NETHER_WART", "Незерский нарост"}, {"IRON_CHAIN", "Цепь"},
                {"CHAIN", "Цепь"}, {"SCULK_CATALYST", "Скалковый катализатор"},
                {"SCULK_SHRIEKER", "Скалковый крикун"}, {"SCULK_SENSOR", "Скалковый датчик"},
                {"GOLDEN_APPLE", "Золотое яблоко"}, {"ENCHANTED_GOLDEN_APPLE", "Зачарованное золотое яблоко"},
                {"GOLDEN_CARROT", "Золотая морковь"}, {"COOKED_PORKCHOP", "Жареная свинина"}, {"COOKED_BEEF", "Жареный стейк"},
                {"MILK_BUCKET", "Ведро молока"}, {"GOLD_INGOT", "Золотой слиток"},
                {"custom:mace_head", "Навершие булавы"}, {"custom:armor_plate", "Ивентовая пластина"}, {"FEATHER", "Перо"}, {"COPPER_HELMET", "Медный шлем"}, {"COPPER_CHESTPLATE", "Медный нагрудник"}, {"COPPER_LEGGINGS", "Медные поножи"}, {"COPPER_BOOTS", "Медные ботинки"}, {"COPPER_INGOT", "Медный слиток"}, {"custom:axe_head", "Голова топора"}, {"custom:dagger_base", "Основа кинжала"}
        };
        for (String[] n : names) {
            RU.put(n[0], n[1]);
        }
    }

    private final LegendaryPlugin plugin;
    private final Map<String, Weapon> weapons = new LinkedHashMap<>();
    /** Всё, что создаётся на алтарях: 12 видов оружия и 4 части брони. */
    private final Map<String, Weapon> ritualItems = new LinkedHashMap<>();
    private final Map<String, Component> components = new LinkedHashMap<>();
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();

    public ItemRegistry(LegendaryPlugin plugin) {
        this.plugin = plugin;
        register(new SwordWeapons.LifeStealer(plugin));
        register(new SwordWeapons.LightBlade(plugin));
        register(new SwordWeapons.EnderBlade(plugin));
        register(new MaceWeapons.BanHammer(plugin));
        register(new MaceWeapons.Thunderer(plugin));
        register(new MaceWeapons.Tecton(plugin));
        register(new AxeWeapons.Inferno(plugin));
        register(new AxeWeapons.FrostAxe(plugin));
        register(new AxeWeapons.Crusher(plugin));
        register(new DaggerWeapons.BloodOath(plugin));
        register(new DaggerWeapons.Phantom(plugin));
        register(new DaggerWeapons.Prisoner(plugin));

        ritualItems.putAll(weapons);
        for (String aid : ARMOR_IDS) {
            ritualItems.put(aid, new ArmorPiece(plugin, aid));
        }
        components.put("armor_plate", new Component("armor_plate", "&6Ивентовая пластина", Material.COPPER_INGOT, "Компонент для медной брони"));
        components.put("mace_head", new Component("mace_head", "&6Навершие булавы", Material.HEAVY_CORE, "Компонент для Молота бана"));
        components.put("axe_head", new Component("axe_head", "&6Голова топора", Material.IRON_AXE, "Компонент для топоров"));
        components.put("dagger_base", new Component("dagger_base", "&7Основа кинжала", Material.IRON_SWORD, "Компонент для кинжалов"));
        reloadRecipes();
    }

    private void register(Weapon w) {
        weapons.put(w.id, w);
    }

    // ------------------------------------------------------------ lookups

    public Map<String, Weapon> weapons() {
        return weapons;
    }

    public Map<String, Weapon> ritualItems() {
        return ritualItems;
    }

    public Weapon ritualItem(String id) {
        return ritualItems.get(id);
    }

    public Weapon weapon(String id) {
        return weapons.get(id);
    }

    public Map<String, Component> components() {
        return components;
    }

    public Recipe recipe(String weaponId) {
        return recipes.get(weaponId);
    }

    /** Сырой тип предмета из PDC: w:<id>, c:<id>, a:<id> или null. */
    public String typeId(ItemStack s) {
        if (s == null || s.getType() == Material.AIR || !s.hasItemMeta()) {
            return null;
        }
        return s.getItemMeta().getPersistentDataContainer().get(plugin.keyId, PersistentDataType.STRING);
    }

    public String instanceId(ItemStack s) {
        if (s == null || s.getType() == Material.AIR || !s.hasItemMeta()) {
            return null;
        }
        return s.getItemMeta().getPersistentDataContainer().get(plugin.keyInstance, PersistentDataType.STRING);
    }

    public String weaponId(ItemStack s) {
        String t = typeId(s);
        if (t == null || !t.startsWith("w:")) {
            return null;
        }
        String id = t.substring(2);
        return weapons.containsKey(id) ? id : null;
    }

    public Weapon weaponOf(ItemStack s) {
        String id = weaponId(s);
        return id == null ? null : weapons.get(id);
    }

    public String armorId(ItemStack s) {
        String t = typeId(s);
        if (t == null || !t.startsWith("a:")) {
            return null;
        }
        String id = t.substring(2);
        return ARMOR_IDS.contains(id) ? id : null;
    }

    /** Ключ ингредиента для алтаря: MATERIAL, custom:<id>, либо null, если предмет нельзя использовать. */
    public String ingredientKey(ItemStack s) {
        if (s == null || s.getType() == Material.AIR) {
            return null;
        }
        String t = typeId(s);
        if (t != null) {
            if (t.startsWith("c:") && components.containsKey(t.substring(2))) {
                return "custom:" + t.substring(2);
            }
            return null; // легендарное оружие и броня как расходник не принимаются
        }
        return s.getType().name();
    }

    public static String displayName(String key) {
        String ru = RU.get(key);
        if (ru != null) {
            return ru;
        }
        return key.toLowerCase().replace('_', ' ');
    }

    // ------------------------------------------------------------ recipes

    public void reloadRecipes() {
        recipes.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("recipes");
        for (String id : ritualItems.keySet()) {
            Map<String, Integer> need = new LinkedHashMap<>();
            boolean valid = root != null && root.isConfigurationSection(id);
            if (valid) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                for (String key : sec.getKeys(false)) {
                    int amount = sec.getInt(key, 0);
                    if (amount <= 0) {
                        valid = false;
                        plugin.getLogger().warning("Recipe " + id + ": bad amount for " + key);
                        continue;
                    }
                    if (key.startsWith("custom:")) {
                        if (!components.containsKey(key.substring(7))) {
                            valid = false;
                            plugin.getLogger().warning("Recipe " + id + ": unknown component " + key);
                        }
                    } else if (Material.matchMaterial(key) == null) {
                        valid = false;
                        plugin.getLogger().warning("Recipe " + id + ": unknown material " + key + " (check your Minecraft version)");
                    }
                    need.put(key, amount);
                }
                if (need.isEmpty()) {
                    valid = false;
                }
            } else {
                plugin.getLogger().warning("Recipe for " + id + " is missing in config.yml");
            }
            recipes.put(id, new Recipe(need, valid));
        }
    }

    // ------------------------------------------------------------ item factories

    private void tag(ItemMeta m, String typeId, String instance) {
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(plugin.keyId, PersistentDataType.STRING, typeId);
        if (instance != null) {
            pdc.set(plugin.keyInstance, PersistentDataType.STRING, instance);
        }
        try {
            CustomModelDataComponent c = m.getCustomModelDataComponent();
            c.setStrings(List.of(typeId.substring(typeId.indexOf(':') + 1)));
            m.setCustomModelDataComponent(c);
        } catch (Throwable ignored) {
            // старые API без компонента модели — предмет просто остаётся ванильным по виду
        }
    }

    // ------------------------------------------------------------ характеристики как у незерита

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble(path, def);
    }

    private void addAttr(ItemMeta m, Attribute attr, String keyName, double amount, EquipmentSlotGroup slot) {
        try {
            m.addAttributeModifier(attr, new AttributeModifier(new NamespacedKey(plugin, keyName), amount,
                    AttributeModifier.Operation.ADD_NUMBER, slot));
        } catch (Throwable ignored) {
            // атрибут недоступен в этой версии API: предмет остаётся с ванильными характеристиками
        }
    }

    /**
     * Урон и скорость атаки как у незеритового оружия (меч 8 / 1.6, топор 10 / 1.0).
     * Ванильные модификаторы предмета при добавлении своих отбрасываются, поэтому ставим оба.
     * Базовый урон игрока 1.0, базовая скорость атаки 4.0.
     */
    private void weaponStats(ItemMeta m, Material mat) {
        if (!plugin.getConfig().getBoolean("stats.enabled", true)) {
            return;
        }
        String n = mat.name();
        String kind = "sword";
        double dmg = 8.0;
        double speed = 1.6;
        if (n.endsWith("_AXE")) {
            kind = "axe";
            dmg = 10.0;
            speed = 1.0;
        } else if (n.equals("MACE")) {
            kind = "mace";
        } else if (n.endsWith("_SPEAR") || n.equals("TRIDENT")) {
            kind = "spear";
        }
        dmg = cfg("stats.weapons." + kind + ".damage", dmg);
        speed = cfg("stats.weapons." + kind + ".speed", speed);
        addAttr(m, Attribute.ATTACK_DAMAGE, "legendary_damage", dmg - 1.0, EquipmentSlotGroup.MAINHAND);
        addAttr(m, Attribute.ATTACK_SPEED, "legendary_speed", speed - 4.0, EquipmentSlotGroup.MAINHAND);
    }

    /** Защита, твёрдость, стойкость к отбрасыванию и прочность как у незеритовой брони. */
    private void armorStats(ItemMeta m, String id) {
        if (!plugin.getConfig().getBoolean("stats.enabled", true)) {
            return;
        }
        String part;
        EquipmentSlotGroup slot;
        double defense;
        int durability;
        switch (id) {
            case "copper_helmet" -> {
                part = "helmet";
                slot = EquipmentSlotGroup.HEAD;
                defense = 3;
                durability = 407;
            }
            case "copper_chestplate" -> {
                part = "chestplate";
                slot = EquipmentSlotGroup.CHEST;
                defense = 8;
                durability = 592;
            }
            case "copper_leggings" -> {
                part = "leggings";
                slot = EquipmentSlotGroup.LEGS;
                defense = 6;
                durability = 555;
            }
            default -> {
                part = "boots";
                slot = EquipmentSlotGroup.FEET;
                defense = 3;
                durability = 481;
            }
        }
        defense = cfg("stats.armor." + part + ".defense", defense);
        addAttr(m, Attribute.ARMOR, "legendary_armor_" + id, defense, slot);
        addAttr(m, Attribute.ARMOR_TOUGHNESS, "legendary_toughness_" + id, cfg("stats.armor.toughness", 3.0), slot);
        addAttr(m, Attribute.KNOCKBACK_RESISTANCE, "legendary_knockback_" + id, cfg("stats.armor.knockback_resistance", 0.1), slot);
        try {
            if (m instanceof Damageable dm) {
                dm.setMaxDamage(plugin.getConfig().getInt("stats.armor." + part + ".durability", durability));
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Map<String, String[]> DESC = new LinkedHashMap<>();

    static {
        DESC.put("life_stealer", new String[]{"Жатва душ: удар по всем врагам рядом (2 сердца каждому), часть урона лечит вас.", "Покров душ: на 8 с поглощение и ускорение, души срываются с покрова и бьют врагов рядом на 2 сердца."});
        DESC.put("light_blade", new String[]{"Световой импульс: вспышка слепит, отбрасывает врагов вокруг и жжёт на 2,5 сердца.", "Божественная защита: на 8 с урон по вам -40%; при включении и в конце всплеск света бьёт врагов на 2 сердца."});
        DESC.put("ender_blade", new String[]{"Разлом пространства: над врагами в радиусе 20 открываются порталы, осколки Края бьют по 2 сердца каждый.", "Обмен измерениями: меняет вас местами с целью, пустота ранит её на 2,5 сердца, цель замедляется."});
        DESC.put("ban_hammer", new String[]{"Приговор: метка и мгновенный удар на 3 сердца; удары по цели сильнее.", "Последнее предупреждение: зона запрета радиусом 8 на 6 с, ударная волна бьёт врагов на 2 сердца."});
        DESC.put("thunderer", new String[]{"Небесный удар: молния в точку прицела, 4,5 сердца всем в области.", "Грозовой шаг: рывок вперёд, враги на пути получают 2,5 сердца и замедление."});
        DESC.put("tecton", new String[]{"Звуковой удар: волна по линии взгляда, 5 сердец всем на пути; сквозь стены не проходит.", "Зов Тьмы: враги вокруг получают 2,5 сердца, слепнут и подсвечиваются сквозь стены."});
        DESC.put("inferno", new String[]{"Пекло: огненное кольцо на 3 с, 2,5 сердца врагам внутри и поджог; вы огнеупорны.", "Пепельное клеймо: тратит 5 зарядов жара, взрыв пепла на 3 сердца вокруг."});
        DESC.put("frost_axe", new String[]{"Ледяная ловушка: шипы льда бьют на 2 сердца, зона замедляет врагов 6 с.", "Ледяная оболочка: на 8 с урон по вам -25%, всплеск холода на 2 сердца, бьющие вас замедляются."});
        DESC.put("crusher", new String[]{"Пробой: тяжёлый замах бьёт ближних на 2,5 сердца, а следующий удар на 6 с пробивает защиту (+30%).", "Дробление: удар по конусу на 2,5 сердца, отбрасывание и износ брони врагов."});
        DESC.put("blood_oath", new String[]{"Жажда крови: надрез бьёт ближних на 2 сердца и лечит вас; 8 с часть урона лечит.", "Полёт нетопырей: стая летит с вами и кусает каждого встречного на 2 сердца; ~3,5 с неуязвимости."});
        DESC.put("phantom", new String[]{"Призрачный рывок: бросок вперёд, враги на пути получают 3 сердца.", "Двойник: вопль при создании бьёт на 2 сердца, ускорение; двойник рассыпается взрывом душ (2 сердца)."});
        DESC.put("prisoner", new String[]{"Цепи заточения: цепи хлещут врагов рядом на 2 сердца, следующий удар на 8 с обездвиживает и метит врага.", "Казнь стража: телепорт к помеченной цели и удар на 4 сердца (до 16 блоков)."});
    }

    public static String desc(String id, int slot) {
        String[] d = DESC.get(id);
        return d == null ? "" : d[slot];
    }

    private static List<String> wrapPlain(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(word);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Своя иконка предмета: компонент item_model указывает на модель из ресурспака (если он включён в конфиге). */
    public void applyModel(ItemMeta m, String id) {
        if (!plugin.getConfig().getBoolean("resource_pack.enabled", false)) {
            return;
        }
        try {
            m.setItemModel(new NamespacedKey("legendaryweapons", id));
        } catch (Throwable ignored) {
        }
    }

    public ItemStack createWeapon(String id) {
        return createWeapon(id, UUID.randomUUID().toString());
    }

    public ItemStack createWeapon(String id, String instance) {
        if (ARMOR_IDS.contains(id)) {
            return createArmor(id, instance);
        }
        Weapon w = weapons.get(id);
        if (w == null) {
            return null;
        }
        ItemStack it = new ItemStack(w.material);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color(w.name));
        List<String> lore = new ArrayList<>();
        lore.add(Util.color("&8Легендарное оружие"));
        lore.add("");
        lore.add(Util.color("&7Способность 1 &8(Shift+ПКМ)&7: &f" + w.primaryName()));
        for (String part : wrapPlain(desc(id, 0), 40)) {
            lore.add(Util.color("&8  " + part));
        }
        lore.add(Util.color("&7Способность 2 &8(F)&7: &f" + w.secondaryName()));
        for (String part : wrapPlain(desc(id, 1), 40)) {
            lore.add(Util.color("&8  " + part));
        }
        lore.add(Util.color("&8Открывается после " + plugin.progress().need() + " убийств разных игроков"));
        lore.add("");
        lore.add(Util.color("&7" + w.blurb()));
        m.setLore(lore);
        m.setUnbreakable(true);
        m.setEnchantmentGlintOverride(true);
        weaponStats(m, w.material);
        applyModel(m, id);
        tag(m, "w:" + id, instance);
        it.setItemMeta(m);
        return it;
    }

    public ItemStack createComponent(String id, int amount) {
        Component c = components.get(id);
        if (c == null) {
            return null;
        }
        ItemStack it = new ItemStack(c.material(), Math.max(1, Math.min(amount, c.material().getMaxStackSize())));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color(c.name()));
        m.setLore(List.of(Util.color("&8Компонент легендарного оружия"), Util.color("&7" + c.lore())));
        m.setEnchantmentGlintOverride(true);
        applyModel(m, "c_" + id);
        tag(m, "c:" + id, null);
        it.setItemMeta(m);
        return it;
    }

    private String recipePage(String id, Recipe r) {
        Weapon w = weapons.get(id);
        String title = w != null ? w.plainName() : displayName(id);
        StringBuilder sb = new StringBuilder("§l" + title + "§r\n");
        for (Map.Entry<String, Integer> n : r.need().entrySet()) {
            String key = n.getKey();
            String label;
            if (key.startsWith("custom:")) {
                Component cc = components.get(key.substring(7));
                label = cc == null ? key : org.bukkit.ChatColor.stripColor(Util.color(cc.name()));
            } else {
                label = displayName(key);
            }
            sb.append("• ").append(label).append(" ×").append(n.getValue()).append('\n');
        }
        sb.append("\nРитуал у алтаря.");
        return sb.toString();
    }

    /** Полная книга рецептов: рецепт каждого легендарного предмета (для админов). */
    public ItemStack createRecipeBook() {
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta m = (org.bukkit.inventory.meta.BookMeta) it.getItemMeta();
        m.setTitle("Книга рецептов");
        m.setAuthor("Древний кузнец");
        m.setDisplayName(Util.color("&6Книга рецептов"));
        m.setLore(List.of(Util.color("&8Рецепты всех легендарных предметов")));
        m.setEnchantmentGlintOverride(true);
        List<String> pages = new ArrayList<>();
        for (Map.Entry<String, Recipe> e : recipes.entrySet()) {
            if (e.getValue() != null && e.getValue().valid()) {
                pages.add(recipePage(e.getKey(), e.getValue()));
            }
        }
        if (pages.isEmpty()) {
            pages.add("Рецепты не найдены.");
        }
        m.setPages(pages);
        tag(m, "f:recipe_book", null);
        it.setItemMeta(m);
        return it;
    }

    /** Книга-чертёж: перечисляет рецепты всех предметов, в которых используется компонент. */
    public ItemStack createBlueprint(String componentId) {
        Component c = components.get(componentId);
        if (c == null) {
            return null;
        }
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta m = (org.bukkit.inventory.meta.BookMeta) it.getItemMeta();
        m.setTitle("Чертёж");
        m.setAuthor("Древний кузнец");
        m.setDisplayName(Util.color("&6Чертёж: &f" + org.bukkit.ChatColor.stripColor(Util.color(c.name()))));
        m.setLore(List.of(Util.color("&8Рецепт оружия, использующего этот компонент"),
                Util.color("&7Прочти (ПКМ), чтобы узнать рецепт")));
        m.setEnchantmentGlintOverride(true);
        List<String> pages = new ArrayList<>();
        for (Map.Entry<String, Recipe> e : recipes.entrySet()) {
            Recipe r = e.getValue();
            if (r == null || !r.valid() || !r.need().containsKey("custom:" + componentId)) {
                continue;
            }
            pages.add(recipePage(e.getKey(), r));
        }
        if (pages.isEmpty()) {
            pages.add("Рецепт не найден.");
        }
        m.setPages(pages);
        tag(m, "f:blueprint_" + componentId, null);
        it.setItemMeta(m);
        return it;
    }

    public ItemStack createArmor(String id) {
        return createArmor(id, UUID.randomUUID().toString());
    }

    public ItemStack createArmor(String id, String instance) {
        String matName;
        String name;
        String desc;
        switch (id) {
            case "copper_helmet" -> {
                matName = "COPPER_HELMET";
                name = "&6Шлем «Искатель»";
                desc = "Периодически обнаруживает ближайших игроков.";
            }
            case "copper_chestplate" -> {
                matName = "COPPER_CHESTPLATE";
                name = "&6Нагрудник «Проводник»";
                desc = "Накапливает заряд при получении урона от игроков.";
            }
            case "copper_leggings" -> {
                matName = "COPPER_LEGGINGS";
                name = "&6Поножи «Гальваника»";
                desc = "Сокращает длительность замедления, слабости и слепоты.";
            }
            case "copper_boots" -> {
                matName = "COPPER_BOOTS";
                name = "&6Ботинки «Импульс»";
                desc = "Ускорение после приземления с большой высоты.";
            }
            default -> {
                return null;
            }
        }
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            mat = Material.matchMaterial(matName.replace("COPPER", "IRON"));
        }
        if (mat == null) {
            return null;
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color(name));
        m.setLore(List.of(Util.color("&8Медный комплект"), Util.color("&7" + desc),
                Util.color("&7Полный комплект: &fShift+F &7— «Гальванический разряд»")));
        m.setEnchantmentGlintOverride(true);
        armorStats(m, id);
        tag(m, "a:" + id, instance);
        it.setItemMeta(m);
        return it;
    }
}
