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
        sessions.put(uuid, new Session(now, group));
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
        UUID uuid = player.getUniqueId();
        Session s = sessions.remove(uuid);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        long playtime = now - s.loginTime;
        if (playtime < minSessionSeconds * 1000L) playtime = 0;
        if (playtime > maxSessionMs) {
            plugin.getLogger().warning("Sesja " + player.getName() + " > max, przycinam.");
            playtime = maxSessionMs;
        }
        if (s.afkStart > 0) { s.afkAccum += (now - s.afkStart); s.afkStart = 0; }
        long afk = Math.min(s.afkAccum, playtime);
        db.upsertSession(uuid, player.getName(), s.groupAtJoin, playtime, afk, s.loginTime, now, playtime > 0);
        // zapisz też do tabeli sesji – do raportów dziennych
        if (playtime > 0) {
            db.insertSessionRecord(uuid, player.getName(), s.groupAtJoin, s.loginTime, now, playtime, afk);
        }

        if (plugin.getConfig().getBoolean("notifications.staff-quit-notify", false)) {
            boolean respectVanish = plugin.getConfig().getBoolean("notifications.respect-vanish", true);
            if (!(respectVanish && isVanished(player))) {
                String msg = color(plugin.getConfig().getString("messages.prefix",""))
                        + "§c" + player.getName() + " §7opuścił. §8Sesja: " + StaffRecord.formatDuration(playtime);
                Bukkit.getOnlinePlayers().stream()
                        .filter(pl -> pl.hasPermission(plugin.getConfig().getString("notifications.notify-permission","staffstats.notify")))
                        .forEach(pl -> pl.sendMessage(msg));
            }
        }
        return new SessionResult(player.getName(), uuid, s.groupAtJoin, playtime, afk, s.loginTime, now);
    }

    public void setAfk(UUID uuid, boolean afk, String cause) {
        Session s = sessions.get(uuid);
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (afk) { if (s.afkStart == 0) s.afkStart = now; }
        else { if (s.afkStart > 0) { s.afkAccum += (now - s.afkStart); s.afkStart = 0; } }
    }

    public void saveAllOnline(boolean sync) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            UUID uuid = e.getKey();
            Session s = e.getValue();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            long playtime = now - s.loginTime;
            if (playtime < 5000) continue;
            long currentAfk = s.afkAccum + (s.afkStart > 0 ? now - s.afkStart : 0);
            db.upsertSession(uuid, p.getName(), s.groupAtJoin, playtime, currentAfk, s.loginTime, 0, false);
            s.loginTime = now;
            s.afkAccum = 0;
            s.afkStart = s.afkStart > 0 ? now : 0;
        }
    }

    public Session getSession(UUID uuid) { return sessions.get(uuid); }
    public Set<String> getTrackedGroups() { return trackedGroups; }

    private boolean isVanished(Player p) {
        return p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean());
    }
    private String color(String s){ return s.replace('&','§'); }

    public static class Session {
        public long loginTime;
        public long afkStart = 0;
        public long afkAccum = 0;
        public final String groupAtJoin;
        public Session(long loginTime, String groupAtJoin) { this.loginTime = loginTime; this.groupAtJoin = groupAtJoin != null ? groupAtJoin : "unknown"; }
        public long currentPlaytime() { return System.currentTimeMillis() - loginTime; }
        public long currentAfk() { long acc = afkAccum; if (afkStart > 0) acc += System.currentTimeMillis() - afkStart; return acc; }
        public boolean isAfk() { return afkStart > 0; }
    }

    public record SessionResult(String name, UUID uuid, String group, long playtimeMs, long afkMs, long login, long logout) {}
}
