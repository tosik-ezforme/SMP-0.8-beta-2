package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Отправляет игрокам ресурспак с иконками легендарок (config resource_pack.*). */
public final class PackListener implements Listener {

    private final LegendaryPlugin plugin;

    public PackListener(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private static byte[] hex(String s) {
        if (s == null || s.length() != 40) {
            return null;
        }
        byte[] out = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("resource_pack.enabled", false)
                || !plugin.getConfig().getBoolean("resource_pack.send_on_join", true)) {
            return;
        }
        String url = plugin.getConfig().getString("resource_pack.url", "");
        if (url == null || url.isBlank()) {
            return;
        }
        final Player p = e.getPlayer();
        final byte[] hash = hex(plugin.getConfig().getString("resource_pack.sha1", ""));
        final String prompt = Util.color(plugin.getConfig().getString("resource_pack.prompt", "Иконки легендарных предметов"));
        final boolean force = plugin.getConfig().getBoolean("resource_pack.required", false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                p.setResourcePack(url, hash, prompt, force);
            }
        }, 40L);
    }
}
