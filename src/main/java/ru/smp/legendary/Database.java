package ru.smp.legendary;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/** Простая синхронная обёртка над SQLite (драйвер входит в состав Spigot). */
public final class Database {

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlAction {
        void run() throws Exception;
    }

    private static final String[] SCHEMA = {
            "CREATE TABLE IF NOT EXISTS hearts(uuid TEXT PRIMARY KEY, hearts INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS progress(player TEXT NOT NULL, weapon TEXT NOT NULL, victim TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(player, weapon, victim))",
            "CREATE TABLE IF NOT EXISTS cooldowns(player TEXT NOT NULL, ckey TEXT NOT NULL, ready_at INTEGER NOT NULL, PRIMARY KEY(player, ckey))",
            "CREATE TABLE IF NOT EXISTS charges(player TEXT NOT NULL, ckey TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY(player, ckey))",
            "CREATE TABLE IF NOT EXISTS ban_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, killer_uuid TEXT, killer_name TEXT, victim_uuid TEXT, victim_name TEXT, world TEXT, x REAL, y REAL, z REAL, reason TEXT, weapon TEXT, unbanned INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS altars(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, UNIQUE(world, x, y, z))",
            "CREATE TABLE IF NOT EXISTS rituals(altar_id INTEGER PRIMARY KEY, weapon TEXT NOT NULL, owner TEXT NOT NULL, started_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, reserved TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS craft_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, player TEXT NOT NULL, weapon TEXT NOT NULL, instance TEXT NOT NULL, altar_id INTEGER)",
            "CREATE TABLE IF NOT EXISTS unique_items(weapon TEXT PRIMARY KEY, state TEXT NOT NULL, owner TEXT, ts INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS dungeons(id INTEGER PRIMARY KEY AUTOINCREMENT, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, UNIQUE(world, x, z))",
            "CREATE TABLE IF NOT EXISTS barriers(id INTEGER PRIMARY KEY AUTOINCREMENT, world TEXT NOT NULL, mode TEXT NOT NULL, x1 INTEGER, z1 INTEGER, x2 INTEGER, z2 INTEGER, y1 INTEGER, y2 INTEGER, ts INTEGER, actor TEXT)",
            "CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pending_rewards(id TEXT PRIMARY KEY, owner TEXT NOT NULL, weapon TEXT NOT NULL, ts INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS instances(id TEXT PRIMARY KEY, weapon TEXT NOT NULL, created INTEGER NOT NULL, voided INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS admin_log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, details TEXT)"
    };

    private final LegendaryPlugin plugin;
    private Connection conn;

    public Database(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void open() throws Exception {
        plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        File file = new File(plugin.getDataFolder(), "data.db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            for (String sql : SCHEMA) {
                s.execute(sql);
            }
        }
    }

    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Выполнить запрос, исключения пробрасываются (для транзакций). */
    public synchronized int exec(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Выполнить запрос, ошибки пишутся в лог. Возвращает -1 при ошибке. */
    public synchronized int update(String sql, Object... params) {
        try {
            return exec(sql, params);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "SQL error: " + sql, e);
            return -1;
        }
    }

    /** INSERT с возвратом последнего rowid (или -1). */
    public synchronized long insert(String sql, Object... params) {
        try {
            exec(sql, params);
            try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "SQL error: " + sql, e);
            return -1;
        }
    }

    public synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "SQL error: " + sql, e);
        }
        return out;
    }

    public synchronized boolean transaction(SqlAction action) {
        try {
            conn.setAutoCommit(false);
            action.run();
            conn.commit();
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Transaction failed", e);
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            return false;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }
}
