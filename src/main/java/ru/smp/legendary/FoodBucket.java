package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Эпический предмет «Ведро еды». Крафтится на верстаке, БЕСКОНЕЧНОЕ: ведро не расходуется, между употреблениями
 * перезарядка 30 секунд. Каждое употребление: насыщение как у золотой моркови, одно золотое сердце и снятие
 * негативных эффектов (кроме тех, что наложены способностями легендарного оружия).
 *
 * Стеки в рецепте: ванильный рецепт не умеет требовать количество, поэтому он зарегистрирован как бесформенный
 * (по одному предмету каждого вида), а нужное количество проверяется и списывается в обработчиках событий:
 * в каждой клетке верстака должен лежать стек не меньше нужного.
 */
public final class FoodBucket implements Listener {

    public static final String ID = "food_bucket";
    private static final String CD_KEY = "food_bucket:use";

    private final LegendaryPlugin plugin;
    private final NamespacedKey recipeKey;

    public FoodBucket(LegendaryPlugin plugin) {
        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "food_bucket");
    }

    // ------------------------------------------------------------ конфиг

    /** Негативные эффекты, которые снимает ведро. */
    private static final String[] NEGATIVE = {"slowness", "weakness", "blindness", "nausea", "poison", "wither", "hunger",
            "mining_fatigue", "levitation", "darkness", "unluck", "bad_omen", "infested", "oozing", "weaving", "raid_omen",
            "trial_omen"};

    /** Требуемые количества: материал -> штук (один стек в одной клетке верстака). Берутся из food_bucket.recipe. */
    private Map<Material, Integer> need() {
        Map<Material, Integer> m = new LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("food_bucket.recipe");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material mat = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                if (mat == null) {
                    plugin.getLogger().warning("food_bucket.recipe: неизвестный материал " + key);
                    continue;
                }
                m.put(mat, Math.max(1, Math.min(mat.getMaxStackSize(), sec.getInt(key, 1))));
            }
        }
        if (m.isEmpty()) {
            m.put(Material.ENCHANTED_GOLDEN_APPLE, 2);
            m.put(Material.GOLDEN_APPLE, 16);
            m.put(Material.GOLDEN_CARROT, 32);
            m.put(Material.COOKED_PORKCHOP, 20);
            m.put(Material.MILK_BUCKET, 1);
            m.put(Material.COOKED_BEEF, 20);
        }
        return m;
    }

    // ------------------------------------------------------------ предмет и рецепт

    public boolean is(ItemStack s) {
        return ("f:" + ID).equals(plugin.items().typeId(s));
    }

    public ItemStack create() {
        ItemStack it = new ItemStack(Material.MILK_BUCKET);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(Util.color("&d&lВедро еды"));
        m.setLore(List.of(
                Util.color("&5Эпический предмет"),
                "",
                Util.color("&7Бесконечное: не расходуется. Зарядов: &f" + plugin.getConfig().getInt("food_bucket.charges", 2)),
                Util.color("&7Каждое употребление: насыщение как у золотой"),
                Util.color("&7моркови, одно золотое сердце и снятие"),
                Util.color("&7негативных эффектов (кроме эффектов легендарок)."),
                Util.color("&7Восстановление заряда: &f" + plugin.getConfig().getInt("food_bucket.cooldown_seconds", 30) + " с")));
        m.setEnchantmentGlintOverride(true);
        try {
            m.setRarity(ItemRarity.EPIC);
        } catch (Throwable ignored) {
        }
        m.getPersistentDataContainer().set(plugin.keyId, PersistentDataType.STRING, "f:" + ID);
        plugin.items().applyModel(m, ID);
        try {
            CustomModelDataComponent c = m.getCustomModelDataComponent();
            c.setStrings(List.of(ID));
            m.setCustomModelDataComponent(c);
        } catch (Throwable ignored) {
        }
        it.setItemMeta(m);
        return it;
    }

    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapelessRecipe r = new ShapelessRecipe(recipeKey, create());
        for (Material m : need().keySet()) {
            r.addIngredient(m);
        }
        Bukkit.addRecipe(r);
    }

    public void unregisterRecipe() {
        Bukkit.removeRecipe(recipeKey);
    }

    private boolean isOurs(Recipe r) {
        return r instanceof Keyed k && k.getKey().equals(recipeKey);
    }

    // ------------------------------------------------------------ крафт

    private boolean enough(ItemStack[] matrix) {
        for (Map.Entry<Material, Integer> e : need().entrySet()) {
            boolean ok = false;
            for (ItemStack s : matrix) {
                if (s != null && !is(s) && s.getType() == e.getKey() && s.getAmount() >= e.getValue()) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!isOurs(e.getRecipe())) {
            return;
        }
        if (!enough(e.getInventory().getMatrix())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!isOurs(e.getRecipe())) {
            return;
        }
        CraftingInventory inv = e.getInventory();
        if (e.isShiftClick() || !enough(inv.getMatrix())) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                Util.actionbar(p, "&cКрафт ведра: без Shift и со стеками нужного размера (" + describeNeed() + ")");
            }
            return;
        }
        // ванильный крафт спишет по одному предмету из каждой клетки, остальное списываем заранее
        ItemStack[] matrix = inv.getMatrix();
        Map<Material, Integer> need = need();
        for (int i = 0; i < matrix.length; i++) {
            ItemStack s = matrix[i];
            if (s == null) {
                continue;
            }
            Integer n = need.get(s.getType());
            if (n != null && n > 1) {
                s.setAmount(s.getAmount() - (n - 1));
                matrix[i] = s;
            }
        }
        inv.setMatrix(matrix);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent e) {
        if (e.getRecipe() instanceof Keyed k && k.getKey().equals(recipeKey)) {
            e.setCancelled(true); // автокрафтер не умеет проверять размер стеков
        }
    }

    private String describeNeed() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Material, Integer> e : need().entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ItemRegistry.displayName(e.getKey().name()));
            if (e.getValue() > 1) {
                sb.append(" x").append(e.getValue());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ употребление

    private int maxCharges() {
        return Math.max(1, plugin.getConfig().getInt("food_bucket.charges", 2));
    }

    /** Каждый заряд восстанавливается независимо (своя перезарядка). Ключ свободного заряда или null. */
    private String freeKey(Player p) {
        for (int i = 1; i <= maxCharges(); i++) {
            if (plugin.cooldowns().remaining(p.getUniqueId(), CD_KEY + ":" + i) <= 0) {
                return CD_KEY + ":" + i;
            }
        }
        return null;
    }

    private int chargesLeft(Player p) {
        int n = 0;
        for (int i = 1; i <= maxCharges(); i++) {
            if (plugin.cooldowns().remaining(p.getUniqueId(), CD_KEY + ":" + i) <= 0) {
                n++;
            }
        }
        return n;
    }

    /** Мс до ближайшего восстановления заряда (0, если есть свободный). */
    private long cooldownLeft(Player p) {
        if (freeKey(p) != null) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        for (int i = 1; i <= maxCharges(); i++) {
            min = Math.min(min, plugin.cooldowns().remaining(p.getUniqueId(), CD_KEY + ":" + i));
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUseStart(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        if (it == null || !is(it)) {
            return;
        }
        long rem = cooldownLeft(e.getPlayer());
        if (rem > 0) {
            e.setUseItemInHand(Event.Result.DENY);
            e.setCancelled(true);
            Util.actionbar(e.getPlayer(), plugin.text("food_cooldown", "time", String.format(Locale.ROOT, "%.1f", rem / 1000.0)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (!is(it)) {
            return;
        }
        e.setCancelled(true); // ванильное действие молока не нужно, ведро не расходуется
        final Player p = e.getPlayer();
        final UUID uid = p.getUniqueId();
        long rem = cooldownLeft(p);
        if (rem > 0) {
            Util.actionbar(p, plugin.text("food_cooldown", "time", String.format(Locale.ROOT, "%.1f", rem / 1000.0)));
            return;
        }
        // заряд тратим сразу, чтобы второе срабатывание в тот же тик не прошло
        String slotKey = freeKey(p);
        if (slotKey == null) {
            return;
        }
        plugin.cooldowns().set(uid, slotKey, plugin.getConfig().getInt("food_bucket.cooldown_seconds", 30));

        int food = p.getFoodLevel();
        p.setFoodLevel(Math.min(20, food + plugin.getConfig().getInt("food_bucket.nutrition", 6)));
        p.setSaturation((float) Math.min(p.getFoodLevel(),
                p.getSaturation() + plugin.getConfig().getDouble("food_bucket.saturation", 14.4)));
        double absorptionBefore = p.getAbsorptionAmount();
        Util.addEffect(p, "absorption", plugin.getConfig().getInt("food_bucket.absorption_seconds", 120) * 20, 0);
        p.setAbsorptionAmount(Math.max(absorptionBefore, plugin.getConfig().getDouble("food_bucket.absorption_hp", 2.0)));

        // снимаем негативные эффекты, кроме наложенных способностями легендарного оружия
        int removed = 0;
        int kept = 0;
        for (String key : NEGATIVE) {
            PotionEffectType t = Util.effect(key);
            if (t == null || !p.hasPotionEffect(t)) {
                continue;
            }
            if (plugin.isLegendaryEffect(uid, key)) {
                kept++;
            } else {
                p.removePotionEffect(t);
                removed++;
            }
        }
        plugin.fx().particle(p.getLocation().add(0, 1.2, 0), "HAPPY_VILLAGER", 12, 0.4, 0.5, 0.4, 0);
        plugin.fx().sound(p.getLocation(), "entity.generic.eat", 1f, 1f);
        Util.actionbar(p, plugin.text("food_used", "removed", String.valueOf(removed), "kept", String.valueOf(kept),
                "left", String.valueOf(chargesLeft(p)), "max", String.valueOf(maxCharges())));
    }
}
