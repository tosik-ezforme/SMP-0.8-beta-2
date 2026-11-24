package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Постоянное меню статистики справа на экране: убийства, смерти, время выживания (и сердца).
 * У каждого игрока своя доска, строки обновляются через команды-префиксы без мерцания.
 */
public final class StatsBoard implements Listener {

    private static final int LINES = 6;
    private final LegendaryPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();

    public StatsBoard(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("stats_board.enabled", true);
    }

    public void start() {
        for (String s : plugin.db().query("SELECT key FROM settings WHERE key LIKE 'statsoff:%'", r -> r.getString(1))) {
            try {
                hidden.add(UUID.fromString(s.substring(9)));
            } catch (IllegalArgumentException ignored) {
                // повреждённая запись
            }
        }
        long period = Math.max(5, plugin.getConfig().getInt("stats_board.update_ticks", 20));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                update(p);
            }
        }, 20L, period);
    }

    public void shutdown() {
        var mgr = Bukkit.getScoreboardManager();
        for (UUID id : new HashSet<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && mgr != null) {
                p.setScoreboard(mgr.getMainScoreboard());
            }
        }
        boards.clear();
    }

    public boolean toggle(Player p) {
        boolean nowOn;
        if (hidden.remove(p.getUniqueId())) {
            plugin.db().update("DELETE FROM settings WHERE key=?", "statsoff:" + p.getUniqueId());
            nowOn = true;
        } else {
            hidden.add(p.getUniqueId());
            plugin.db().update("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", "statsoff:" + p.getUniqueId(), "1");
            nowOn = false;
        }
        update(p);
        return nowOn;
    }

    private static String time(long ticks) {
        long s = ticks / 20;
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60;
        if (d > 0) {
            return d + "д " + h + "ч " + m + "м";
        }
        if (h > 0) {
            return h + "ч " + m + "м";
        }
        return m + "м " + (s % 60) + "с";
    }

    private Scoreboard create() {
        var mgr = Bukkit.getScoreboardManager();
        if (mgr == null) {
            return null;
        }
        Scoreboard sb = mgr.getNewScoreboard();
        Objective o = sb.registerNewObjective("lw_stats", Criteria.DUMMY,
                Util.color(plugin.getConfig().getString("stats_board.title", "&6&l⚔ СТАТИСТИКА ⚔")));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int i = 0; i < LINES; i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry);
            org.bukkit.scoreboard.Score sc = o.getScore(entry);
            sc.setScore(LINES - i);
            blank(sc, org.bukkit.scoreboard.Score.class);
        }
        blank(o, Objective.class);
        return sb;
    }

    /**
     * Прячет красные цифры справа (формат числа «пусто»). Работает на сборках, где в API есть numberFormat
     * (Paper и новые Spigot); если метода нет — цифры остаются, это не ошибка.
     */
    private static boolean reported = false;

    /**
     * Прячет красные цифры справа через Paper API (NumberFormat.blank). Вызов идёт через рефлексию,
     * чтобы плагин собирался и на чистом Spigot. Результат один раз пишется в консоль.
     */
    private static void blank(Object target, Class<?> api) {
        String err = null;
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            java.lang.reflect.Method m = api.getMethod("numberFormat", nf);
            m.invoke(target, blank);
        } catch (ClassNotFoundException ex) {
            err = "это не Paper (нет io.papermc.paper.scoreboard.numbers.NumberFormat)";
        } catch (Throwable ex) {
            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
            err = c.toString();
        }
        if (!reported) {
            reported = true;
            if (err == null) {
                LegendaryPlugin.inst().getLogger().info("Табло: цифры справа скрыты (Paper NumberFormat.blank).");
            } else {
                LegendaryPlugin.inst().getLogger().warning("Табло: не удалось скрыть цифры справа — " + err);
            }
        }
    }

    private void line(Scoreboard sb, int i, String text) {
        Team t = sb.getTeam("l" + i);
        if (t != null) {
            t.setPrefix(Util.color(text));
        }
    }

    public void update(Player p) {
        if (!p.isOnline()) {
            return;
        }
        var mgr = Bukkit.getScoreboardManager();
        if (mgr == null) {
            return;
        }
        if (!enabled() || hidden.contains(p.getUniqueId())) {
            if (boards.remove(p.getUniqueId()) != null) {
                p.setScoreboard(mgr.getMainScoreboard());
            }
            return;
        }
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) {
            sb = create();
            if (sb == null) {
                return;
            }
            boards.put(p.getUniqueId(), sb);
        }
        if (p.getScoreboard() != sb) {
            p.setScoreboard(sb);
        }
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        long alive = p.getStatistic(Statistic.TIME_SINCE_DEATH);
        line(sb, 0, "&8&m          ");
        line(sb, 1, "&c⚔ Убийства: &f" + kills);
        line(sb, 2, "&4☠ Смерти: &f" + deaths);
        line(sb, 3, "&a⌛ Выживание: &f" + time(alive));
        if (plugin.getConfig().getBoolean("stats_board.show_hearts", true)) {
            line(sb, 4, "&d❤ Сердца: &f" + plugin.hearts().get(p.getUniqueId()));
        } else {
            line(sb, 4, "");
        }
        line(sb, 5, "&8&m          ");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }
}
