package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Булавы: Молот бана, Громовержец, Тектон. */
public final class MaceWeapons {

    private MaceWeapons() {
    }

    // =====================================================================
    //  Молот бана
    // =====================================================================
    public static final class BanHammer extends Weapon {

        BanHammer(LegendaryPlugin p) {
            super(p, "ban_hammer", "&c&lМолот бана", Material.MACE, "Судный алтарь", Cat.UNIQUE);
        }

        @Override public String primaryName() { return "Приговор"; }
        @Override public String secondaryName() { return "Последнее предупреждение"; }
        @Override public String blurb() { return "Смертельный удар = вечный бан."; }

        /** Первая способность (в ТЗ не описана — рабочая версия): метка цели с бонусным уроном. */
        @Override
        public boolean primary(Player p) {
            LivingEntity t = Util.targetInSight(p, 25);
            if (t == null || !Util.isEnemy(p, t)) {
                say(p, "&cНет цели в прицеле");
                return false;
            }
            int sec = i("verdict_seconds", 8);
            buffs().set(p.getUniqueId(), "verdict", sec * 1000L, t.getUniqueId());
            Util.addEffect(t, "glowing", sec * 20, 0);
            fx().particle(t.getLocation().add(0, 2.3, 0), "SOUL_FIRE_FLAME", 20, 0.3, 0.2, 0.3, 0.02);
            fx().sound(t.getLocation(), "entity.wither.spawn", 0.3f, 1.8f);
            hurt(p, t, d("verdict_damage", 6)); // молот выносит приговор сразу
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            double r = d("zone_radius", 8);
            Location c = p.getLocation().clone();
            plugin.suppression().add(p, c, r, i("zone_seconds", 6));
            nova(p, c, r, d("zone_damage", 4)); // ударная волна судного круга
            fx().ring(c, r, 60, "SOUL_FIRE_FLAME");
            fx().particle(c.clone().add(0, 0.2, 0), "EXPLOSION", 6, 1.5, 0.1, 1.5, 0);
            fx().sound(c, "block.anvil.land", 1f, 0.6f);
            return true;
        }

        @Override
        public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
            Buffs.Entry en = buffs().get(attacker.getUniqueId(), "verdict");
            if (en != null && victim.getUniqueId().equals(en.data)) {
                e.setDamage(e.getDamage() + d("verdict_bonus", 3));
            }
        }

        @Override
        public void onKill(Player killer, Player victim, CombatListener.Pending pd) {
            // Только финальный ближний удар молотом, не способность.
            if (!pd.melee() || !plugin.getConfig().getBoolean("items.ban_hammer.ban_enabled", true)) {
                return;
            }
            if (victim.hasPermission("legendary.banhammer.immune")) {
                plugin.adminLog("system", "banhammer_immune", victim.getName() + " killed by " + pd.attackerName() + ", ban skipped (immune)");
                return;
            }
            String reason = plugin.getConfig().getString("items.ban_hammer.reason", "Убит Молотом бана игроком {killer}")
                    .replace("{killer}", pd.attackerName());
            Location l = victim.getLocation();
            plugin.db().update("INSERT INTO ban_log(ts, killer_uuid, killer_name, victim_uuid, victim_name, world, x, y, z, reason, weapon) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis(), pd.attacker().toString(), pd.attackerName(),
                    victim.getUniqueId().toString(), victim.getName(),
                    l.getWorld() == null ? "?" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), reason, id);
            final String cmd = plugin.getConfig().getString("items.ban_hammer.ban_command", "ban {player} {reason}")
                    .replace("{player}", victim.getName()).replace("{reason}", reason);
            final String announce = plugin.text("ban_notice", "player", victim.getName());
            // Бан выполняется на следующий тик, уже после смерти
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                Bukkit.broadcastMessage(announce);
            });
        }
    }

    // =====================================================================
    //  Громовержец
    // =====================================================================
    public static final class Thunderer extends Weapon {

        Thunderer(LegendaryPlugin p) {
            super(p, "thunderer", "&bГромовержец", Material.MACE, "Громовой чертог", Cat.SPECIAL);
        }

        @Override public String primaryName() { return "Небесный удар"; }
        @Override public String secondaryName() { return "Грозовой шаг"; }
        @Override public String blurb() { return "Молния подчиняется владельцу."; }

        @Override
        public boolean primary(Player p) {
            LivingEntity t = Util.targetInSight(p, d("range", 30));
            Location point = t != null && Util.isEnemy(p, t) ? t.getLocation().clone() : Util.aimPoint(p, d("range", 30));
            if (point == null) {
                say(p, "&cНет точки удара");
                return false;
            }
            final Location target = point.clone();
            final int warn = i("warning_ticks", 20);
            final double radius = d("radius", 3.5);
            final double dmg = d("damage", 9);
            fx().sound(target, "entity.lightning_bolt.impact", 0.3f, 2f);
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!p.isOnline() || p.isDead()) {
                        cancel();
                        return;
                    }
                    if (t >= warn) {
                        cancel();
                        target.getWorld().strikeLightningEffect(target);
                        for (LivingEntity le : enemies(p, target, radius)) {
                            hurt(p, le, dmg);
                        }
                        fx().particle(target.clone().add(0, 1, 0), "ELECTRIC_SPARK", 40, radius / 2, 1, radius / 2, 0.3);
                        return;
                    }
                    fx().ring(target, radius, 28, "ELECTRIC_SPARK");
                    fx().particle(target.clone().add(0, 0.3, 0), "END_ROD", 3, 0.2, 0.1, 0.2, 0);
                    t += 4;
                }
            }.runTaskTimer(plugin, 0L, 4L);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            final double dmg = d("dash_damage", 5);
            dash(p, d("dash_speed", 1.5), i("dash_ticks", 7), 1.6, "ELECTRIC_SPARK", le -> {
                if (hurt(p, le, dmg)) {
                    Util.addEffect(le, "slowness", 40, 1);
                    fx().particle(le.getLocation().add(0, 1, 0), "ELECTRIC_SPARK", 25, 0.3, 0.6, 0.3, 0.3);
                }
            });
            fx().sound(p.getLocation(), "entity.lightning_bolt.thunder", 0.5f, 1.8f);
            return true;
        }
    }

    // =====================================================================
    //  Страж Глубин (булава тематики Вардена; внутренний id остался "tecton")
    // =====================================================================
    public static final class Tecton extends Weapon {

        Tecton(LegendaryPlugin p) {
            super(p, "tecton", "&3Страж Глубин", Material.MACE, "Алтарь Глубокой Тьмы", Cat.SPECIAL);
        }

        @Override public String primaryName() { return "Звуковой удар"; }
        @Override public String secondaryName() { return "Зов Тьмы"; }
        @Override public String blurb() { return "Сила Стража: звуковая волна и тьма глубин."; }

        /** Звуковой удар Стража: после короткой зарядки по линии взгляда бьёт волна, пробивающая всех на пути. */
        @Override
        public boolean primary(Player p) {
            final double range = d("sonic_range", 22);
            final double dmg = d("sonic_damage", 10);
            final int charge = Math.max(1, i("sonic_charge_ticks", 10));
            fx().sound(p.getLocation(), "entity.warden.sonic_charge", 1.2f, 1f);
            fx().particle(p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.8)), "SCULK_SOUL", 20, 0.3, 0.3, 0.3, 0.05);
            later(charge, () -> {
                if (!p.isOnline() || p.isDead()) {
                    return;
                }
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                Set<UUID> hit = new HashSet<>();
                fx().sound(eye, "entity.warden.sonic_boom", 1.6f, 1f);
                for (int step = 1; step <= (int) range; step++) {
                    Location pt = eye.clone().add(dir.clone().multiply(step));
                    if (!pt.getBlock().isPassable()) {
                        break; // волна не проходит сквозь стены
                    }
                    if (step % 2 == 0) {
                        fx().particle(pt, "SONIC_BOOM", 1, 0, 0, 0, 0);
                    }
                    fx().particle(pt, "SCULK_SOUL", 2, 0.15, 0.15, 0.15, 0.01);
                    for (LivingEntity le : enemies(p, pt, 1.4)) {
                        if (hit.add(le.getUniqueId()) && hurt(p, le, dmg)) {
                            Util.knock(le, p.getLocation(), 1.1, 0.3);
                            fx().particle(le.getLocation().add(0, 1, 0), "SCULK_SOUL", 15, 0.3, 0.5, 0.3, 0.05);
                        }
                    }
                }
            });
            return true;
        }

        /** Зов Тьмы: волна глубин накрывает врагов вокруг — тьма в глазах и светящийся контур сквозь стены. */
        @Override
        public boolean secondary(Player p) {
            final double radius = d("dark_radius", 14);
            List<LivingEntity> targets = enemies(p, p.getLocation(), radius);
            if (targets.isEmpty()) {
                say(p, "&cВрагов поблизости нет");
                return false;
            }
            final int dark = i("dark_ticks", 120);
            final int glow = i("glow_ticks", 100);
            final double kb = d("dark_knockback", 0.6);
            fx().sound(p.getLocation(), "entity.warden.heartbeat", 1.5f, 0.7f);
            fx().sound(p.getLocation(), "entity.warden.agitated", 1.2f, 0.8f);
            for (LivingEntity le : targets) {
                hurt(p, le, d("dark_damage", 5));
                Util.addEffect(le, "darkness", dark, 0);
                Util.addEffect(le, "glowing", glow, 0);
                Util.knock(le, p.getLocation(), kb, 0.25);
                fx().particle(le.getLocation().add(0, 1, 0), "SCULK_SOUL", 18, 0.3, 0.6, 0.3, 0.03);
            }
            final Location c = p.getLocation().clone();
            new BukkitRunnable() {
                double r = 0;

                @Override
                public void run() {
                    r += 1.6;
                    if (r > radius) {
                        cancel();
                        return;
                    }
                    fx().ring(c.clone().add(0, 0.2, 0), r, (int) Math.min(80, r * 10), "SCULK_SOUL");
                    fx().ring(c.clone().add(0, 0.2, 0), r * 0.8, (int) Math.min(60, r * 8), "SCULK_CHARGE_POP");
                }
            }.runTaskTimer(plugin, 0L, 2L);
            return true;
        }
    }
}
