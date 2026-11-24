package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Медный комплект.
 *  Шлем «Искатель»      — периодически подсвечивает ближайших игроков (только владельцу шлема).
 *  Нагрудник «Проводник» — копит заряд, когда игрок получает урон от другого игрока.
 *  Поножи «Гальваника»  — сокращают длительность замедления, слабости и слепоты (но не отменяют их).
 *  Ботинки «Импульс»    — ускорение после жёсткого приземления; урон от падения лишь немного снижается.
 *  Комплект целиком     — Shift+F: «Гальванический разряд» (расходует все заряды Проводника).
 */
public final class ArmorSet implements Listener {

    public static final String CHARGE_KEY = "copper_charge";
    private static final String CD_KEY = "copper_set:primary";

    private record Detection(long until, List<UUID> targets) {
    }

    private final LegendaryPlugin plugin;
    private final Map<UUID, Long> nextScan = new HashMap<>();
    private final Map<UUID, Detection> detections = new HashMap<>();
    private final Map<UUID, Long> lastGain = new HashMap<>();
    private final Set<UUID> reentry = new HashSet<>();

    public ArmorSet(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHelmets, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickLeggings, 20L, 40L);
    }

    /** Поножи постоянно дают эффект «Плетение» (по умолчанию уровень 2): при смерти вокруг появляется паутина. */
    private void tickLeggings() {
        int level = (int) d("leggings.weaving_level", 2);
        if (level <= 0) {
            return;
        }
        PotionEffectType weaving = Util.effect("weaving");
        if (weaving == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || !wears(p, "copper_leggings")) {
                continue;
            }
            p.addPotionEffect(new PotionEffect(weaving, 100, level - 1, true, false, true));
        }
    }

    // ------------------------------------------------------------ identification

    private String idOf(ItemStack s) {
        return plugin.items().armorId(s);
    }

    private boolean wears(Player p, String slotId) {
        return switch (slotId) {
            case "copper_helmet" -> slotId.equals(idOf(p.getInventory().getHelmet()));
            case "copper_chestplate" -> slotId.equals(idOf(p.getInventory().getChestplate()));
            case "copper_leggings" -> slotId.equals(idOf(p.getInventory().getLeggings()));
            case "copper_boots" -> slotId.equals(idOf(p.getInventory().getBoots()));
            default -> false;
        };
    }

    public boolean fullSet(Player p) {
        for (String id : ItemRegistry.ARMOR_IDS) {
            if (!wears(p, id)) {
                return false;
            }
        }
        return true;
    }

    private double d(String path, double def) {
        return plugin.getConfig().getDouble("copper_armor." + path, def);
    }

    // ------------------------------------------------------------ helmet: «Искатель»

    private void tickHelmets() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!wears(p, "copper_helmet") || !Util.worldAllowed(p) || p.isDead()) {
                nextScan.remove(u);
                detections.remove(u);
                continue;
            }
            if (now >= nextScan.getOrDefault(u, 0L)) {
                nextScan.put(u, now + (long) (d("helmet.interval_seconds", 15) * 1000));
                scan(p, now);
            }
            Detection det = detections.get(u);
            if (det != null) {
                if (now >= det.until()) {
                    detections.remove(u);
                } else {
                    show(p, det);
                }
            }
        }
    }

    private void scan(Player p, long now) {
        double radius = d("helmet.radius", 40);
        int max = (int) d("helmet.max_targets", 5);
        List<Player> found = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p) || o.getGameMode() == GameMode.SPECTATOR || Util.isAlly(p, o)) {
                continue;
            }
            if (o.getLocation().distanceSquared(p.getLocation()) <= radius * radius) {
                found.add(o);
            }
        }
        found.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(p.getLocation()),
                b.getLocation().distanceSquared(p.getLocation())));
        if (found.size() > max) {
            found = new ArrayList<>(found.subList(0, max));
        }
        if (found.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>();
        for (Player o : found) {
            ids.add(o.getUniqueId());
        }
        detections.put(p.getUniqueId(), new Detection(now + (long) (d("helmet.duration_seconds", 5) * 1000), ids));
        plugin.fx().sound(p.getLocation(), "block.beacon.activate", 0.6f, 1.6f);
    }

    /** Показываем метки только владельцу шлема (Player#spawnParticle отправляет частицы одному игроку). */
    private void show(Player p, Detection det) {
        StringBuilder bar = new StringBuilder("&6Искатель: ");
        int shown = 0;
        for (UUID id : det.targets()) {
            Player o = Bukkit.getPlayer(id);
            if (o == null || !o.getWorld().equals(p.getWorld()) || o.isDead()) {
                continue;
            }
            Location l = o.getLocation();
            for (int i = 0; i < 12; i++) {
                p.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + i * 0.5, l.getZ(), 1, 0.02, 0.02, 0.02, 0);
            }
            if (shown < 3) {
                int dist = (int) Math.round(l.distance(p.getLocation()));
                bar.append("&f").append(o.getName()).append(" &7").append(dist).append("м  ");
            }
            shown++;
        }
        if (shown > 0) {
            Util.actionbar(p, bar.toString().trim());
        }
    }

    // ------------------------------------------------------------ chestplate: «Проводник»

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent e) {
        if (plugin.fakeDamage || !(e.getEntity() instanceof Player victim) || !wears(victim, "copper_chestplate")) {
            return;
        }
        Player source = null;
        if (e.getDamager() instanceof Player pl) {
            source = pl;
        } else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) {
            source = pl;
        }
        if (source == null || source.equals(victim) || e.getFinalDamage() <= 0) {
            return;
        }
        UUID u = victim.getUniqueId();
        long now = System.currentTimeMillis();
        long gap = plugin.getConfig().getLong("copper_armor.chestplate.gain_cooldown_ms", 500L);
        if (now - lastGain.getOrDefault(u, 0L) < gap) {
            return;
        }
        lastGain.put(u, now);
        int max = (int) d("chestplate.max_charges", 10);
        int n = plugin.charges().add(u, CHARGE_KEY, 1, max);
        Util.actionbar(victim, plugin.text("charge_gain", "charges", n + "/" + max));
        plugin.fx().particle(victim.getLocation().add(0, 1.2, 0), "ELECTRIC_SPARK", 8, 0.3, 0.5, 0.3, 0.05);
    }

    // ------------------------------------------------------------ leggings: «Гальваника»

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || reentry.contains(p.getUniqueId())) {
            return;
        }
        EntityPotionEffectEvent.Action a = e.getAction();
        if (a != EntityPotionEffectEvent.Action.ADDED && a != EntityPotionEffectEvent.Action.CHANGED) {
            return;
        }
        PotionEffect ne = e.getNewEffect();
        if (ne == null || ne.getDuration() < 0 || !wears(p, "copper_leggings")) {
            return;
        }
        PotionEffectType t = ne.getType();
        if (!t.equals(Util.effect("slowness")) && !t.equals(Util.effect("weakness")) && !t.equals(Util.effect("blindness"))) {
            return;
        }
        int min = (int) d("leggings.min_ticks", 20);
        int dur = ne.getDuration();
        if (dur <= min) {
            return;
        }
        int shortened = Math.max(min, (int) Math.round(dur * d("leggings.duration_multiplier", 0.6)));
        if (shortened >= dur) {
            return;
        }
        e.setCancelled(true);
        reentry.add(p.getUniqueId());
        try {
            p.addPotionEffect(new PotionEffect(t, shortened, ne.getAmplifier(), ne.isAmbient(), ne.hasParticles(), ne.hasIcon()));
        } finally {
            reentry.remove(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------ boots: «Импульс»

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)
                || !wears(p, "copper_boots") || !Util.worldAllowed(p)) {
            return;
        }
        double dmg = e.getDamage();
        if (dmg < d("boots.min_fall_damage", 5)) {
            return;
        }
        // Урон лишь немного снижается и никогда не обнуляется полностью
        double reduced = dmg * (1.0 - Math.max(0, Math.min(0.5, d("boots.fall_damage_reduction", 0.2))));
        e.setDamage(Math.max(1.0, reduced));
        int ticks = (int) d("boots.speed_ticks", 80);
        int amp = (int) d("boots.speed_amplifier", 1);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && !p.isDead()) {
                Util.addEffect(p, "speed", ticks, amp);
                plugin.fx().particle(p.getLocation(), "ELECTRIC_SPARK", 25, 0.5, 0.1, 0.5, 0.15);
                plugin.fx().sound(p.getLocation(), "entity.lightning_bolt.impact", 0.4f, 1.8f);
            }
        });
    }

    // ------------------------------------------------------------ full set: «Гальванический разряд»

    /**
     * Shift+F в полном комплекте. Расходует все заряды Проводника: чем больше заряд, тем сильнее разряд.
     * Шлем: цели, найденные «Искателем», получают бонус урона. Поножи: снимают с владельца замедление/слабость/слепоту.
     * Ботинки: короткое ускорение. Разряд не тратится, если целей нет.
     */
    public void useSetAbility(Player p) {
        if (!Util.worldAllowed(p)) {
            plugin.msg(p, "world_disabled");
            return;
        }
        if (plugin.suppression().isSuppressed(p)) {
            plugin.msg(p, "suppressed");
            return;
        }
        UUID u = p.getUniqueId();
        long rem = plugin.cooldowns().remaining(u, CD_KEY);
        if (rem > 0) {
            Util.actionbar(p, plugin.text("cooldown", "time", String.format(Locale.ROOT, "%.1f", rem / 1000.0)));
            return;
        }
        int have = plugin.charges().get(u, CHARGE_KEY);
        int need = (int) d("set.min_charges", 5);
        if (have < need) {
            plugin.msg(p, "set_no_charge", "have", String.valueOf(have), "need", String.valueOf(need));
            return;
        }
        List<LivingEntity> targets = Util.enemies(p, p.getLocation(), d("set.radius", 10));
        int maxT = (int) d("set.max_targets", 5);
        if (targets.size() > maxT) {
            targets = new ArrayList<>(targets.subList(0, maxT));
        }
        double base = Math.min(d("set.max_damage", 12), d("set.base_damage", 2) + d("set.damage_per_charge", 0.8) * have);
        Detection det = detections.get(u);
        double bonus = 1.0 + d("set.detected_bonus", 0.25);

        int hits = 0;
        Location from = p.getLocation().add(0, 1.2, 0);
        for (LivingEntity t : targets) {
            if (!Util.canHarm(p, t)) {
                continue;
            }
            double dmg = base;
            if (det != null && System.currentTimeMillis() < det.until() && det.targets().contains(t.getUniqueId())) {
                dmg *= bonus;
            }
            plugin.abilityDepth++;
            try {
                t.damage(dmg, p);
            } finally {
                plugin.abilityDepth--;
            }
            plugin.fx().line(from, t.getLocation().add(0, 1.0, 0), "ELECTRIC_SPARK", 0.4);
            plugin.fx().particle(t.getLocation().add(0, 1, 0), "ELECTRIC_SPARK", 20, 0.3, 0.5, 0.3, 0.2);
            hits++;
        }
        if (hits == 0) {
            plugin.msg(p, "no_target");
            return;
        }
        plugin.charges().set(u, CHARGE_KEY, 0);
        plugin.cooldowns().set(u, CD_KEY, plugin.getConfig().getInt("abilities.cooldowns.copper_set.primary", 45));

        // поножи: очищаем негативные эффекты, ботинки: ускорение
        Util.removeEffect(p, "slowness");
        Util.removeEffect(p, "weakness");
        Util.removeEffect(p, "blindness");
        Util.addEffect(p, "speed", (int) d("set.speed_ticks", 60), 1);

        plugin.fx().ring(p.getLocation().add(0, 0.2, 0), d("set.radius", 10) * 0.6, 40, "ELECTRIC_SPARK");
        plugin.fx().sound(p.getLocation(), "entity.lightning_bolt.thunder", 0.6f, 1.9f);
        Util.actionbar(p, plugin.text("used", "ability", "Гальванический разряд"));
    }

    // ------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        nextScan.remove(u);
        detections.remove(u);
        lastGain.remove(u);
        reentry.remove(u);
    }
}
