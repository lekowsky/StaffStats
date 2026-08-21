package pl.kadrastats.staffstats.util;

import pl.kadrastats.staffstats.StaffStatsPlugin;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Cykl tygodniowy (config: weekly-reset):
 * co interval-days (domyślnie 7) następuje pełny reset postępu kadry:
 *
 *   1) doliczenie trwających sesji (flush do bazy)
 *   2) webhook Discord z podsumowaniem tygodnia (TOP aktywność + TOP kary + nieobecni)
 *   3) archiwum tygodnia do tabeli staff_weeks (historia zostaje w bazie)
 *   4) wyczyszczenie CAŁEGO postępu (staff_stats, staff_sessions, staff_afk, staff_punishments)
 *   5) restart serwera (restart-command, domyślnie "restart")
 *
 * Kotwica cyklu (last_weekly_reset) trzymana w tabeli staff_meta – reset nadąża
 * także gdy serwer był wyłączony w terminie (catch-up przy starcie).
 * Wymuszenie ręczne: /staff weekly reset
 */
public class WeeklyResetManager {

    public static final String META_LAST_RESET = "last_weekly_reset";
    private static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final StaffStatsPlugin plugin;
    private BukkitTask task;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeeklyResetManager(StaffStatsPlugin plugin) {
        this.plugin = plugin;
    }

    public void schedule() {
        if (task != null) { task.cancel(); task = null; }
        if (!plugin.getConfig().getBoolean("weekly-reset.enabled", true)) {
            plugin.getLogger().info("[WeeklyReset] Wyłączony w configu (weekly-reset.enabled=false).");
            return;
        }
        try {
            task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 30L, 20L * 60L);
            plugin.getLogger().info("[WeeklyReset] Interwał: " + intervalDays() + " dni | następny reset: "
                    + format(getNextResetAt())
                    + " | restart serwera: " + (plugin.getConfig().getBoolean("weekly-reset.restart-server", true) ? "TAK" : "NIE"));
        } catch (Exception ex) {
            // nawet jeśli nie udało się wystartować checker-a, plugin działa dalej
            plugin.getLogger().log(Level.WARNING, "[WeeklyReset] schedule() error", ex);
        }
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public long intervalDays() {
        long d = plugin.getConfig().getLong("weekly-reset.interval-days", 7);
        return Math.max(1, d);
    }

    private long intervalMs() { return intervalDays() * 86_400_000L; }

    /** Czas ostatniego resetu (kotwica cyklu). Pierwsze uruchomienie = teraz. */
    public long lastResetAt() {
        String v = plugin.getDatabase().getMetaValue(META_LAST_RESET);
        long val = 0;
        if (v != null) {
            try { val = Long.parseLong(v); } catch (NumberFormatException ignored) {}
        }
        if (val <= 0) {
            val = System.currentTimeMillis();
            plugin.getDatabase().setMetaValue(META_LAST_RESET, String.valueOf(val));
        }
        return val;
    }

    /** Następny reset liczony od kotwicy (z opcjonalnym przesunięciem na at-hour). */
    public long getNextResetAt() { return getNextResetAfter(lastResetAt()); }

    /** Następny reset gdyby kotwicą był podany czas (do podglądu po resecie). */
    public long getNextResetAfter(long anchorMs) {
        long base = anchorMs + intervalMs();
        int hour = plugin.getConfig().getInt("weekly-reset.at-hour", -1);
        int minute = plugin.getConfig().getInt("weekly-reset.at-minute", 0);
        if (hour >= 0 && hour <= 23) {
            if (minute < 0 || minute > 59) minute = 0;
            ZonedDateTime t = Instant.ofEpochMilli(base).atZone(ZONE)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            if (t.toInstant().toEpochMilli() < base) t = t.plusDays(1);
            base = t.toInstant().toEpochMilli();
        }
        return base;
    }

    public String format(long epochMs) {
        return FMT.format(Instant.ofEpochMilli(epochMs).atZone(ZONE));
    }

    /** Wymuszenie resetu z komendy (/staff weekly reset) – async, z powiadomieniem nadawcy. */
    public void forceReset(org.bukkit.command.CommandSender initiator) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            executeReset(true);
            if (initiator != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        initiator.sendMessage(net.kyori.adventure.text.Component.text(
                                "[StaffStats] Reset tygodnia wykonany.",
                                net.kyori.adventure.text.format.NamedTextColor.GREEN)));
            }
        });
    }

    private void check() {
        try {
            if (!plugin.getConfig().getBoolean("weekly-reset.enabled", true)) return;
            if (System.currentTimeMillis() >= getNextResetAt()) {
                executeReset(false);
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[WeeklyReset] check error", ex);
        }
    }

    /**
     * Pełny cykl resetu tygodnia. Wywoływane z wątku async (checker albo /staff weekly reset).
     * Sekwencja jest odporna na restart: dane tygodnia są kompletne ZANIM cokolwiek zniknie.
     */
    public void executeReset(boolean forced) {
        if (!running.compareAndSet(false, true)) return;
        try {
            var db = plugin.getDatabase();
            var tracker = plugin.getActivityTracker();

            long to = System.currentTimeMillis();
            long last = lastResetAt();
            long from = (last > 0 && last < to) ? last : Math.max(0, to - intervalMs());

            plugin.getLogger().info("[WeeklyReset]" + (forced ? " (wymuszony)" : "")
                    + " zamykam tydzień " + format(from) + " → " + format(to));

            // 1) dolicz trwające sesje + czekaj aż kolejka zapisów async się wyczerpie
            if (tracker != null) tracker.saveAllOnline(false);
            db.flush();

            // 2) komplet danych tygodnia (PRZED czyszczeniem)
            List<pl.kadrastats.staffstats.storage.StaffRecord> top = db.getTop(null, 20);
            Map<UUID, Map<String, Long>> punish = db.getAllPunishmentCounts();

            // 3) webhook z podsumowaniem – SYNCHRONICZNIE (musi zdążyć przed restartem)
            var webhook = plugin.getWebhook();
            if (plugin.getConfig().getBoolean("weekly-reset.send-webhook", true)
                    && webhook != null && webhook.isEnabled()) {
                try {
                    webhook.sendWeeklySummary(top, punish, from, to, getNextResetAfter(to));
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "[WeeklyReset] webhook nie wysłany", ex);
                }
            }

            // 4) archiwum tygodnia (historia w staff_weeks)
            try {
                db.archiveWeek(from, to, top, punish);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "[WeeklyReset] archiwizacja nieudana (kontynuuję)", ex);
            }

            // 5) czyszczenie CAŁEGO postępu + nowa kotwica
            db.wipeAllStats();
            db.setMetaValue(META_LAST_RESET, String.valueOf(to));

            plugin.getLogger().info("[WeeklyReset] Postęp zresetowany. Historia tygodnia w tabeli staff_weeks.");

            // 6) restart serwera (wątek główny, 3 s na domknięcie logów)
            if (plugin.getConfig().getBoolean("weekly-reset.restart-server", true)) {
                String cmd = plugin.getConfig().getString("weekly-reset.restart-command", "restart");
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.getLogger().info("[WeeklyReset] Restart serwera: /" + cmd);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }, 60L));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "[WeeklyReset] Błąd resetu tygodnia", ex);
        } finally {
            running.set(false);
        }
    }
}
