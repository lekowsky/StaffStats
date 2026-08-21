package pl.kadrastats.staffstats.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private final ExecutorService asyncPool;
    private final boolean asyncWrites;

    /**
     * Wspólna blokada dla WSZYSTKICH operacji na połączeniu.
     * Zapisy idą z dedykowanego wątku, ale odczyty wołane są też z main thread
     * (raporty, tab-complete) oraz z wątków async (GUI, webhook) – jedno połączenie
     * JDBC nie jest bezpieczne do współbieżnego użycia, więc serializujemy dostęp.
     */
    private final Object lock = new Object();

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
        synchronized (lock) {
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

                // szczegółowe sesje – do dziennych raportów (pełne sesje: login → logout)
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

                // interwały AFK – dokładne liczenie AFK w oknach czasowych (daily raport)
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS staff_afk (
                        uuid TEXT NOT NULL,
                        start_ts INTEGER NOT NULL,
                        end_ts INTEGER NOT NULL
                    );
                    """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_afk_uuid_time ON staff_afk(uuid, start_ts);");

                // liczniki kar (LibertyBans) – niezależne od rangi; widok filtruje rangą przy wyświetlaniu
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS staff_punishments (
                        uuid TEXT NOT NULL,
                        ptype TEXT NOT NULL,
                        cnt INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (uuid, ptype)
                    );
                    """);

                // metadane pluginu (m.in. kotwica cyklu tygodniowego)
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS staff_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    );
                    """);

                // archiwum zamkniętych tygodni (historia po resecie postępu)
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS staff_weeks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        week_start INTEGER NOT NULL,
                        week_end INTEGER NOT NULL,
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        group_name TEXT,
                        playtime_ms INTEGER NOT NULL DEFAULT 0,
                        afk_ms INTEGER NOT NULL DEFAULT 0,
                        session_count INTEGER NOT NULL DEFAULT 0,
                        bans INTEGER NOT NULL DEFAULT 0,
                        mutes INTEGER NOT NULL DEFAULT 0,
                        kicks INTEGER NOT NULL DEFAULT 0,
                        warns INTEGER NOT NULL DEFAULT 0
                    );
                    """);
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_weeks_time ON staff_weeks(week_end DESC);");
            }
        }
    }

    /**
     * Dodaje kolejne porcje czasu do statystyk gracza.
     * UWAGA: playtimeAdd/afkAdd to DELTY (część sesji od ostatniego zapisu),
     * nie pełna sesja – dzięki temu okresowy zapis + quit nie liczą się podwójnie.
     */
    public void upsertSession(UUID uuid, String name, String group,
                              long playtimeAdd, long afkAdd,
                              long loginTs, long logoutTs, boolean incrementSession) {
        Runnable task = () -> {
            synchronized (lock) {
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
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    // Zapis pełnej sesji do tabeli staff_sessions (do raportów dziennych)
    public void insertSessionRecord(UUID uuid, String name, String group, long loginTs, long logoutTs, long playtimeMs, long afkMs) {
        if (playtimeMs <= 0) return;
        Runnable task = () -> {
            synchronized (lock) {
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
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    /**
     * Zapis interwałów AFK [start, end] – pozwala DOKŁADNIE policzyć AFK w dowolnym oknie
     * czasowym (wcześniej: szacunek afk*0.5 dla sesji częściowych).
     */
    public void insertAfkIntervals(UUID uuid, List<long[]> intervals) {
        if (intervals == null || intervals.isEmpty()) return;
        Runnable task = () -> {
            synchronized (lock) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO staff_afk (uuid, start_ts, end_ts) VALUES (?,?,?)")) {
                    for (long[] iv : intervals) {
                        if (iv == null || iv.length < 2 || iv[1] <= iv[0]) continue;
                        ps.setString(1, uuid.toString());
                        ps.setLong(2, iv[0]);
                        ps.setLong(3, iv[1]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "insertAfkIntervals failed", e);
                }
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    // ===== LIBERTYBANS – liczniki kar =====

    /** +1 kara danego typu (ban/mute/warn/kick) dla członka kadry. */
    public void incrementPunishment(UUID uuid, String type) {
        if (uuid == null || type == null || type.isBlank()) return;
        final String t = type.toLowerCase(java.util.Locale.ROOT);
        Runnable task = () -> {
            synchronized (lock) {
                try (PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO staff_punishments (uuid, ptype, cnt, updated_at)
                        VALUES (?, ?, 1, ?)
                        ON CONFLICT(uuid, ptype) DO UPDATE SET
                            cnt = cnt + 1,
                            updated_at = excluded.updated_at
                        """)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, t);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "incrementPunishment failed", e);
                }
            }
        };
        if (asyncWrites) asyncPool.execute(task); else task.run();
    }

    /** Liczniki kar jednego gracza: typ -> liczba. */
    public Map<String, Long> getPunishmentCounts(UUID uuid) {
        Map<String, Long> out = new HashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ptype, cnt FROM staff_punishments WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.put(rs.getString("ptype"), rs.getLong("cnt"));
                }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getPunishmentCounts", e); }
            return out;
        }
    }

    /** Wszystkie liczniki kar jednym zapytaniem – do budowy GUI. */
    public Map<UUID, Map<String, Long>> getAllPunishmentCounts() {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        synchronized (lock) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, ptype, cnt FROM staff_punishments")) {
                while (rs.next()) {
                    try {
                        out.computeIfAbsent(UUID.fromString(rs.getString("uuid")), k -> new HashMap<>())
                           .put(rs.getString("ptype"), rs.getLong("cnt"));
                    } catch (IllegalArgumentException ignored) {}
                }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getAllPunishmentCounts", e); }
            return out;
        }
    }

    // ===== META / CYKL TYGODNIOWY =====

    public String getMetaValue(String key) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM staff_meta WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString("value"); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getMetaValue", e); }
            return null;
        }
    }

    public void setMetaValue(String key, String value) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO staff_meta (key, value) VALUES (?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "setMetaValue", e); }
        }
    }

    /** Czeka (max 10 s) aż kolejka zapisów async się wyczerpie – przed resetem tygodnia. */
    public void flush() {
        try {
            asyncPool.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /** Kasuje CAŁY postęp kadry (reset tygodnia). Wywoływane po flush() i webhookie. */
    public void wipeAllStats() {
        synchronized (lock) {
            String[] tables = {"staff_stats", "staff_sessions", "staff_afk", "staff_punishments"};
            for (String t : tables) {
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("DELETE FROM " + t);
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "wipe " + t, e);
                }
            }
        }
    }

    /** Archiwizuje zamknięty tydzień do staff_weeks (historia zachowana po resecie). */
    public void archiveWeek(long fromMs, long toMs, List<StaffRecord> top, Map<UUID, Map<String, Long>> punish) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO staff_weeks (week_start, week_end, uuid, name, group_name,
                        playtime_ms, afk_ms, session_count, bans, mutes, kicks, warns)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (StaffRecord r : top) {
                    Map<String, Long> p = punish.getOrDefault(r.uuid, Map.of());
                    ps.setLong(1, fromMs);
                    ps.setLong(2, toMs);
                    ps.setString(3, r.uuid.toString());
                    ps.setString(4, r.name);
                    ps.setString(5, r.group);
                    ps.setLong(6, r.totalPlaytimeMs);
                    ps.setLong(7, r.totalAfkMs);
                    ps.setInt(8, r.sessionCount);
                    ps.setLong(9, p.getOrDefault("ban", 0L));
                    ps.setLong(10, p.getOrDefault("mute", 0L));
                    ps.setLong(11, p.getOrDefault("kick", 0L));
                    ps.setLong(12, p.getOrDefault("warn", 0L));
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "archiveWeek failed", e);
            }
        }
    }

    public void updateLogin(UUID uuid, String name, String group, long loginTs) {
        upsertSession(uuid, name, group, 0, 0, loginTs, 0, false);
    }

    public StaffRecord getRecord(UUID uuid) {
        synchronized (lock) {
            String sql = "SELECT * FROM staff_stats WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return map(rs); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getRecord", e); }
            return null;
        }
    }

    public CompletableFuture<StaffRecord> getRecordAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> getRecord(uuid), asyncPool);
    }

    public List<StaffRecord> getTop(String groupFilter, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        // TOP pokazuje czas aktywny (online - AFK), więc sortowanie musi używać tej samej wartości.
        String sql = groupFilter == null
                ? "SELECT * FROM staff_stats ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?"
                : "SELECT * FROM staff_stats WHERE group_name = ? COLLATE NOCASE ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?";
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (groupFilter == null) ps.setInt(1, limit);
                else { ps.setString(1, groupFilter); ps.setInt(2, limit); }
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getTop", e); }
            return list;
        }
    }

    /** Szukanie po nicku (do /staff <nick>). */
    public List<StaffRecord> searchByName(String query, int limit) {
        synchronized (lock) {
            List<StaffRecord> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM staff_stats WHERE name LIKE ? COLLATE NOCASE ORDER BY total_playtime_ms DESC LIMIT ?")) {
                ps.setString(1, "%" + query + "%");
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "searchByName", e);
            }
            return out;
        }
    }

    /** Wariant getTop poza wątkiem głównym (do /staff top z czatu). */
    public CompletableFuture<List<StaffRecord>> getTopAsync(String groupFilter, int limit) {
        return CompletableFuture.supplyAsync(() -> getTop(groupFilter, limit), asyncPool);
    }

    public List<StaffRecord> getByGroup(String group) {
        List<StaffRecord> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM staff_stats WHERE group_name = ? COLLATE NOCASE ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC")) {
                ps.setString(1, group);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getByGroup", e); }
            return list;
        }
    }

    public List<StaffRecord> getAll(int limit) {
        List<StaffRecord> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM staff_stats ORDER BY (total_playtime_ms - total_afk_ms) DESC, total_playtime_ms DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getAll", e); }
            return list;
        }
    }

    public List<StaffRecord> getActiveSince(long sinceMs, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM staff_stats WHERE last_login >= ? OR last_logout >= ? ORDER BY last_login DESC LIMIT ?";
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, sinceMs);
                ps.setLong(2, sinceMs);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getActiveSince", e); }
            return list;
        }
    }

    public List<StaffRecord> getInactiveSince(long cutoffMs, int limit) {
        List<StaffRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM staff_stats " +
                "WHERE last_login > 0 AND last_login < ? " +
                "ORDER BY last_login ASC LIMIT ?";
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, cutoffMs);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) list.add(map(rs)); }
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "getInactiveSince", e); }
            return list;
        }
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
     * Statystyki w oknie czasowym [sinceMs, untilMs]:
     * - playtime: sumy sesji z proporcjonalnym przycięciem sesji częściowych
     * - AFK: DOKŁADNIE z interwałów (staff_afk); dla starych danych (bez interwałów)
     *   fallback na afk_ms sesji w pełni mieszczących się w oknie
     */
    public List<DailyStat> getDailyStats(long sinceMs, long untilMs, int limit) {
        List<DailyStat> out = new ArrayList<>();
        synchronized (lock) {
            try {
                // 1) playtime + metadane z tabeli sesji
                Map<UUID, Object[]> sess = new HashMap<>(); // [name, group, dayPlay, sessions, lastSeen]
                try (PreparedStatement ps = connection.prepareStatement("""
                        SELECT uuid, name, MAX(group_name) AS group_name,
                               SUM(CASE
                                     WHEN login_ts >= ? AND logout_ts <= ? THEN playtime_ms
                                     WHEN logout_ts - login_ts <= 0 THEN 0
                                     ELSE CAST(playtime_ms * ((MIN(logout_ts, ?) - MAX(login_ts, ?)) * 1.0 / (logout_ts - login_ts)) AS INTEGER)
                                   END) AS day_play,
                               COUNT(*) AS sessions,
                               MAX(logout_ts) AS last_seen
                        FROM staff_sessions
                        WHERE NOT (logout_ts < ? OR login_ts > ?)
                        GROUP BY uuid
                        LIMIT ?
                        """)) {
                    int i = 1;
                    ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
                    ps.setLong(i++, untilMs); ps.setLong(i++, sinceMs);
                    ps.setLong(i++, sinceMs); ps.setLong(i++, untilMs);
                    ps.setInt(i++, Math.max(limit * 3, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                sess.put(UUID.fromString(rs.getString("uuid")), new Object[]{
                                        rs.getString("name"), rs.getString("group_name"),
                                        rs.getLong("day_play"), rs.getInt("sessions"), rs.getLong("last_seen")});
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }

                // 2) dokładny AFK z interwałów (przecięcie z oknem)
                Map<UUID, Long> afkIntervals = new HashMap<>();
                try (PreparedStatement ps = connection.prepareStatement("""
                        SELECT uuid, SUM(MAX(0, MIN(end_ts, ?) - MAX(start_ts, ?))) AS day_afk
                        FROM staff_afk
                        WHERE NOT (end_ts < ? OR start_ts > ?)
                        GROUP BY uuid
                        """)) {
                    ps.setLong(1, untilMs); ps.setLong(2, sinceMs);
                    ps.setLong(3, sinceMs); ps.setLong(4, untilMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try { afkIntervals.put(UUID.fromString(rs.getString("uuid")), rs.getLong("day_afk")); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }
                }

                // 3) fallback: legacy sesje bez interwałów (afk_ms tylko dla sesji w pełni w oknie)
                Map<UUID, Long> afkLegacy = new HashMap<>();
                try (PreparedStatement ps = connection.prepareStatement("""
                        SELECT s.uuid AS uuid, SUM(s.afk_ms) AS day_afk
                        FROM staff_sessions s
                        WHERE s.login_ts >= ? AND s.logout_ts <= ?
                          AND NOT EXISTS (SELECT 1 FROM staff_afk a WHERE a.uuid = s.uuid)
                        GROUP BY s.uuid
                        """)) {
                    ps.setLong(1, sinceMs);
                    ps.setLong(2, untilMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try { afkLegacy.put(UUID.fromString(rs.getString("uuid")), rs.getLong("day_afk")); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }
                }

                // scala
                for (Map.Entry<UUID, Object[]> entry : sess.entrySet()) {
                    UUID uuid = entry.getKey();
                    Object[] row = entry.getValue();
                    long afk = afkIntervals.getOrDefault(uuid, 0L) + afkLegacy.getOrDefault(uuid, 0L);
                    out.add(new DailyStat(uuid, (String) row[0], (String) row[1],
                            (Long) row[2], afk, (Integer) row[3], (Long) row[4]));
                }
                out.sort((a, b) -> {
                    int c = Long.compare(b.activeMs(), a.activeMs());
                    return c != 0 ? c : Long.compare(b.playtimeMs, a.playtimeMs);
                });
                if (out.size() > limit) out = new ArrayList<>(out.subList(0, limit));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "getDailyStats failed, fallback simple", e);
                out.clear();
                // fallback prosty – bez interwałów (stary sposób)
                try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, name, group_name, SUM(playtime_ms) as day_play, SUM(afk_ms) as day_afk, COUNT(*) as sessions, MAX(logout_ts) as last_seen " +
                    "FROM staff_sessions WHERE logout_ts >= ? GROUP BY uuid ORDER BY (day_play - day_afk) DESC, day_play DESC LIMIT ?")) {
                    ps.setLong(1, sinceMs);
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                out.add(new DailyStat(
                                    UUID.fromString(rs.getString("uuid")),
                                    rs.getString("name"),
                                    rs.getString("group_name"),
                                    rs.getLong("day_play"),
                                    rs.getLong("day_afk"),
                                    rs.getInt("sessions"),
                                    rs.getLong("last_seen")
                                ));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "fallback daily stats fail", ex);
                }
            }
        }
        return out;
    }

    public boolean resetPlayer(UUID uuid) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM staff_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                boolean ok = ps.executeUpdate() > 0;
                // also clear sessions + afk intervals
                try (PreparedStatement ps2 = connection.prepareStatement("DELETE FROM staff_sessions WHERE uuid = ?")) {
                    ps2.setString(1, uuid.toString());
                    ps2.executeUpdate();
                } catch (SQLException ignored) {}
                try (PreparedStatement ps3 = connection.prepareStatement("DELETE FROM staff_afk WHERE uuid = ?")) {
                    ps3.setString(1, uuid.toString());
                    ps3.executeUpdate();
                } catch (SQLException ignored) {}
                try (PreparedStatement ps4 = connection.prepareStatement("DELETE FROM staff_punishments WHERE uuid = ?")) {
                    ps4.setString(1, uuid.toString());
                    ps4.executeUpdate();
                } catch (SQLException ignored) {}
                return ok;
            } catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "reset", e); return false; }
        }
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

    /**
     * Zamyka bazę BEZ utraty danych: najpierw czeka (do 5 s) aż kolejka zapisów async
     * się wyczerpie, dopiero potem zamyka połączenie. (Wcześniej: close() mógł
     * nastąpić w trakcie wykonywania zapisów → utrata ostatnich sesji przy stopie.)
     */
    public void shutdown() {
        asyncPool.shutdown();
        try {
            if (!asyncPool.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) connection.close();
            } catch (SQLException ignored) {}
        }
    }
}
