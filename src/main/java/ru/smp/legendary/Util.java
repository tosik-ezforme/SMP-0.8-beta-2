package ru.smp.legendary;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Util {

    private Util() {
    }

    private static LegendaryPlugin plugin() {
        return LegendaryPlugin.inst();
    }

    // ------------------------------------------------------------ text

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String strip(String s) {
        return ChatColor.stripColor(color(s));
    }

    /** Секунды в вид «14:59» или «45 с». */
    public static String fmtTime(long seconds) {
        seconds = Math.max(0, seconds);
        if (seconds >= 60) {
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        }
        return seconds + " с";
    }

    public static void actionbar(Player p, String msg) {
        try {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(msg)));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ effects / attributes

    public static PotionEffectType effect(String key) {
        return Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    public static void addEffect(LivingEntity e, String key, int ticks, int amplifier) {
        PotionEffectType t = effect(key);
        if (t != null) {
            if (e instanceof Player) {
                // все эффекты способностей идут через этот метод: помечаем их, чтобы ведро еды их не снимало
                plugin().markLegendaryEffect(e.getUniqueId(), key, ticks);
            }
            e.addPotionEffect(new PotionEffect(t, ticks, amplifier, false, true, true));
        }
    }

    public static void removeEffect(LivingEntity e, String key) {
        PotionEffectType t = effect(key);
        if (t != null) {
            e.removePotionEffect(t);
        }
    }

    public static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    public static void heal(LivingEntity e, double amount) {
        e.setHealth(Math.min(maxHealth(e), e.getHealth() + Math.max(0, amount)));
    }

    // ------------------------------------------------------------ worlds / protection / targets

    public static boolean worldAllowed(Player p) {
        String w = p.getWorld().getName();
        if (plugin().getConfig().getStringList("general.disabled_worlds").contains(w)) {
            return false;
        }
        List<String> enabled = plugin().getConfig().getStringList("general.enabled_worlds");
        return enabled.isEmpty() || enabled.contains(w);
    }

    public static boolean isAlly(Player a, Player b) {
        if (Bukkit.getScoreboardManager() == null) {
            return false;
        }
        Team ta = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(a.getName());
        Team tb = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(b.getName());
        return ta != null && ta.equals(tb);
    }

    /** Подходит ли сущность как цель способности (без проверки приватов). */
    public static boolean isEnemy(Player owner, Entity e) {
        if (!(e instanceof LivingEntity le) || e.equals(owner) || e instanceof ArmorStand || le.isDead()
                || plugin().decoys.contains(e.getUniqueId())) {
            return false;
        }
        if (e instanceof Player p) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                return false;
            }
            return !isAlly(owner, p);
        }
        return plugin().getConfig().getBoolean("general.affect_mobs", false);
    }

    /** Пробное событие урона, чтобы уважать WorldGuard и другие плагины защиты. */
    @SuppressWarnings("deprecation")
    public static boolean canHarm(Player attacker, LivingEntity victim) {
        if (!attacker.getWorld().equals(victim.getWorld())) {
            return false;
        }
        if (victim instanceof Player && !victim.getWorld().getPVP()) {
            return false;
        }
        if (!plugin().getConfig().getBoolean("general.respect_protection", true)) {
            return true;
        }
        plugin().fakeDamage = true;
        try {
            EntityDamageByEntityEvent ev = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
            Bukkit.getPluginManager().callEvent(ev);
            return !ev.isCancelled();
        } catch (Throwable t) {
            return true;
        } finally {
            plugin().fakeDamage = false;
        }
    }

    public static List<LivingEntity> enemies(Player owner, Location center, double radius) {
        World w = center.getWorld();
        List<LivingEntity> out = new ArrayList<>();
        if (w == null) {
            return out;
        }
        int max = plugin().getConfig().getInt("general.max_targets", 8);
        for (Entity e : w.getNearbyEntities(center, radius, radius, radius)) {
            if (!isEnemy(owner, e)) {
                continue;
            }
            LivingEntity le = (LivingEntity) e;
            if (le.getLocation().distance(center) > radius + 0.75) {
                continue;
            }
            out.add(le);
        }
        out.sort(Comparator.comparingDouble(le -> le.getLocation().distanceSquared(center)));
        if (out.size() > max) {
            return new ArrayList<>(out.subList(0, max));
        }
        return out;
    }

    // ------------------------------------------------------------ aiming

    public static LivingEntity targetInSight(Player p, double range) {
        Location eye = p.getEyeLocation();
        World w = p.getWorld();
        RayTraceResult r = w.rayTraceEntities(eye, eye.getDirection(), range, 0.5, en -> en instanceof LivingEntity && !en.equals(p) && !(en instanceof ArmorStand));
        if (r == null || !(r.getHitEntity() instanceof LivingEntity le)) {
            return null;
        }
        double dist = r.getHitPosition().distance(eye.toVector());
        RayTraceResult b = w.rayTraceBlocks(eye, eye.getDirection(), dist, FluidCollisionMode.NEVER, true);
        if (b != null && b.getHitPosition().distance(eye.toVector()) < dist - 0.3) {
            return null;
        }
        return le;
    }

    /** Точка попадания луча взгляда в блок (или null, если блока в зоне досягаемости нет). */
    public static Location aimPoint(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult b = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (b == null) {
            return null;
        }
        return b.getHitPosition().toLocation(p.getWorld());
    }

    // ------------------------------------------------------------ movement

    public static void knock(LivingEntity e, Location from, double power, double up) {
        Vector v = e.getLocation().toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 1.0E-4) {
            v = new Vector(0, 0, 0);
        } else {
            v.normalize().multiply(power);
        }
        v.setY(up);
        e.setVelocity(v);
    }

    public static boolean safeStand(Location l) {
        World w = l.getWorld();
        if (w == null) {
            return false;
        }
        int y = l.getBlockY();
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 2) {
            return false;
        }
        Block feet = l.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) {
            return false;
        }
        Material gm = ground.getType();
        if (!gm.isSolid() || gm == Material.MAGMA_BLOCK || gm == Material.CACTUS
                || gm == Material.CAMPFIRE || gm == Material.SOUL_CAMPFIRE) {
            return false;
        }
        Material fm = feet.getType();
        if (fm == Material.FIRE || fm == Material.SOUL_FIRE || fm == Material.WITHER_ROSE
                || fm == Material.POWDER_SNOW || fm == Material.COBWEB) {
            return false;
        }
        BlockFace[] around = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN};
        for (BlockFace f : around) {
            if (feet.getRelative(f).getType() == Material.LAVA) {
                return false;
            }
        }
        return w.getWorldBorder().isInside(l);
    }
}
