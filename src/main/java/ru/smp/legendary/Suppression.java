package ru.smp.legendary;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Зоны подавления способностей («Последнее предупреждение» Молота бана). */
public final class Suppression {

    private static final class Zone {
        final UUID owner;
        final Location center;
        final double radius;
        final long until;

        Zone(UUID owner, Location center, double radius, long until) {
            this.owner = owner;
            this.center = center;
            this.radius = radius;
            this.until = until;
        }
    }

    private final LegendaryPlugin plugin;
    private final List<Zone> zones = new ArrayList<>();

    public Suppression(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void add(Player owner, Location center, double radius, int seconds) {
        // одна зона на владельца — защита от накопления
        zones.removeIf(z -> z.owner.equals(owner.getUniqueId()));
        final Zone zone = new Zone(owner.getUniqueId(), center.clone(), radius, System.currentTimeMillis() + seconds * 1000L);
        zones.add(zone);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() >= zone.until || !zones.contains(zone)) {
                    zones.remove(zone);
                    cancel();
                    return;
                }
                plugin.fx().ring(zone.center, zone.radius, 48, "SOUL_FIRE_FLAME");
                plugin.fx().ring(zone.center, zone.radius * 0.6, 28, "SMOKE");
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    public boolean isSuppressed(Player p) {
        long now = System.currentTimeMillis();
        zones.removeIf(z -> z.until <= now);
        for (Zone z : zones) {
            if (z.owner.equals(p.getUniqueId())) {
                continue;
            }
            if (z.center.getWorld() != null && z.center.getWorld().equals(p.getWorld())
                    && z.center.distanceSquared(p.getLocation()) <= z.radius * z.radius) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        zones.clear();
    }
}
