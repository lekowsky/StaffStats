package pl.kadrastats.staffstats.util;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LuckPermsHook {
    private final JavaPlugin plugin;
    private LuckPerms api;
    private final Map<UUID, CachedGroup> cache = new ConcurrentHashMap<>();
    private long cacheTtlMs = 30000;

    public LuckPermsHook(JavaPlugin plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().isSet("performance.luckperms-cache-ms"))
            cacheTtlMs = plugin.getConfig().getLong("performance.luckperms-cache-ms");
    }

    public boolean init() {
        try {
            api = LuckPermsProvider.get();
            // wersja przez opis pluginu (bukkit API) – NIE przez LuckPerms API:
            // getPluginMetadata() istnieje dopiero od LP 5.1 i przy starszej kopii
            // interfejsu LP w classpath rzuca NoSuchMethodError (Error, nie Exception!)
            String ver = "?";
            try {
                org.bukkit.plugin.Plugin lp = org.bukkit.Bukkit.getPluginManager().getPlugin("LuckPerms");
                if (lp != null && lp.getDescription() != null && lp.getDescription().getVersion() != null) {
                    ver = lp.getDescription().getVersion();
                }
            } catch (Throwable ignored) {}
            plugin.getLogger().info("LuckPerms hooked: " + ver);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("LuckPerms hook failed: " + t);
            return false;
        }
    }
    public boolean isActive() { return api != null; }

    public String getPrimaryGroup(UUID uuid) {
        CachedGroup cg = cache.get(uuid);
        long now = System.currentTimeMillis();
        if (cg != null && now - cg.time < cacheTtlMs) return cg.group;
        if (api == null) return "default";
        User user = api.getUserManager().getUser(uuid);
        if (user == null) { api.getUserManager().loadUser(uuid); return cg != null ? cg.group : "default"; }
        String primary = user.getPrimaryGroup();
        cache.put(uuid, new CachedGroup(primary, now));
        return primary;
    }


    public void invalidate(UUID uuid) { cache.remove(uuid); }
    private static class CachedGroup { final String group; final long time; CachedGroup(String g,long t){group=g;time=t;} }
}
