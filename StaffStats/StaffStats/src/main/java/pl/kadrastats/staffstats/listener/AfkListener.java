package pl.kadrastats.staffstats.listener;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

public class AfkListener {
    private final StaffStatsPlugin plugin;
    private final ActivityTracker tracker;
    public AfkListener(StaffStatsPlugin plugin, ActivityTracker tracker){ this.plugin=plugin; this.tracker=tracker; }

    public void register() {
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) return;
        try {
            Class<?> afkClass = Class.forName("net.ess3.api.events.AfkStatusChangeEvent", true, essentials.getClass().getClassLoader());
            Method getValue = afkClass.getMethod("getValue");
            Method getAffected = afkClass.getMethod("getAffected");
            EventExecutor executor = (listener, event) -> {
                if (!afkClass.isInstance(event)) return;
                try {
                    boolean isAfk = (Boolean) getValue.invoke(event);
                    Object affected = getAffected.invoke(event);
                    Method getUUID = affected.getClass().getMethod("getUUID");
                    UUID uuid = (UUID) getUUID.invoke(affected);
                    tracker.setAfk(uuid, isAfk, "ESS");
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.FINE, "AFK hook", ex);
                }
            };
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) afkClass, new Listener(){}, EventPriority.MONITOR, executor, plugin, true);
            plugin.getLogger().info("EssentialsX AFK hooked.");
        } catch (Exception e) {
            plugin.getLogger().warning("AFK hook failed: " + e.getMessage());
        }
    }
}
