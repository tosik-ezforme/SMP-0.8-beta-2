package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Применение постоянных сердец и выдача ожидающих наград ритуалов. */
public final class ConnectionListener implements Listener {

    private final LegendaryPlugin plugin;

    public ConnectionListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                plugin.hearts().apply(p);
                plugin.hearts().enforce(p);
                plugin.rituals().claimPending(p);
            }
        }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                plugin.hearts().apply(p);
                plugin.hearts().enforce(p);
            }
        }, 2L);
    }
}
