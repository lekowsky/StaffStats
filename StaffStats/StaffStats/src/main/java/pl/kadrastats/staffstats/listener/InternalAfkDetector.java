package pl.kadrastats.staffstats.listener;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wbudowany detektor AFK – fallback gdy na serwerze NIE ma EssentialsX.
 * Implementuje opcje config: integrations.internal-afk-detector / internal-afk-timeout-minutes
 *
 * Zasady:
 * - śledzi WYŁĄCZNIE kadrę (osoby bez aktywnej sesji w trackerze są ignorowane – zero narzutu)
 * - oznacza AFK po X minutach bezruchu (ruch/obrót kamerą/interakcja/czat/komenda resetują licznik)
 * - aktywność gracza zawsze zdejmuje flagę AFK (działa też jako wzbudzacz dla AFK z EssentialsX)
 */
public class InternalAfkDetector implements Listener {

    private final StaffStatsPlugin plugin;
    private final ActivityTracker tracker;
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long timeoutMs = 10 * 60_000L;

    public InternalAfkDetector(StaffStatsPlugin plugin, ActivityTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /** Rejestruje eventy + startuje pętlę sprawdzającą (co 15 s, wątek główny). */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        schedule();
        plugin.getLogger().info("Internal AFK detector aktywny (timeout " + (timeoutMs / 60_000L) + " min).");
    }

    /** Restart po /staff reload – wg aktualnego configa. */
    public void restart() {
        if (task != null) { task.cancel(); task = null; }
        if (plugin.getConfig().getBoolean("integrations.internal-afk-detector", false)) {
            schedule();
        }
    }

    public void stop() {
        lastActivity.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    private void schedule() {
        int minutes = Math.max(1, plugin.getConfig().getInt("integrations.internal-afk-timeout-minutes", 10));
        this.timeoutMs = minutes * 60_000L;
        if (task != null) task.cancel();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 15L, 20L * 15L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ActivityTracker.Session s = tracker.getSession(p.getUniqueId());
            if (s == null || s.isAfk()) continue;
            long last = lastActivity.getOrDefault(p.getUniqueId(), now);
            if (now - last >= timeoutMs) {
                tracker.setAfk(p.getUniqueId(), true, "IDLE");
            }
        }
    }

    private void markActivity(Player p) {
        if (p == null) return;
        ActivityTracker.Session s = tracker.getSession(p.getUniqueId());
        if (s == null) return; // tylko kadra – zwykli gracze nie obciążają mapy
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());
        if (s.isAfk()) tracker.setAfk(p.getUniqueId(), false, "ACTIVITY");
    }

    // ---- eventy aktywności ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        var from = e.getFrom();
        var to = e.getTo();
        boolean moved = from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()
                || Math.abs(from.getYaw() - to.getYaw()) > 5.0f
                || Math.abs(from.getPitch() - to.getPitch()) > 5.0f;
        if (moved) markActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) { markActivity(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        // wątek async – tracker.setAfk jest bezpieczny wątkowo (ConcurrentHashMap)
        markActivity(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { markActivity(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { markActivity(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { lastActivity.remove(e.getPlayer().getUniqueId()); }
}
