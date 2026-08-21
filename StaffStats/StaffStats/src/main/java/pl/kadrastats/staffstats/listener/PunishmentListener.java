package pl.kadrastats.staffstats.listener;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Integracja z LibertyBans (https://github.com/A248/LibertyBans) – zliczanie kar
 * wydawanych przez śledzoną kadrę (mute / kick / warn / ban).
 *
 * Hook w 100% refleksyjny (bez zależności kompilacyjnych) – ten sam wzorzec co hook
 * EssentialsX w AfkListener. LibertyBans NIE używa eventu Bukkita – ma własny event
 * bus (space.arim.omnibus). Rejestracja wg oficjalnej dokumentacji Developer-API:
 *
 *   Omnibus omnibus = OmnibusProvider.getOmnibus();
 *   omnibus.getEventBus().registerListener(PostPunishEvent.class, ListenerPriorities.NORMAL, consumer);
 *
 * Zdarzenie: space.arim.libertybans.api.event.PostPunishEvent#getPunishment() →
 * Punishment#getType() (BAN/MUTE/WARN/KICK) + Punishment#getOperator() →
 * Operator#getType() (PLAYER/CONSOLE) + PlayerOperator#getUUID().
 *
 * Liczymy kary wydawane przez graczy ze śledzonej kadry (aktywna sesja w trackerze).
 * Kary konsoli są pomijane. Liczniki są niezależne od rangi – widok filtruje rangą
 * przy wyświetlaniu (PunishDisplay.typesFor) – zmiana rangi nie psuje statystyk.
 */
public class PunishmentListener {

    private final StaffStatsPlugin plugin;
    private final ActivityTracker tracker;

    private ClassLoader lbClassLoader;
    private Object eventBus;
    private Object registeredListener;

    public PunishmentListener(StaffStatsPlugin plugin, ActivityTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    public boolean register() {
        Plugin lb = Bukkit.getPluginManager().getPlugin("LibertyBans");
        if (lb == null) return false;
        try {
            this.lbClassLoader = lb.getClass().getClassLoader();

            // OmnibusProvider.getOmnibus() -> getEventBus()
            Class<?> omnibusProvider = Class.forName("space.arim.omnibus.OmnibusProvider", true, lbClassLoader);
            Object omnibus = omnibusProvider.getMethod("getOmnibus").invoke(null);
            this.eventBus = omnibus.getClass().getMethod("getEventBus").invoke(omnibus);

            Class<?> postPunishEvent = Class.forName("space.arim.libertybans.api.event.PostPunishEvent", true, lbClassLoader);
            Class<?> eventConsumer = Class.forName("space.arim.omnibus.events.EventConsumer", true, lbClassLoader);
            Class<?> listenerPriorities = Class.forName("space.arim.omnibus.events.ListenerPriorities", true, lbClassLoader);
            byte normal = listenerPriorities.getField("NORMAL").getByte(null);

            InvocationHandler handler = (proxy, method, args) -> {
                if ("accept".equals(method.getName()) && args != null && args.length == 1 && args[0] != null) {
                    try {
                        handlePunishEvent(args[0]);
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.FINE, "LibertyBans hook error", ex);
                    }
                    return null;
                }
                switch (method.getName()) {
                    case "toString" -> { return "StaffStats-PunishmentConsumer"; }
                    case "hashCode" -> { return System.identityHashCode(proxy); }
                    case "equals"   -> { return args != null && args.length == 1 && proxy == args[0]; }
                    default         -> { return null; } // np. domyślne andThen – nieużywane przez bus
                }
            };
            Object consumer = Proxy.newProxyInstance(lbClassLoader, new Class<?>[]{eventConsumer}, handler);

            this.registeredListener = eventBus.getClass()
                    .getMethod("registerListener", Class.class, byte.class, eventConsumer)
                    .invoke(eventBus, postPunishEvent, normal, consumer);

            plugin.getLogger().info("LibertyBans hooked – zliczanie kar kadry (mute/kick/warn/ban) aktywne.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("LibertyBans hook failed: " + e);
            return false;
        }
    }

    private void handlePunishEvent(Object event) throws Exception {
        if (!plugin.getConfig().getBoolean("libertybans.enabled", true)) return;

        Object punishment = event.getClass().getMethod("getPunishment").invoke(event);
        if (punishment == null) return;

        Object type = punishment.getClass().getMethod("getType").invoke(punishment);
        if (type == null) return;
        String typeName = ((Enum<?>) type).name().toLowerCase(Locale.ROOT); // ban / mute / warn / kick

        Object operator = punishment.getClass().getMethod("getOperator").invoke(punishment);
        if (operator == null) return;
        Object operatorType = operator.getClass().getMethod("getType").invoke(operator);
        if (operatorType == null || !"PLAYER".equals(operatorType.toString())) return; // konsola → pomijamy

        UUID staffUuid = (UUID) operator.getClass().getMethod("getUUID").invoke(operator);
        if (staffUuid == null) return;
        if (tracker.getSession(staffUuid) == null) return; // tylko śledzona kadra online

        plugin.getDatabase().incrementPunishment(staffUuid, typeName);
    }

    /** Wyrejestrowanie listenera (onDisable / reload). */
    public void shutdown() {
        if (eventBus == null || registeredListener == null) return;
        try {
            Class<?> rlClass = Class.forName("space.arim.omnibus.events.RegisteredListener", true, lbClassLoader);
            eventBus.getClass().getMethod("unregisterListener", rlClass).invoke(eventBus, registeredListener);
        } catch (Exception ignored) {}
        registeredListener = null;
    }
}
