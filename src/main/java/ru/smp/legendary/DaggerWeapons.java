package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Кинжалы и копьё: Багровый Обет, Фантом, Узник. */
public final class DaggerWeapons {

    private DaggerWeapons() {
    }

    // =====================================================================
    //  Багровый Обет (кинжал крови, оружие №10)
    // =====================================================================
    public static final class BloodOath extends Weapon {

        BloodOath(LegendaryPlugin p) {
            super(p, "blood_oath", "&4Багровый Обет", Material.IRON_SWORD, "Алтарь кровавой клятвы", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Жажда крови"; }
        @Override public String secondaryName() { return "Полёт нетопырей"; }
        @Override public String blurb() { return "Пьёт кровь врагов и уносит владельца стаей летучих мышей."; }

        /** Жажда крови: короткое время удары лечат владельца. */
        @Override
        public boolean primary(Player p) {
            int sec = i("bloodthirst_seconds", 8);
            buffs().set(p.getUniqueId(), "bloodthirst", sec * 1000L, null);
            // кровавый надрез: бьёт ближних врагов и отдаёт владельцу часть крови
            int hit = nova(p, p.getLocation(), d("bloodthirst_radius", 4), d("bloodthirst_damage", 4));
            if (hit > 0) {
                Util.heal(p, Math.min(hit * d("bloodthirst_damage", 4) * d("bloodthirst_heal_ratio", 0.5), d("bloodthirst_heal_cap", 6)));
            }
            fx().ring(p.getLocation().add(0, 0.2, 0), d("bloodthirst_radius", 4), 30, "CRIMSON_SPORE");
            fx().particle(p.getLocation().add(0, 1, 0), "CRIMSON_SPORE", 30, 0.4, 0.7, 0.4, 0.05);
            fx().sound(p.getLocation(), "entity.witch.drink", 0.8f, 0.6f);
            return true;
        }

        @Override
        public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
            if (!buffs().active(attacker.getUniqueId(), "bloodthirst")) {
                return;
            }
            double heal = Math.min(e.getFinalDamage() * d("bloodthirst_ratio", 0.3), d("bloodthirst_cap", 4));
            Util.heal(attacker, heal);
            fx().particle(attacker.getLocation().add(0, 1.2, 0), "HEART", 3, 0.3, 0.3, 0.3, 0);
        }

        /**
         * Полёт нетопырей: владелец на несколько секунд взлетает в ту сторону, куда смотрел, в окружении
         * стаи летучих мышей, и всё это время неуязвим. Вместо неуязвимости через флаг игрока (он мог бы
         * остаться навсегда при вылете) отменяем входящий урон, пока действует метка «batform».
         */
        @Override
        public boolean secondary(Player p) {
            final int ticks = (int) Math.round(d("bat_seconds", 3.5) * 20);
            final double speed = d("bat_speed", 0.9);
            final int count = Math.max(0, Math.min(i("bat_count", 6), 10));
            final Vector dir = p.getLocation().getDirection().clone();
            if (dir.lengthSquared() < 1.0E-4) {
                return false;
            }
            dir.normalize();
            final UUID uid = p.getUniqueId();
            buffs().set(uid, "batform", ticks * 50L + 500L, null);

            final List<Bat> bats = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                Bat bat = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Bat.class, b -> {
                    b.setAI(false);
                    b.setSilent(true);
                    b.setInvulnerable(true);
                    b.setPersistent(false);
                    b.setCollidable(false);
                    b.setAwake(true);
                });
                plugin.decoys.add(bat.getUniqueId());
                bats.add(bat);
            }
            fx().sound(p.getLocation(), "entity.bat.takeoff", 1.2f, 0.8f);
            fx().particle(p.getLocation().add(0, 1, 0), "CRIMSON_SPORE", 40, 0.6, 0.8, 0.6, 0.1);

            final java.util.Set<UUID> struck = new java.util.HashSet<>();
            final double batDmg = d("bat_damage", 4);
            new BukkitRunnable() {
                int t = 0;

                private void end() {
                    for (Bat b : bats) {
                        plugin.decoys.remove(b.getUniqueId());
                        fx().particle(b.getLocation(), "SMOKE", 6, 0.2, 0.2, 0.2, 0.02);
                        b.remove();
                    }
                    buffs().clear(uid, "batform");
                    if (p.isOnline() && !p.isDead()) {
                        p.setFallDistance(0f);
                        Util.addEffect(p, "slow_falling", 80, 0);
                        fx().sound(p.getLocation(), "entity.bat.death", 0.8f, 1.2f);
                    }
                }

                @Override
                public void run() {
                    if (!p.isOnline() || p.isDead() || t >= ticks) {
                        end();
                        cancel();
                        return;
                    }
                    p.setVelocity(dir.clone().multiply(speed));
                    p.setFallDistance(0f);
                    Location c = p.getLocation().add(0, 1, 0);
                    // стая кусает всех, кого задела по пути (каждого один раз)
                    for (LivingEntity le : enemies(p, c, 3.0)) {
                        if (struck.add(le.getUniqueId())) {
                            hurt(p, le, batDmg);
                            fx().particle(le.getLocation().add(0, 1, 0), "CRIMSON_SPORE", 12, 0.3, 0.5, 0.3, 0.05);
                        }
                    }
                    for (int k = 0; k < bats.size(); k++) {
                        double ang = t * 0.55 + k * (2 * Math.PI / bats.size());
                        Location at = c.clone().add(Math.cos(ang) * 1.3, 0.2 + Math.sin(t * 0.4 + k) * 0.5, Math.sin(ang) * 1.3);
                        bats.get(k).teleport(at);
                    }
                    if (t % 2 == 0) {
                        fx().particle(c, "CRIMSON_SPORE", 5, 0.3, 0.4, 0.3, 0.02);
                    }
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 1L);
            return true;
        }

        @Override
        public void onVictim(Player victim, EntityDamageEvent e) {
            if (!buffs().active(victim.getUniqueId(), "batform")) {
                return;
            }
            switch (e.getCause()) {
                case VOID, KILL, SUICIDE, WORLD_BORDER -> {
                }
                default -> e.setCancelled(true);
            }
        }
    }

    // =====================================================================
    //  Фантом
    // =====================================================================
    public static final class Phantom extends Weapon {

        Phantom(LegendaryPlugin p) {
            super(p, "phantom", "&fФантом", Material.IRON_SWORD, "Алтарь забытого духа", Cat.NORMAL);
        }

        @Override public String primaryName() { return "Призрачный рывок"; }
        @Override public String secondaryName() { return "Двойник"; }
        @Override public String blurb() { return "Скорость, иллюзии и пустота."; }

        @Override
        public boolean primary(Player p) {
            final double dmg = d("dash_damage", 6);
            dash(p, d("dash_speed", 1.6), i("dash_ticks", 6), 1.5, "SCULK_SOUL", le -> {
                if (hurt(p, le, dmg)) {
                    fx().particle(le.getLocation().add(0, 1, 0), "SCULK_SOUL", 15, 0.3, 0.6, 0.3, 0.05);
                }
            });
            fx().sound(p.getLocation(), "entity.phantom.swoop", 1f, 1.3f);
            return true;
        }

        @Override
        public boolean secondary(Player p) {
            final int seconds = i("decoy_seconds", 8);
            final ArmorStand stand = p.getWorld().spawn(p.getLocation(), ArmorStand.class, st -> {
                st.setArms(true);
                st.setBasePlate(false);
                st.setInvulnerable(true);
                st.setSilent(true);
                st.setCustomName(p.getName());
                st.setCustomNameVisible(true);
                EntityEquipment eq = st.getEquipment();
                if (eq != null) {
                    ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                    if (skull.getItemMeta() instanceof SkullMeta sm) {
                        sm.setOwningPlayer(p);
                        skull.setItemMeta(sm);
                    }
                    eq.setHelmet(skull);
                    eq.setChestplate(p.getInventory().getChestplate());
                    eq.setLeggings(p.getInventory().getLeggings());
                    eq.setBoots(p.getInventory().getBoots());
                    eq.setItemInMainHand(p.getInventory().getItemInMainHand().clone());
                }
            });
            plugin.decoys.add(stand.getUniqueId());
            fx().particle(p.getLocation().add(0, 1, 0), "SCULK_SOUL", 25, 0.4, 0.8, 0.4, 0.05);
            fx().sound(p.getLocation(), "entity.allay.item_thrown", 1f, 0.6f);
            Util.addEffect(p, "speed", 40, 1);
            // вопль призрака при создании двойника
            nova(p, p.getLocation(), d("decoy_radius", 5), d("decoy_damage", 4));
            fx().ring(p.getLocation().add(0, 0.2, 0), d("decoy_radius", 5), 36, "SCULK_SOUL");
            new BukkitRunnable() {
                int t = 0;

                @Override
                public void run() {
                    if (stand.isDead() || t >= seconds * 4) {
                        // двойник рассыпается взрывом душ
                        nova(p, stand.getLocation(), d("decoy_burst_radius", 4), d("decoy_burst_damage", 4));
                        fx().particle(stand.getLocation().add(0, 1, 0), "SCULK_SOUL", 20, 0.3, 0.7, 0.3, 0.05);
                        plugin.decoys.remove(stand.getUniqueId());
                        stand.remove();
                        cancel();
                        return;
                    }
                    fx().particle(stand.getLocation().add(0, 1, 0), "SCULK_SOUL", 2, 0.3, 0.7, 0.3, 0.01);
                    t++;
                }
            }.runTaskTimer(plugin, 0L, 5L);
            return true;
        }
    }

    // =====================================================================
    //  Узник (копьё)
    // =====================================================================
    public static final class Prisoner extends Weapon {

        Prisoner(LegendaryPlugin p) {
            super(p, "prisoner", "&8Узник", spearMaterial(), "Алтарь вечного заточения", Cat.SPECIAL);
        }

        static Material spearMaterial() {
            Material m = Material.matchMaterial("NETHERITE_SPEAR");
            return m != null ? m : Material.TRIDENT;
        }

        @Override public String primaryName() { return "Цепи заточения"; }
        @Override public String secondaryName() { return "Казнь стража"; }
        @Override public String blurb() { return "Заковывает цель и настигает её."; }

        /** Взводит цепи: следующий удар обездвиживает цель и помечает её для «Казни стража». */
        @Override
        public boolean primary(Player p) {
            buffs().set(p.getUniqueId(), "chains", i("chains_arm_seconds", 8) * 1000L, null);
            // цепи хлещут по врагам рядом и сковывают их ненадолго
            for (LivingEntity le : enemies(p, p.getLocation(), d("chains_radius", 6))) {
                if (hurt(p, le, d("chains_damage", 4))) {
                    Util.addEffect(le, "slowness", 30, 2);
                    fx().line(p.getLocation().add(0, 1, 0), le.getLocation().add(0, 1, 0), "SOUL_FIRE_FLAME", 0.5);
                }
            }
            fx().particle(p.getLocation().add(0, 1, 0), "SOUL_FIRE_FLAME", 20, 0.4, 0.7, 0.4, 0.03);
            fx().sound(p.getLocation(), "block.chain.place", 1f, 0.8f);
            say(p, "&8Цепи взведены: следующий удар свяжет цель");
            return true;
        }

        @Override
        public void onHit(Player attacker, LivingEntity victim, EntityDamageByEntityEvent e) {
            if (!buffs().active(attacker.getUniqueId(), "chains")) {
                return;
            }
            buffs().clear(attacker.getUniqueId(), "chains");
            int ticks = i("chains_root_ticks", 40);
            Util.addEffect(victim, "slowness", ticks, 6);
            buffs().set(attacker.getUniqueId(), "mark", i("mark_seconds", 15) * 1000L, victim.getUniqueId());
            fx().particle(victim.getLocation().add(0, 1, 0), "SOUL_FIRE_FLAME", 30, 0.4, 0.8, 0.4, 0.03);
            fx().ring(victim.getLocation(), 1.0, 16, "ASH");
            fx().sound(victim.getLocation(), "block.chain.hit", 1f, 0.7f);
        }

        @Override
        public boolean secondary(Player p) {
            Buffs.Entry en = buffs().get(p.getUniqueId(), "mark");
            Entity te = en == null || !(en.data instanceof UUID u) ? null : Bukkit.getEntity(u);
            if (!(te instanceof LivingEntity t) || t.isDead() || !Util.isEnemy(p, t)) {
                say(p, "&cНет отмеченной цели");
                return false;
            }
            if (!t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > d("execute_range", 16)) {
                say(p, "&cЦель слишком далеко");
                return false;
            }
            Location tl = t.getLocation();
            org.bukkit.util.Vector back = tl.getDirection().clone().setY(0);
            if (back.lengthSquared() < 1.0E-4) {
                back = new org.bukkit.util.Vector(1, 0, 0);
            }
            back.normalize().multiply(-1.6);
            Location dest = tl.clone().add(back);
            if (!Util.safeStand(dest)) {
                dest = tl.clone().add(back.multiply(-1));
                if (!Util.safeStand(dest)) {
                    say(p, "&cНет безопасного места рядом с целью");
                    return false;
                }
            }
            dest.setDirection(tl.toVector().subtract(dest.toVector()));
            if (!Util.canHarm(p, t)) {
                return false;
            }
            fx().line(p.getLocation().add(0, 1, 0), dest.clone().add(0, 1, 0), "SOUL_FIRE_FLAME", 0.5);
            p.teleport(dest);
            hurt(p, t, d("execute_damage", 8));
            buffs().clear(p.getUniqueId(), "mark");
            fx().particle(tl.clone().add(0, 1, 0), "CRIT", 30, 0.4, 0.7, 0.4, 0.3);
            fx().sound(tl, "entity.player.attack.crit", 1f, 0.6f);
            return true;
        }
    }
}
