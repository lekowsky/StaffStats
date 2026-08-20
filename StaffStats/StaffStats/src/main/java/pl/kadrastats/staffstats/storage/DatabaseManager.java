package pl.kadrastats.staffstats.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private final ExecutorService asyncPool;
    private final boolean asyncWrites;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.asyncPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StaffStats-DB");
            t.setDaemon(true);
            return t;
        });
        this.asyncWrites = plugin.getConfig().getBoolean("storage.async-writes", true);
    }

    public void init() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "staff_activity.db");
        if (!dbFile.getParentFile().exists()) dbFile.getParentFile().mkdirs();
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        // SQLite driver – shaded, bez relokacji
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite driver not found! Upewnij się że JAR jest shaded.");
            throw new SQLException("SQLite driver missing", e);
        }
        connection = DriverManager.getConnection(url);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL;");
            st.executeUpdate("PRAGMA synchronous=NORMAL;");
        }
        createTables();
        plugin.getLogger().info("SQLite connected: " + dbFile.getName());
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS staff_stats (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    group_name TEXT,
                    total_playtime_ms INTEGER NOT NULL DEFAULT 0,
                    total_afk_ms INTEGER NOT NULL DEFAULT 0,
                    last_login INTEGER DEFAULT 0,
                    last_logout INTEGER DEFAULT 0,
                    session_count INTEGER NOT NULL DEFAULT 0,
                    first_seen INTEGER DEFAULT 0,
                    updated_at INTEGER NOT NULL
                );
                """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_group ON staff_stats(group_name);");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playtime ON staff_stats(total_playtime_ms DESC);");

            // NEW: szczegółowe sesje – do dziennych raportów
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS staff_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    name TEXT NOT NULL,
                    group_name TEXT,
                    login_ts INTEGER NOT NULL,
                    logout_ts INTEGER NOT NULL,
                    playtime_ms INTEGER NOT NULL,
                    afk_ms INTEGER NOT NULL
                );
                """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_uuid ON staff_sessions(uuid);");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_time ON staff_sessions(logout_ts DESC);");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_login ON staff_sessions(login_ts DESC);");
        }
    }

    public void upsertSession(UUID uuid, String name, String group,
                              long playtimeAdd, long afkAdd,
                              long loginTs, long logoutTs, boolean incrementSession) {
        Runnable task = () -> {
            try {
                String sql = """
                    INSERT INTO staff_stats (uuid, name, group_name, total_playtime_ms, total_afk_ms, last_login, last_logout, session_count, first_seen, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        name = excluded.name,
                        group_name = COALESCE(excluded.group_name, group_name),
                        total_playtime_ms = total_playtime_ms + excluded.total_playtime_ms,
                        total_afk_ms = total_afk_ms + excluded.total_afk_ms,
                        last_login = CASE WHEN excluded.last_login > last_login THEN excluded.last_login ELSE last_login END,
                        last_logout = CASE WHEN excluded.last_logout > last_logout THEN excluded.last_logout ELSE last_logout END,
                        session_count = session_count + ?,
                        updated_at = excluded.updated_at
                    ;
                    """;
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, group);
                    ps.setLong(4, playtimeAdd);
                    ps.setLong(5, afkAdd);
                    ps.setLong(6, loginTs);
                    ps.setLong(7, logoutTs);
                    ps.setInt(8, incrementSession ? 1 : 0);
                    ps.setLong(9, now);
                    ps.setLong(10, now);
                    ps.setInt(11, incrementSession ? 1 : 0);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "DB upsert failed " + uuid, e);
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    // Zapis pojedynczej sesji do tabeli staff_sessions (do raportów dziennych)
    public void insertSessionRecord(UUID uuid, String name, String group, long loginTs, long logoutTs, long playtimeMs, long afkMs) {
        if (playtimeMs <= 0) return;
        Runnable task = () -> {
            String sql = "INSERT INTO staff_sessions (uuid, name, group_name, login_ts, logout_ts, playtime_ms, afk_ms) VALUES (?,?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, group);
                ps.setLong(4, loginTs);
                ps.setLong(5, logoutTs);
                ps.setLong(6, playtimeMs);
                ps.setLong(7, afkMs);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "insertSessionRecord failed", e);
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    public void updateLogin(UUID uuid, String name, String group, long loginTs) {
        upsertSession(uuid, name, group, 0, 0, loginTs, 0, false);
    }

    public StaffRecord getRecord(UUID uuid) {
        String sql = "SELECT * FROM staff_stats WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return map(rs); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getRecord", e); }
        return null;
    }

    public CompletableFuture<StaffRecord> getRecordAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> getRecord(uuid), asyncPool);
    }

    public List<StaffRecord> getTop(String groupFilter, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        // TOP pokazuje czas aktywny (online - AFK), więc sortowanie musi używać tej samej wartości.
        // Wcześniej było ORDER BY total_playtime_ms, przez co osoba z dużą ilością AFK mogła być wyżej
        // mimo mniejszego czasu aktywnego widocznego w raporcie.
        String sql = groupFilter == null
                ? "SELECT * FROM staff_stats ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?"
                : "SELECT * FROM staff_stats WHERE group_name = ? COLLATE NOCASE ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (groupFilter == null) ps.setInt(1, limit);
            else { ps.setString(1, groupFilter); ps.setInt(2, limit); }
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getTop", e); }
        return list;
    }

    public List<StaffRecord> getByGroup(String group) {
        List<StaffRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM staff_stats WHERE group_name = ? COLLATE NOCASE ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC")) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getByGroup", e); }
        return list;
    }

    public List<StaffRecord> getAll(int limit) {
        List<StaffRecord> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM staff_stats ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getAll", e); }
        return list;
    }

    public List<StaffRecord> getActiveSince(long sinceMs, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM staff_stats WHERE last_login >= ? OR last_logout >= ? ORDER BY last_login DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sinceMs);
            ps.setLong(2, sinceMs);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getActiveSince", e); }
        return list;
    }

    public List<StaffRecord> getInactiveSince(long cutoffMs, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM staff_stats " +
                "WHERE last_login > 0 AND last_login < ? " +
                "ORDER BY last_login ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffMs);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getInactiveSince", e); }
        return list;
    }

    // ===== DAILY STATS =====
    public static class DailyStat {
        public final UUID uuid;
        public final String name;
        public final String group;
        public final long playtimeMs;
        public final long afkMs;
        public final int sessions;
        public final long lastSeen;
        public DailyStat(UUID uuid, String name, String group, long playtimeMs, long afkMs, int sessions, long lastSeen) {
            this.uuid = uuid; this.name = name; this.group = group;
            this.playtimeMs = playtimeMs; this.afkMs = afkMs;
            this.sessions = sessions; this.lastSeen = lastSeen;
        }
        public long activeMs() { return Math.max(0, playtimeMs - afkMs); }
    }

    /**
     * Sumuje sesje których logout nachodzi na okres [since, until]
     * + częściowo nachodzące sesje są przycinane proporcjonalnie do overlapu
     */
    public List<DailyStat> getDailyStats(long sinceMs, long untilMs, int limit) {
        List<DailyStat> out = new ArrayList<>();
        // Pobierz sesje w pełni lub częściowo w oknie czasowym
        // overlap: NOT (logout < since OR login > until)
        String sql = """
            SELECT uuid, name, MAX(group_name) as group_name,
                   SUM(
                     CASE
                       WHEN login_ts >= ? AND logout_ts <= ? THEN playtime_ms
                       WHEN login_ts < ? AND logout_ts > ? THEN
                         CAST(playtime_ms * (CAST((MIN(logout_ts, ?) - MAX(login_ts, ?)) AS REAL) / NULLIF(logout_ts - login_ts,0)) AS INTEGER)
                       WHEN login_ts < ? THEN
                         CAST(playtime_ms * (CAST((logout_ts - ?) AS REAL) / NULLIF(logout_ts - login_ts,0)) AS INTEGER)
                       WHEN logout_ts > ? THEN
                         CAST(playtime_ms * (CAST((? - login_ts) AS REAL) / NULLIF(logout_ts - login_ts,0)) AS INTEGER)
                       ELSE playtime_ms
                     END
                   ) as day_play,
                   SUM(
                     CASE
                       WHEN login_ts >= ? AND logout_ts <= ? THEN afk_ms
                       ELSE CAST(afk_ms * 0.5 AS INTEGER) -- przybliżenie dla sesji częściowych
                     END
                   ) as day_afk,
                   COUNT(*) as sessions,
                   MAX(logout_ts) as last_seen
            FROM staff_sessions
            WHERE NOT (logout_ts < ? OR login_ts > ?)
            GROUP BY uuid
            ORDER BY (day_play - day_afk) DESC, day_play DESC
            LIMIT ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i=1;
            // full contain
            ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
            // overlap both sides
            ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
            ps.setLong(i++, untilMs); ps.setLong(i++, sinceMs);
            // left overlap
            ps.setLong(i++, sinceMs); ps.setLong(i++, sinceMs);
            // right overlap
            ps.setLong(i++, untilMs); ps.setLong(i++, untilMs);
            // afk full
            ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
            // where
            ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
            ps.setInt(i++, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid;
                    try { uuid = UUID.fromString(rs.getString("uuid")); } catch (Exception e) { continue; }
                    out.add(new DailyStat(
                        uuid,
                        rs.getString("name"),
                        rs.getString("group_name"),
                        rs.getLong("day_play"),
                        rs.getLong("day_afk"),
                        rs.getInt("sessions"),
                        rs.getLong("last_seen")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "getDailyStats failed, fallback simple", e);
            // fallback prosty – bez overlap scaling
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, name, group_name, SUM(playtime_ms) as day_play, SUM(afk_ms) as day_afk, COUNT(*) as sessions, MAX(logout_ts) as last_seen " +
                "FROM staff_sessions WHERE logout_ts >= ? GROUP BY uuid ORDER BY (day_play - day_afk) DESC, day_play DESC LIMIT ?")) {
                ps.setLong(1, sinceMs);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new DailyStat(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getString("group_name"),
                            rs.getLong("day_play"),
                            rs.getLong("day_afk"),
                            rs.getInt("sessions"),
                            rs.getLong("last_seen")
                        ));
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "fallback daily stats fail", ex);
            }
        }
        return out;
    }

    public boolean resetPlayer(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM staff_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            boolean ok = ps.executeUpdate() > 0;
            // also clear sessions
            try (PreparedStatement ps2 = connection.prepareStatement("DELETE FROM staff_sessions WHERE uuid = ?")) {
                ps2.setString(1, uuid.toString());
                ps2.executeUpdate();
            } catch (SQLException ignored) {}
            return ok;
        } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "reset", e); return false; }
    }

    private StaffRecord map(ResultSet rs) throws SQLException {
        return new StaffRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getString("group_name"),
                rs.getLong("total_playtime_ms"),
                rs.getLong("total_afk_ms"),
                rs.getLong("last_login"),
                rs.getLong("last_logout"),
                rs.getInt("session_count"),
                rs.getLong("first_seen")
        );
    }

    public void shutdown() {
        try {
            asyncPool.shutdown();
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
