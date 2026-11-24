package ru.smp.legendary;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.List;

/** Мечи: Похититель жизней, Лучезар, Эндер-клинок. */
public final class SwordWeapons {

    private SwordWeapons() {
    }

    // =====================================================================
    //  Похититель жизней
    // =====================================================================
    public static final class LifeStealer extends Weapon {

        LifeStealer(LegendaryPlugin p) {
            super(p, "life_stealer", "&4Похититель жизней", Material.NETHERITE_SWORD, "Алтарь похищенных душ", Cat.SPECIAL);
        }

        @Override public String primaryName() { return "Жатва душ"; }
        @Override public String secondaryName() { return "Покров душ"; }
        @Override public String blurb() { return "Смертельный удар навсегда крадёт сердце жертвы."; }

        @Override
        public boolean primary(Player p) {
            double radius = d("harvest_radius", 4.5);
            List<LivingEntity> targets = enemies(p, p.getLocation(), radius);
            if (targets.isEmpty()) {
                say(p, "&cНет целей рядом");
                return false;
            }
            double dmg = d("harvest_damage", 4), total = 0;
            for (LivingEntity t : targets) {
                if (hurt(p, t, dmg)) {
                    total += dmg;
                    fx().line(t.getLocation().add(0, 1, 0), p.getLocation().add(0, 1, 0), "SOUL", 0.4);
                }
            }
            if (total <= 0) {
                return false;
            }
            Util.heal(p, Math.min(total * d("harvest_heal_ratio", 0.3), d("harvest_heal_cap", 6)));
            fx().ring(p.getLocation(), radius, 40, "SOUL");
            fx().sound(p.getLocation(), "entity.wither.ambient", 0.6f, 1.4f);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            int ticks = i("shroud_seconds", 8) * 20;
            Util.addEffect(p, "absorption", ticks, 1);
            Util.addEffect(p, "speed", ticks, 0);
            // души срываются с покрова и бьют ближайших врагов
            nova(p, p.getLocation(), d("shroud_radius", 5), d("shroud_damage", 4));
            fx().ring(p.getLocation().add(0, 0.2, 0), d("shroud_radius", 5), 40, "SOUL");
            fx().particle(p.getLocation().add(0, 1, 0), "SOUL", 30, 0.5, 0.8, 0.5, 0.05);
            fx().sound(p.getLocation(), "particle.soul_escape", 1f, 1f);
            return true;
        }

        @Override
        public void onKill(Player killer, Player victim, CombatListener.Pending pd) {
            if (killer == null || !pd.melee()) {
                return;
            }
            plugin.hearts().steal(killer, victim);
            if (plugin.getConfig().getBoolean("hearts.eliminate_at_min", true)
                    && plugin.hearts().get(victim.getUniqueId()) <= plugin.hearts().min()) {
                plugin.hearts().eliminate(victim);
            }
        }
    }

    // =====================================================================
    //  Лучезар (меч света, оружие №2)
    // =====================================================================
    public static final class LightBlade extends Weapon {

        LightBlade(LegendaryPlugin p) {
            super(p, "light_blade", "&eЛучезар", Material.NETHERITE_SWORD, "Рассекатель света", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Световой импульс"; }
        @Override public String secondaryName() { return "Божественная защита"; }
        @Override public String blurb() { return "Клинок, рассекающий тьму вспышкой света."; }

        @Override
        public boolean primary(Player p) {
            double radius = d("pulse_radius", 6);
            List<LivingEntity> targets = enemies(p, p.getLocation(), radius);
            for (LivingEntity t : targets) {
                if (!Util.canHarm(p, t)) {
                    continue;
                }
                Util.addEffect(t, "blindness", i("blind_ticks", 60), 0);
                Util.knock(t, p.getLocation(), d("knockback", 1.3), 0.4);
                hurt(p, t, d("pulse_damage", 5));
            }
            for (double r = 1.5; r <= radius; r += 1.5) {
                fx().ring(p.getLocation(), r, 36, "END_ROD");
            }
            fx().particle(p.getLocation().add(0, 1, 0), "END_ROD", 40, 0.4, 0.6, 0.4, 0.25);
            fx().sound(p.getLocation(), "block.beacon.activate", 1f, 1.6f);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            if (buffs().active(p.getUniqueId(), "divine")) {
                say(p, "&cЗащита уже активна");
                return false;
            }
            long ms = i("divine_seconds", 8) * 1000L;
            buffs().set(p.getUniqueId(), "divine", ms, null);
            nova(p, p.getLocation(), d("divine_radius", 4), d("divine_damage", 4));
            fx().ring(p.getLocation().add(0, 0.2, 0), d("divine_radius", 4), 36, "END_ROD");
            fx().sound(p.getLocation(), "item.totem.use", 0.7f, 1.4f);
            final Player owner = p;
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!owner.isOnline() || owner.isDead() || !buffs().active(owner.getUniqueId(), "divine")) {
                        cancel();
                        if (owner.isOnline() && !owner.isDead() && t * 5L >= ms / 50 - 5) {
                            flash(owner);
                        }
                        buffs().clear(owner.getUniqueId(), "divine");
                        return;
                    }
                    t++;
                    fx().ring(owner.getLocation(), 1.1, 14, "END_ROD");
                    fx().particle(owner.getLocation().add(0, 1, 0), "END_ROD", 2, 0.3, 0.7, 0.3, 0.01);
                }
            }.runTaskTimer(plugin, 0L, 5L);
            return true;
        }

        private void flash(Player p) {
            double radius = d("flash_radius", 5);
            for (LivingEntity t : enemies(p, p.getLocation(), radius)) {
                if (Util.canHarm(p, t)) {
                    Util.knock(t, p.getLocation(), d("knockback", 1.3), 0.45);
                    Util.addEffect(t, "blindness", 30, 0);
                    hurt(p, t, d("flash_damage", 4));
                }
            }
            for (double r = 1.5; r <= radius; r += 1.5) {
                fx().ring(p.getLocation(), r, 30, "END_ROD");
            }
            fx().sound(p.getLocation(), "entity.generic.explode", 0.6f, 1.8f);
        }

        @Override
        public void onVictim(Player victim, EntityDamageEvent e) {
            if (buffs().active(victim.getUniqueId(), "divine")) {
                e.setDamage(e.getDamage() * (1.0 - Math.max(0, Math.min(0.9, d("divine_absorb", 0.4)))));
            }
        }
    }

    // =====================================================================
    //  Эндер-клинок
    // =====================================================================
    public static final class EnderBlade extends Weapon {

        EnderBlade(LegendaryPlugin p) {
            super(p, "ender_blade", "&5Эндер-клинок", Material.NETHERITE_SWORD, "Разлом Края", Cat.UNIQUE);
        }

        @Override public String primaryName() { return "Разлом пространства"; }
        @Override public String secondaryName() { return "Обмен измерениями"; }
        @Override public String blurb() { return "Рвёт ткань пространства и меняет места."; }

        @Override
        public boolean primary(Player p) {
            final Location portal = p.getEyeLocation().add(p.getLocation().getDirection().multiply(2.5));
            final List<LivingEntity> targets = enemies(p, p.getLocation(), d("shard_range", 20));
            final int delay = i("portal_delay_ticks", 15);
            fx().sound(portal, "block.end_portal.spawn", 0.5f, 1.8f);
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!p.isOnline() || p.isDead()) {
                        cancel();
                        return;
                    }
                    if (t >= delay) {
                        cancel();
                        launch(p, portal, targets);
                        return;
                    }
                    fx().ring(portal, 0.9, 18, "PORTAL");
                    fx().particle(portal, "REVERSE_PORTAL", 8, 0.3, 0.3, 0.3, 0.1);
                    t += 3;
                }
            }.runTaskTimer(plugin, 0L, 3L);
            return true;
        }

        private Vector aim(Player p, Location from, List<LivingEntity> targets, int k, int n) {
            if (!targets.isEmpty()) {
                LivingEntity t = targets.get(k % targets.size());
                Vector v = t.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
                if (v.lengthSquared() > 1.0E-4) {
                    return v.normalize();
                }
            }
            Vector base = p.getLocation().getDirection().clone();
            return base.rotateAroundY(Math.toRadians((k - (n - 1) / 2.0) * 10.0)).normalize();
        }

        private void launch(Player p, Location portal, List<LivingEntity> targets) {
            final int n = Math.max(1, Math.min(12, i("shards", 5)));
            final double dmg = d("shard_damage", 4);
            final double speed = d("shard_speed", 1.0);
            for (int k = 0; k < n; k++) {
                final Vector dir = aim(p, portal, targets, k, n);
                final Location pos = portal.clone();
                new BukkitRunnable() {
                    int life = 0;

                    @Override
                    public void run() {
                        if (!p.isOnline() || life++ > 40) {
                            cancel();
                            return;
                        }
                        pos.add(dir.clone().multiply(speed));
                        if (pos.getBlock().getType().isSolid()) {
                            cancel();
                            return;
                        }
                        fx().particle(pos, "END_ROD", 2, 0.04, 0.04, 0.04, 0);
                        fx().particle(pos, "PORTAL", 3, 0.15, 0.15, 0.15, 0.2);
                        for (Entity en : pos.getWorld().getNearbyEntities(pos, 0.8, 0.8, 0.8)) {
                            if (en instanceof ArmorStand || !Util.isEnemy(p, en)) {
                                continue;
                            }
                            if (hurt(p, (LivingEntity) en, dmg)) {
                                fx().particle(pos, "REVERSE_PORTAL", 15, 0.2, 0.2, 0.2, 0.1);
                                fx().sound(pos, "entity.enderman.hurt", 0.5f, 1.5f);
                                cancel();
                                return;
                            }
                        }
                    }
                }.runTaskTimer(plugin, k * 2L, 1L);
            }
        }

        @Override
        public boolean secondary(Player p) {
            LivingEntity t = Util.targetInSight(p, d("swap_range", 20));
            if (!(t instanceof Player target) || !Util.isEnemy(p, target)) {
                say(p, "&cНет подходящей цели в прицеле");
                return false;
            }
            if (!Util.canHarm(p, target) || p.isInsideVehicle() || target.isInsideVehicle()) {
                say(p, "&cОбмен невозможен");
                return false;
            }
            Location a = p.getLocation().clone();
            Location b = target.getLocation().clone();
            if (!a.getWorld().equals(b.getWorld()) || !Util.safeStand(a) || !Util.safeStand(b)) {
                say(p, "&cНет безопасного места для обмена");
                return false;
            }
            Location toP = b.clone();
            toP.setYaw(a.getYaw());
            toP.setPitch(a.getPitch());
            Location toT = a.clone();
            toT.setYaw(b.getYaw());
            toT.setPitch(b.getPitch());
            fx().particle(a.clone().add(0, 1, 0), "PORTAL", 40, 0.4, 0.8, 0.4, 0.5);
            fx().particle(b.clone().add(0, 1, 0), "PORTAL", 40, 0.4, 0.8, 0.4, 0.5);
            p.teleport(toP);
            target.teleport(toT);
            Util.addEffect(target, "slowness", 60, 1);
            Util.addEffect(target, "nausea", 100, 0);
            hurt(p, target, d("swap_damage", 5)); // пустота кусает того, кого вытащили из своего места
            fx().sound(toP, "entity.enderman.teleport", 1f, 0.8f);
            fx().sound(toT, "entity.enderman.teleport", 1f, 1.2f);
            return true;
        }
    }
}
