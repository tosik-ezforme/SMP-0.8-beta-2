package ru.smp.legendary;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/** Топоры: Инферно, Ледоруб, Сокрушитель. */
public final class AxeWeapons {

    private AxeWeapons() {
    }

    // =====================================================================
    //  Инферно
    // =====================================================================
    public static final class Inferno extends Weapon {

        Inferno(LegendaryPlugin p) {
            super(p, "inferno", "&cИнферно", Material.NETHERITE_AXE, "Кузня вечного пламени", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Пекло"; }
        @Override public String secondaryName() { return "Пепельное клеймо"; }
        @Override public String blurb() { return "Каждый удар копит жар для пепельного взрыва."; }

        /** Новая первая способность (прежняя отклонена): огненное кольцо вокруг владельца. */
        @Override
        public boolean primary(Player p) {
            final double radius = d("ring_radius", 5);
            final int seconds = i("ring_seconds", 3);
            final double dmg = d("ring_damage", 5);
            final int fire = i("ring_fire_ticks", 80);
            final java.util.Set<java.util.UUID> burnt = new java.util.HashSet<>();
            Util.addEffect(p, "fire_resistance", (seconds + 2) * 20, 0);
            fx().sound(p.getLocation(), "item.firecharge.use", 1f, 0.7f);
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!p.isOnline() || p.isDead() || t >= seconds * 4) {
                        cancel();
                        return;
                    }
                    Location c = p.getLocation();
                    fx().ring(c, radius, 40, "FLAME");
                    fx().ring(c, radius * 0.6, 24, "SMOKE");
                    for (LivingEntity le : enemies(p, c, radius)) {
                        if (burnt.add(le.getUniqueId())) {
                            hurt(p, le, dmg);
                        }
                        le.setFireTicks(fire);
                    }
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            int need = i("required_charges", 5);
            int have = plugin.charges().get(p.getUniqueId(), "inferno");
            if (have < need) {
                say(p, "&cНедостаточно жара: &f" + have + "/" + need);
                return false;
            }
            double radius = d("ash_radius", 4.5);
            Location c = p.getLocation();
            LivingEntity aimed = Util.targetInSight(p, 8);
            if (aimed != null && Util.isEnemy(p, aimed)) {
                c = aimed.getLocation();
            }
            boolean any = false;
            for (LivingEntity le : enemies(p, c, radius)) {
                if (hurt(p, le, d("ash_damage", 6))) {
                    le.setFireTicks(100);
                    any = true;
                }
            }
            if (!any) {
                say(p, "&cНет целей для клейма");
                return false;
            }
            plugin.charges().set(p.getUniqueId(), "inferno", 0);
            fx().ring(c, radius, 40, "FLAME");
            fx().particle(c.clone().add(0, 1, 0), "ASH", 60, radius / 2, 1, radius / 2, 0.05);
            fx().particle(c.clone().add(0, 1, 0), "LARGE_SMOKE", 15, 0.5, 0.8, 0.5, 0.05);
            fx().sound(c, "entity.blaze.shoot", 1f, 0.6f);
            return true;
        }

        @Override
        public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
            int max = i("max_charges", 5);
            int c = plugin.charges().add(attacker.getUniqueId(), "inferno", 1, max);
            say(attacker, "&6Жар: &f" + c + "/" + max);
        }
    }

    // =====================================================================
    //  Ледоруб
    // =====================================================================
    public static final class FrostAxe extends Weapon {

        FrostAxe(LegendaryPlugin p) {
            super(p, "frost_axe", "&bЛедоруб", Material.NETHERITE_AXE, "Ледяной трон", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Ледяная ловушка"; }
        @Override public String secondaryName() { return "Ледяная оболочка"; }
        @Override public String blurb() { return "Замораживает всё, что движется."; }

        @Override
        public boolean primary(Player p) {
            Location point = Util.aimPoint(p, d("trap_range", 14));
            final Location c = (point == null ? p.getLocation() : point).clone();
            final double radius = d("trap_radius", 4);
            final int total = i("trap_seconds", 6) * 4;
            final int amp = i("trap_slow_amp", 2);
            fx().sound(c, "block.glass.break", 1f, 0.6f);
            nova(p, c, radius, d("trap_damage", 4)); // ледяные шипы вырастают под ногами
            fx().particle(c.clone().add(0, 0.5, 0), "SNOWFLAKE", 40, radius / 2, 0.5, radius / 2, 0.05);
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!p.isOnline() || t >= total) {
                        cancel();
                        return;
                    }
                    fx().ring(c, radius, 36, "SNOWFLAKE");
                    fx().particle(c.clone().add(0, 0.4, 0), "SNOWFLAKE", 14, radius / 2, 0.3, radius / 2, 0.02);
                    for (LivingEntity le : enemies(p, c, radius)) {
                        Util.addEffect(le, "slowness", 30, amp);
                    }
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            if (buffs().active(p.getUniqueId(), "frost_shell")) {
                say(p, "&cОболочка уже активна");
                return false;
            }
            final int sec = i("shell_seconds", 8);
            buffs().set(p.getUniqueId(), "frost_shell", sec * 1000L, null);
            nova(p, p.getLocation(), d("shell_radius", 4), d("shell_damage", 4)); // ледяной всплеск при создании
            fx().ring(p.getLocation().add(0, 0.2, 0), d("shell_radius", 4), 36, "SNOWFLAKE");
            fx().sound(p.getLocation(), "block.glass.place", 1f, 0.5f);
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (!p.isOnline() || p.isDead() || !buffs().active(p.getUniqueId(), "frost_shell")) {
                        cancel();
                        return;
                    }
                    fx().ring(p.getLocation(), 1.0, 14, "SNOWFLAKE");
                    fx().particle(p.getLocation().add(0, 1, 0), "SNOWFLAKE", 5, 0.4, 0.8, 0.4, 0.01);
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
            return true;
        }

        @Override
        public void onVictim(Player victim, EntityDamageEvent e) {
            if (!buffs().active(victim.getUniqueId(), "frost_shell")) {
                return;
            }
            e.setDamage(e.getDamage() * (1.0 - Math.max(0, Math.min(0.9, d("shell_reduction", 0.25)))));
            if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager() instanceof LivingEntity attacker) {
                Util.addEffect(attacker, "slowness", i("shell_slow_ticks", 60), 1);
                fx().particle(attacker.getLocation().add(0, 1, 0), "SNOWFLAKE", 12, 0.3, 0.6, 0.3, 0.02);
            }
        }
    }

    // =====================================================================
    //  Сокрушитель
    // =====================================================================
    public static final class Crusher extends Weapon {

        Crusher(LegendaryPlugin p) {
            super(p, "crusher", "&7Сокрушитель", Material.NETHERITE_AXE, "Кузня титанов", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Пробой"; }
        @Override public String secondaryName() { return "Дробление"; }
        @Override public String blurb() { return "Ломает защиту и броню."; }

        /** Взводит «Пробой»: ближайший удар в течение нескольких секунд частично игнорирует броню и щит. */
        @Override
        public boolean primary(Player p) {
            buffs().set(p.getUniqueId(), "pierce", i("pierce_seconds", 6) * 1000L, null);
            nova(p, p.getLocation(), d("pierce_radius", 3.5), d("pierce_damage", 5)); // тяжёлый замах по ближним
            fx().ring(p.getLocation().add(0, 0.2, 0), d("pierce_radius", 3.5), 30, "CRIT");
            fx().particle(p.getLocation().add(0, 1, 0), "CRIT", 25, 0.4, 0.7, 0.4, 0.2);
            fx().sound(p.getLocation(), "item.axe.scrape", 1f, 0.7f);
            say(p, "&7Следующий удар пробьёт защиту");
            return true;
        }

        @Override
        public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
            if (!buffs().active(attacker.getUniqueId(), "pierce")) {
                return;
            }
            buffs().clear(attacker.getUniqueId(), "pierce");
            final double extra = e.getDamage() * d("pierce_ratio", 0.3);
            if (victim instanceof Player vp && vp.isBlocking()) {
                vp.setCooldown(Material.SHIELD, 100);
            }
            fx().particle(victim.getLocation().add(0, 1, 0), "CRIT", 20, 0.3, 0.6, 0.3, 0.3);
            // Часть урона проходит мимо брони. Сам по себе «пробой» не убивает: хп не опускается ниже 1.
            later(1L, () -> {
                if (!victim.isDead() && victim.getHealth() > 1.0) {
                    victim.setHealth(Math.max(1.0, victim.getHealth() - extra));
                }
            });
        }

        @Override
        public boolean secondary(Player p) {
            double radius = d("crush_radius", 5);
            Vector dir = p.getLocation().getDirection().clone().setY(0);
            if (dir.lengthSquared() < 1.0E-4) {
                return false;
            }
            dir.normalize();
            boolean any = false;
            for (LivingEntity le : enemies(p, p.getLocation(), radius)) {
                Vector to = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (to.lengthSquared() < 1.0E-4 || to.normalize().dot(dir) < Math.cos(Math.toRadians(35))) {
                    continue;
                }
                if (hurt(p, le, d("crush_damage", 5))) {
                    any = true;
                    damageArmor(le, d("crush_armor_percent", 5));
                    Util.knock(le, p.getLocation(), 0.8, 0.3);
                }
            }
            if (!any) {
                say(p, "&cНет целей в конусе");
                return false;
            }
            for (double r = 1.5; r <= radius; r += 1.5) {
                Location c = p.getLocation().add(dir.clone().multiply(r)).add(0, 0.3, 0);
                fx().particle(c, "CRIT", 10, r / 3, 0.2, r / 3, 0.1);
                fx().particle(c, "SMOKE", 4, r / 3, 0.2, r / 3, 0.02);
            }
            fx().sound(p.getLocation(), "entity.iron_golem.damage", 1f, 0.5f);
            return true;
        }

        /** Снижает прочность брони на процент от максимума, но никогда не ломает её. */
        private void damageArmor(LivingEntity target, double percent) {
            EntityEquipment eq = target.getEquipment();
            if (eq == null) {
                return;
            }
            ItemStack[] armor = eq.getArmorContents();
            for (int k = 0; k < armor.length; k++) {
                ItemStack it = armor[k];
                if (it == null || it.getType().getMaxDurability() <= 0) {
                    continue;
                }
                if (it.getItemMeta() instanceof Damageable dm && !dm.isUnbreakable()) {
                    int max = it.getType().getMaxDurability();
                    int inc = Math.max(1, (int) (max * percent / 100.0));
                    dm.setDamage(Math.min(max - 2, dm.getDamage() + inc));
                    it.setItemMeta(dm);
                    armor[k] = it;
                }
            }
            eq.setArmorContents(armor);
        }
    }
}
