package pl.kadrastats.staffstats.tracker;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.DatabaseManager;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.util.LuckPermsHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityTracker {

    private final StaffStatsPlugin plugin;
    private final DatabaseManager db;
    private final LuckPermsHook lpHook;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private Set<String> trackedGroups = new HashSet<>();
    private boolean trackOnlyListed;
    private int minSessionSeconds;
    private long maxSessionMs;

    public ActivityTracker(StaffStatsPlugin plugin, DatabaseManager db, LuckPermsHook lpHook) {
        this.plugin = plugin;
        this.db = db;
        this.lpHook = lpHook;
        reloadConfigCache();
    }

    public void reloadConfigCache() {
        trackedGroups = new HashSet<>();
        plugin.getConfig().getStringList("tracked-groups").forEach(g -> trackedGroups.add(g.toLowerCase(Locale.ROOT)));
        trackOnlyListed = plugin.getConfig().getBoolean("track-only-listed-groups", true);
        minSessionSeconds = plugin.getConfig().getInt("performance.min-session-seconds", 30);
        maxSessionMs = plugin.getConfig().getLong("security.max-session-hours", 24) * 3600_000L;
    }

    public boolean shouldTrack(Player p) {
        if (p.hasPermission("staffstats.bypass") && plugin.getConfig().getBoolean("security.enable-bypass-permission", true))
            return false;
        if (!trackOnlyListed) return true;
        String group = resolveGroup(p);
        return group != null && trackedGroups.contains(group.toLowerCase(Locale.ROOT));
    }

    public String resolveGroup(Player p) {
        // 1) LuckPerms primary group – główne źródło prawdy
        if (lpHook != null && lpHook.isActive()) {
            String g = lpHook.getPrimaryGroup(p.getUniqueId());
            if (g != null && !g.isEmpty() && !"default".equalsIgnoreCase(g)) {
                return g;
            }
            // jeśli LP zwróci default, sprawdź fallback permission
        }
        // 2) fallback: permission group.xxx
        for (String grp : trackedGroups) {
            if (p.hasPermission("group." + grp)) return grp;
        }
        // 3) ostateczny fallback
        if (lpHook != null && lpHook.isActive()) {
            String g = lpHook.getPrimaryGroup(p.getUniqueId());
            return g != null ? g : "default";
        }
        return "default";
    }

    public void handleJoin(Player player, boolean isReload) {
        if (!shouldTrack(player)) return;
        UUID uuid = player.getUniqueId();
        String group = resolveGroup(player);
        long now = System.currentTimeMillis();
        sessions.put(uuid, new Session(now, group, player.getName()));
        db.updateLogin(uuid, player.getName(), group, now);

        if (plugin.getConfig().getBoolean("notifications.staff-join-notify", false) && !isReload) {
            boolean respectVanish = plugin.getConfig().getBoolean("notifications.respect-vanish", true);
            if (respectVanish && isVanished(player)) return;
            String msg = color(plugin.getConfig().getString("messages.prefix", "&8[&bStaffStats&8] &7"))
                    + "§a" + player.getName() + " §7[" + group + "] dołączył.";
            Bukkit.getOnlinePlayers().stream()
                    .filter(pl -> pl.hasPermission(plugin.getConfig().getString("notifications.notify-permission", "staffstats.notify")))
                    .forEach(pl -> pl.sendMessage(msg));
        }
    }

    // returns SessionData for webhook
    public SessionResult handleQuit(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return null;
        SessionResult res = finalizeSession(player.getUniqueId(), s);

        if (res != null && plugin.getConfig().getBoolean("notifications.staff-quit-notify", false)) {
            String msg = color(plugin.getConfig().getString("messages.prefix",""))
                    + "§c" + res.name() + " §7opuścił. §8Sesja: " + StaffRecord.formatDuration(res.playtimeMs());
            Bukkit.getOnlinePlayers().stream()
                    .filter(pl -> pl.hasPermission(plugin.getConfig().getString("notifications.notify-permission","staffstats.notify")))
                    .forEach(pl -> pl.sendMessage(msg));
        }
        return res;
    }

    /**
     * Zapisuje i domyka sesję: pełny rekord do staff_sessions, interwały AFK do staff_afk,
     * a do staff_stats tylko brakującą DELTĘ (częściowe zapisy okresowe już uzupełniły statystyki).
     */
    private SessionResult finalizeSession(UUID uuid, Session s) {
        long now = System.currentTimeMillis();
        s.closeAfk(now);

        long rawPlay = now - s.loginTime;
        if (rawPlay < 0) {
            antiCheatLog("Anomalia: ujemny czas sesji " + s.playerName + " (" + rawPlay + " ms) – przycięte do 0.");
            rawPlay = 0;
        }
        long playtime = rawPlay;
        if (playtime < minSessionSeconds * 1000L) playtime = 0;
        if (rawPlay > maxSessionMs) {
            antiCheatLog("Sesja " + s.playerName + " [" + s.groupAtJoin + "] przekroczyła limit: "
                    + StaffRecord.formatDuration(rawPlay) + " > " + (maxSessionMs / 3600_000L) + "h – przycięta.");
            playtime = maxSessionMs;
        }
        long totalAfk = s.currentAfk();
        if (rawPlay > 0 && totalAfk > rawPlay) {
            antiCheatLog("Anomalia AFK: " + s.playerName + " – AFK (" + totalAfk + " ms) > sesja (" + rawPlay + " ms) – przycięte.");
        }
        long afk = Math.min(totalAfk, playtime);

        long deltaPlay;
        long deltaAfk;
        if (playtime <= 0) {
            deltaPlay = 0;
            deltaAfk = 0;
        } else {
            deltaPlay = Math.max(0, Math.min(playtime, rawPlay) - s.getFlushedPlayRaw());
            deltaAfk = Math.max(0, afk - s.getAfkFlushed());
        }

        db.upsertSession(uuid, s.playerName, s.groupAtJoin, deltaPlay, deltaAfk, s.loginTime, now, playtime > 0);
        if (playtime > 0) {
            db.insertSessionRecord(uuid, s.playerName, s.groupAtJoin, s.loginTime, now, playtime, afk);
        }
        db.insertAfkIntervals(uuid, s.drainNewIntervals());
        return new SessionResult(s.playerName, uuid, s.groupAtJoin, playtime, afk, s.loginTime, now);
    }

    public void setAfk(UUID uuid, boolean afk, String cause) {
        Session s = sessions.get(uuid);
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (afk) s.markAfkStart(now);
        else s.closeAfk(now);
    }

    /**
     * Okresowy zapis osób online (delta od ostatniego checkpointu) – chroni przed utratą
     * danych przy crashu serwera. Dodatkowo: znalezione sesje „zombie" (gracz bez zdarzenia
     * quit) są domykane tak samo jak przy wylogowaniu.
     */
    public void saveAllOnline(boolean sync) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            UUID uuid = e.getKey();
            Session s = e.getValue();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                // defensywnie: gracz wyszedł bez PlayerQuitEvent – zfinalizuj sesję
                if (sessions.remove(uuid, s)) {
                    finalizeSession(uuid, s);
                }
                continue;
            }
            long rawPlay = now - s.loginTime;
            if (rawPlay < 5000) continue;
            long deltaPlay = Math.max(0, Math.min(rawPlay, maxSessionMs) - s.getFlushedPlayRaw());
            long deltaAfk = Math.max(0, s.currentAfk() - s.getAfkFlushed());
            db.upsertSession(uuid, p.getName(), s.groupAtJoin, deltaPlay, deltaAfk, s.loginTime, 0, false);
            db.insertAfkIntervals(uuid, s.drainNewIntervals());
            s.markCheckpoint(now, rawPlay);
        }
    }

    public Session getSession(UUID uuid) { return sessions.get(uuid); }
    public Set<String> getTrackedGroups() { return trackedGroups; }

    /** security.anti-cheat-logging – logowanie anomalii statystyk do konsoli. */
    private void antiCheatLog(String msg) {
        if (plugin.getConfig().getBoolean("security.anti-cheat-logging", true)) {
            plugin.getLogger().warning("[AntiCheat] " + msg);
        }
    }

    private boolean isVanished(Player p) {
        return p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean());
    }
    private String color(String s){ return s.replace('&','§'); }

    /**
     * Sesja gracza:
     * - loginTime = NIEZMIENNY prawdziwy start sesji (poprzednio był resetowany przy
     *   okresowym zapisie, przez co rekord w staff_sessions tracił początek sesji)
     * - checkpointy (flushed*) pilnują, żeby nic nie policzyło się dwa razy
     * - afkIntervals = zamknięte odcinki AFK [start,end] do dokładnych raportów dziennych
     */
    public static class Session {
        public final long loginTime;
        public final String groupAtJoin;
        public final String playerName;
        public long afkStart = 0;   // otwarty interwał AFK (0 = brak)
        public long afkAccum = 0;   // zamknięte AFK od startu sesji
        public final List<long[]> afkIntervals = new ArrayList<>();
        private long flushedPlayRaw = 0;
        private long afkFlushed = 0;
        private int intervalsFlushed = 0;

        public Session(long loginTime, String groupAtJoin, String playerName) {
            this.loginTime = loginTime;
            this.groupAtJoin = groupAtJoin != null ? groupAtJoin : "unknown";
            this.playerName = playerName != null ? playerName : "unknown";
        }

        public synchronized long currentPlaytime() { return Math.max(0, System.currentTimeMillis() - loginTime); }
        public synchronized long currentAfk() { return afkAccum + (afkStart > 0 ? System.currentTimeMillis() - afkStart : 0); }
        public synchronized boolean isAfk() { return afkStart > 0; }

        public synchronized void markAfkStart(long now) { if (afkStart == 0) afkStart = now; }

        public synchronized void closeAfk(long now) {
            if (afkStart > 0) {
                afkAccum += (now - afkStart);
                afkIntervals.add(new long[]{afkStart, now});
                afkStart = 0;
            }
        }

        /** Zapamiętuje ile już zapisano do staff_stats (ochrona przed podwójnym liczeniem). */
        public synchronized void markCheckpoint(long now, long rawPlay) {
            this.flushedPlayRaw = rawPlay;
            this.afkFlushed = currentAfk(now);
        }

        public synchronized long getFlushedPlayRaw() { return flushedPlayRaw; }
        public synchronized long getAfkFlushed() { return afkFlushed; }

        /** Zwraca interwały AFK jeszcze niezapisane do bazy. */
        public synchronized List<long[]> drainNewIntervals() {
            if (intervalsFlushed >= afkIntervals.size()) return List.of();
            List<long[]> out = new ArrayList<>(afkIntervals.subList(intervalsFlushed, afkIntervals.size()));
            intervalsFlushed = afkIntervals.size();
            return out;
        }

        private long currentAfk(long now) { return afkAccum + (afkStart > 0 ? now - afkStart : 0); }
    }

    public record SessionResult(String name, UUID uuid, String group, long playtimeMs, long afkMs, long login, long logout) {}
}
