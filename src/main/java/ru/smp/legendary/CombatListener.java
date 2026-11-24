package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Боевая логика: передача ударов в оружие, отслеживание ФИНАЛЬНОГО смертельного удара
 * (Похититель жизней, Молот бана, прогресс разблокировки).
 */
public final class CombatListener implements Listener {

    /** Потенциально смертельный удар легендарным оружием. */
    public record Pending(EntityDamageByEntityEvent event, UUID attacker, String attackerName,
                          String weaponId, boolean melee, long timeMs) {
    }

    private final LegendaryPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public CombatListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    /** Защитные способности (Божественная защита, Ледяная оболочка, Кровавый долг). */
    /**
     * Урон способностей легендарок не режется бронёй и чарами защиты: цель в полной алмазке с Защитой III
     * получает столько же, сколько и без брони. Работает после защитных способностей (HIGH), эффекты
     * сопротивления и поглощения остаются.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAbilityPierce(EntityDamageEvent e) {
        if (plugin.abilityDepth <= 0 || plugin.fakeDamage || !plugin.getConfig().getBoolean("abilities.ignore_armor", true)) {
            return;
        }
        for (EntityDamageEvent.DamageModifier m : new EntityDamageEvent.DamageModifier[]{
                EntityDamageEvent.DamageModifier.ARMOR, EntityDamageEvent.DamageModifier.MAGIC}) {
            try {
                if (e.isApplicable(m)) {
                    e.setDamage(m, 0);
                }
            } catch (Exception ignored) {
                // модификатор недоступен для этого типа урона
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (plugin.fakeDamage || !(e.getEntity() instanceof Player victim)) {
            return;
        }
        for (Weapon w : plugin.items().weapons().values()) {
            w.onVictim(victim, e);
        }
    }

    /** Удар владельцем с оружием в основной руке. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (plugin.fakeDamage || plugin.abilityDepth > 0) {
            return;
        }
        if (!(e.getDamager() instanceof Player attacker) || !(e.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (!Util.worldAllowed(attacker)) {
            return;
        }
        Weapon w = plugin.items().weaponOf(attacker.getInventory().getItemInMainHand());
        if (w != null) {
            w.onHit(attacker, victim, e);
        }
    }

    /** Запоминаем удар, который при окончательном расчёте убивает игрока. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void track(EntityDamageByEntityEvent e) {
        if (plugin.fakeDamage || !(e.getEntity() instanceof Player victim)) {
            return;
        }
        pending.remove(victim.getUniqueId());
        if (!(e.getDamager() instanceof Player attacker)) {
            return;
        }
        if (victim.getHealth() - e.getFinalDamage() > 0) {
            return;
        }
        String wid = plugin.items().weaponId(attacker.getInventory().getItemInMainHand());
        if (wid == null) {
            return;
        }
        EntityDamageEvent.DamageCause c = e.getCause();
        boolean melee = plugin.abilityDepth == 0
                && (c == EntityDamageEvent.DamageCause.ENTITY_ATTACK || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
        pending.put(victim.getUniqueId(),
                new Pending(e, attacker.getUniqueId(), attacker.getName(), wid, melee, System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        plugin.buffs().clearAll(victim.getUniqueId());
        Pending p = pending.remove(victim.getUniqueId());
        if (p == null) {
            return;
        }
        // Убеждаемся, что именно этот удар стал последним уроном (тотем бессмертия, смена урона и т.п.)
        EntityDamageEvent last = victim.getLastDamageCause();
        boolean same = last == p.event()
                || (last instanceof EntityDamageByEntityEvent le
                && le.getDamager().getUniqueId().equals(p.attacker())
                && System.currentTimeMillis() - p.timeMs() < 250);
        if (!same) {
            return;
        }
        Weapon w = plugin.items().weapon(p.weaponId());
        if (w == null) {
            return;
        }
        Player killer = Bukkit.getPlayer(p.attacker());

        // Прогресс разблокировки второй способности
        if (plugin.progress().add(p.attacker(), w.id, victim.getUniqueId()) && killer != null) {
            int need = plugin.progress().need();
            int count = plugin.progress().count(p.attacker(), w.id);
            if (count == need) {
                plugin.msg(killer, "unlocked", "weapon", w.plainName());
                plugin.fx().sound(killer.getLocation(), "entity.player.levelup", 1f, 1.2f);
            } else if (count < need) {
                plugin.msg(killer, "progress_kill", "weapon", w.plainName(),
                        "count", String.valueOf(count), "need", String.valueOf(need));
            }
        }
        w.onKill(killer, victim, p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }
}
