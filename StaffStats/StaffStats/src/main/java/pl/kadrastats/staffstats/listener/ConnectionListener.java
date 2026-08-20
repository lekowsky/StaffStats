package pl.kadrastats.staffstats.listener;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import pl.kadrastats.staffstats.util.WebhookManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ConnectionListener implements Listener {

    private final StaffStatsPlugin plugin;
    private final ActivityTracker tracker;
    private final WebhookManager webhook;

    public ConnectionListener(StaffStatsPlugin plugin, ActivityTracker tracker, WebhookManager webhook) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.webhook = webhook;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // Opóźnienie 1s – daj LuckPerms czas na załadowanie danych.
        // Guard isOnline(): gracz wyrzucony/wychodzący w tę sekundę NIE może dostać
        // sesji zombie (wcześniej: quit obsłużony PRZED join → sesja nigdy nie domknięta,
        // gracz świecił się "ONLINE" do końca życia serwera).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!e.getPlayer().isOnline()) return;
            tracker.handleJoin(e.getPlayer(), false);
            // Webhook join jest domyślnie WYŁĄCZONY – WebhookManager sam sprawdzi config
            if (webhook.isEnabled() && tracker.shouldTrack(e.getPlayer())) {
                String group = tracker.resolveGroup(e.getPlayer());
                webhook.sendJoin(e.getPlayer().getName(), group);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        ActivityTracker.SessionResult res = tracker.handleQuit(e.getPlayer());
        if (res != null && res.playtimeMs() > 0 && webhook.isEnabled()) {
            webhook.sendQuit(res);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent e) {
        ActivityTracker.SessionResult res = tracker.handleQuit(e.getPlayer());
        if (res != null && res.playtimeMs() > 0 && webhook.isEnabled()) {
            webhook.sendQuit(res);
        }
    }
}
