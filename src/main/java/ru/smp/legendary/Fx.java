package ru.smp.legendary;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;

/** Безопасные частицы и звуки: неверное имя из конфига/версии никогда не роняет плагин. */
public final class Fx {

    private final LegendaryPlugin plugin;
    private final Set<String> warned = new HashSet<>();

    public Fx(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private void warn(String name) {
        if (warned.add(name)) {
            plugin.getLogger().warning("Particle '" + name + "' is not available on this server version.");
        }
    }

    public void particle(Location l, String name, int count, double dx, double dy, double dz, double speed) {
        particle(l, name, count, dx, dy, dz, speed, null);
    }

    public void particle(Location l, String name, int count, double dx, double dy, double dz, double speed, Object data) {
        if (l == null || l.getWorld() == null) {
            return;
        }
        Particle p;
        try {
            p = Particle.valueOf(name);
        } catch (IllegalArgumentException ex) {
            warn(name);
            return;
        }
        int max = plugin.getConfig().getInt("general.max_particles", 150);
        int c = Math.max(0, Math.min(count, max));
        try {
            if (data == null) {
                l.getWorld().spawnParticle(p, l, c, dx, dy, dz, speed);
            } else {
                l.getWorld().spawnParticle(p, l, c, dx, dy, dz, speed, data);
            }
        } catch (Exception ex) {
            warn(name);
        }
    }

    public void dust(Location l, Color color, float size, int count, double spread) {
        particle(l, "DUST", count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
    }

    public void ring(Location center, double radius, int points, String particle) {
        ring(center, radius, points, particle, null);
    }

    public void ring(Location center, double radius, int points, String particle, Object data) {
        int n = Math.max(6, Math.min(points, 80));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            Location p = center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
            particle(p, particle, 1, 0, 0, 0, 0, data);
        }
    }

    public void line(Location a, Location b, String particle, double step) {
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return;
        }
        Vector v = b.toVector().subtract(a.toVector());
        double len = v.length();
        if (len < 0.01 || len > 64) {
            return;
        }
        v.normalize().multiply(step);
        Location cur = a.clone();
        int n = (int) Math.min(80, len / step);
        for (int i = 0; i < n; i++) {
            cur.add(v);
            particle(cur, particle, 1, 0, 0, 0, 0);
        }
    }

    public void sound(Location l, String key, float volume, float pitch) {
        if (l == null || l.getWorld() == null) {
            return;
        }
        try {
            l.getWorld().playSound(l, key, volume, pitch);
        } catch (Exception ignored) {
        }
    }
}
