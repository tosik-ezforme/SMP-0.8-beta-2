package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Базовый класс легендарного оружия. */
public abstract class Weapon {

    public enum Cat { NORMAL, SPECIAL, UNIQUE }

    protected final LegendaryPlugin plugin;
    public final String id;
    public final String name;
    public final String altarName;
    public final Material material;
    private final Cat defaultCat;

    protected Weapon(LegendaryPlugin plugin, String id, String name, Material material, String altarName, Cat defaultCat) {
        this.plugin = plugin;
        this.id = id;
        this.name = name;
        this.material = material;
        this.altarName = altarName;
        this.defaultCat = defaultCat;
    }

    public abstract String primaryName();

    public abstract String secondaryName();

    public abstract String blurb();

    /** @return true, если способность сработала (тогда включается кулдаун). */
    public abstract boolean primary(Player p);

    public abstract boolean secondary(Player p);

    /** Удар владельцем (оружие в основной руке), вызывается до применения урона. */
    public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
    }

    /** Любой урон по игроку (для защитных способностей). */
    public void onVictim(Player victim, EntityDamageEvent e) {
    }

    /** Подтверждённое убийство игрока этим оружием. */
    public void onKill(Player killer, Player victim, CombatListener.Pending pending) {
    }

    // ------------------------------------------------------------ config helpers

    public Cat category() {
        try {
            return Cat.valueOf(plugin.getConfig().getString("items." + id + ".ritual_category", defaultCat.name()).toUpperCase());
        } catch (IllegalArgumentException ex) {
            return defaultCat;
        }
    }

    public boolean unique() {
        return plugin.getConfig().getBoolean("items." + id + ".unique", false);
    }

    public double d(String key, double def) {
        return plugin.getConfig().getDouble("items." + id + "." + key, def);
    }

    public int i(String key, int def) {
        return plugin.getConfig().getInt("items." + id + "." + key, def);
    }

    public String plainName() {
        return Util.strip(name);
    }

    // ------------------------------------------------------------ helpers for subclasses

    protected Buffs buffs() {
        return plugin.buffs();
    }

    protected Fx fx() {
        return plugin.fx();
    }

    protected void say(Player p, String text) {
        Util.actionbar(p, text);
    }

    protected void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
    }

    /** Нанести урон способностью (с проверкой приватов). Урон помечается как «от способности». */
    protected boolean hurt(Player owner, LivingEntity target, double amount) {
        if (!Util.canHarm(owner, target)) {
            return false;
        }
        plugin.abilityDepth++;
        try {
            target.damage(amount, owner);
        } finally {
            plugin.abilityDepth--;
        }
        return true;
    }

    /** Волна урона по всем врагам в радиусе. @return сколько целей задето */
    protected int nova(Player owner, Location center, double radius, double dmg) {
        int n = 0;
        for (LivingEntity le : enemies(owner, center, radius)) {
            if (hurt(owner, le, dmg)) {
                n++;
            }
        }
        return n;
    }

    protected List<LivingEntity> enemies(Player owner, Location center, double radius) {
        return Util.enemies(owner, center, radius);
    }

    /** Рывок вперёд с уроном по пути. Каждая цель задевается один раз. */
    protected void dash(Player p, double speed, int ticks, double radius, String trail, Consumer<LivingEntity> onHit) {
        Vector dir = p.getLocation().getDirection().clone();
        dir.setY(Math.max(-0.2, Math.min(dir.getY(), 0.25)));
        if (dir.lengthSquared() < 1.0E-4) {
            return;
        }
        final Vector step = dir.normalize().multiply(speed);
        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t++ >= ticks) {
                    cancel();
                    return;
                }
                Vector v = step.clone();
                if (v.getY() < 0.05 && p.isOnGround()) {
                    v.setY(0.05);
                }
                p.setVelocity(v);
                fx().particle(p.getLocation().add(0, 0.6, 0), trail, 6, 0.25, 0.4, 0.25, 0.02);
                for (LivingEntity le : enemies(p, p.getLocation().add(0, 1, 0), radius)) {
                    if (hit.add(le.getUniqueId())) {
                        onHit.accept(le);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
