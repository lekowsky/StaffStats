package pl.kadrastats.staffstats;

import pl.kadrastats.staffstats.command.StaffCommand;
import pl.kadrastats.staffstats.gui.GuiListener;
import pl.kadrastats.staffstats.listener.AfkListener;
import pl.kadrastats.staffstats.listener.ConnectionListener;
import pl.kadrastats.staffstats.storage.DatabaseManager;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import pl.kadrastats.staffstats.util.LuckPermsHook;
import pl.kadrastats.staffstats.util.WebhookManager;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.util.logging.Level;

public final class StaffStatsPlugin extends JavaPlugin {

    private static StaffStatsPlugin instance;
    private DatabaseManager database;
    private ActivityTracker activityTracker;
    private LuckPermsHook luckPermsHook;
    private AfkListener afkListener;
    private WebhookManager webhook;

    private BukkitTask dailyTask;
    private LocalDate lastWebhookSentDate = null;
    private ZonedDateTime nextWebhookRun = null;

    @Override
    public void onLoad() { instance = this; }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // AUTO-UPDATE CONFIG – nadpisywanie / merge po wgraniu nowego JARa
        try {
            new pl.kadrastats.staffstats.util.ConfigUpdater(this).run();
            reloadConfig();
        } catch (Exception e) {
            getLogger().warning("ConfigUpdater error: " + e.getMessage());
        }

        try {
            database = new DatabaseManager(this);
            database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "DB init failed", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (getConfig().getBoolean("integrations.luckperms", true)) {
            luckPermsHook = new LuckPermsHook(this);
            luckPermsHook.init();
        }

        activityTracker = new ActivityTracker(this, database, luckPermsHook);

        webhook = new WebhookManager(this);
        
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(this, activityTracker, webhook), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);

        if (getConfig().getBoolean("integrations.essentials-afk", true) && Bukkit.getPluginManager().getPlugin("Essentials") != null) {
            afkListener = new AfkListener(this, activityTracker);
            afkListener.register();
        }

        StaffCommand staffCmd = new StaffCommand(this, activityTracker, database);
        getCommand("staff").setExecutor(staffCmd);
        getCommand("staff").setTabCompleter(staffCmd);
        if (getCommand("staffstats") != null) {
            getCommand("staffstats").setExecutor(staffCmd);
            getCommand("staffstats").setTabCompleter(staffCmd);
        }

        Bukkit.getOnlinePlayers().forEach(p -> activityTracker.handleJoin(p, true));

        int period = getConfig().getInt("storage.periodic-save-minutes", 0);
        if (period > 0) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> activityTracker.saveAllOnline(false),
                    period * 60L * 20L, period * 60L * 20L);
        }

        scheduleDailySummary();

        getLogger().info("StaffStats v" + getDescription().getVersion() + " enabled. /staff GUI ready.");
        if (webhook != null) {
            getLogger().info("Webhook enabled=" + webhook.isEnabled() + " urlConfigured=" + getConfig().getBoolean("webhook.enabled"));
        }
    }

    @Override
    public void onDisable() {
        if (dailyTask != null) { dailyTask.cancel(); dailyTask = null; }
        if (activityTracker != null) activityTracker.saveAllOnline(true);
        if (database != null) database.shutdown();
        if (webhook != null) webhook.shutdown();
    }

    public void reloadPlugin() {
        reloadConfig();
        if (activityTracker != null) activityTracker.reloadConfigCache();
        if (webhook != null) webhook.reload();
        // reschedule webhook
        scheduleDailySummary();
        getLogger().info("StaffStats reload complete.");
    }

    private void scheduleDailySummary() {
        // cancel old
        if (dailyTask != null) { dailyTask.cancel(); dailyTask = null; }
        lastWebhookSentDate = null;

        int hour = getConfig().getInt("webhook.daily-summary-hour", -1);
        int minute = getConfig().getInt("webhook.daily-summary-minute", 0);
        if (hour < 0 || hour > 23) {
            getLogger().info("Daily webhook wyłączony (daily-summary-hour = " + hour + ")");
            nextWebhookRun = null;
            return;
        }
        if (minute < 0 || minute > 59) minute = 0;

        // Always schedule the checker, even if webhook currently disabled – it will check isEnabled() at run time
        final int targetHour = hour;
        final int targetMinute = minute;
        ZoneId zone = ZoneId.of("Europe/Warsaw");

        // compute next run for info
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.withHour(targetHour).withMinute(targetMinute).withSecond(5).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        nextWebhookRun = next;

        long minutesUntil = Duration.between(now, next).toMinutes();
        getLogger().info("[Webhook] Daily summary zaplanowany: " + next + " (" + zone + ") za ~" + minutesUntil + " min. Enabled=" + (webhook != null && webhook.isEnabled()));

        // checker co 30 sekund – odporny na restarty/reloady
        final int fHour = targetHour;
        final int fMinute = targetMinute;
        dailyTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                if (webhook == null || !webhook.isEnabled()) return;
                ZonedDateTime znow = ZonedDateTime.now(zone);
                // czy jest czas wysyłki?
                if (znow.getHour() == fHour && znow.getMinute() >= fMinute && znow.getMinute() < fMinute + 5) {
                    LocalDate today = znow.toLocalDate();
                    if (lastWebhookSentDate == null || !lastWebhookSentDate.equals(today)) {
                        lastWebhookSentDate = today;
                        getLogger().info("[Webhook] Wysyłam dzienny raport (" + today + " " + fHour + ":" + String.format("%02d", fMinute) + ")");
                        webhook.sendDailySummary(database);
                        // update next run info
                        nextWebhookRun = znow.plusDays(1).withHour(fHour).withMinute(fMinute).withSecond(5);
                    }
                }
                // update nextRun display (for /staff webhook schedule)
                ZonedDateTime n = ZonedDateTime.now(zone).withHour(fHour).withMinute(fMinute).withSecond(5);
                if (!n.isAfter(ZonedDateTime.now(zone))) n = n.plusDays(1);
                nextWebhookRun = n;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Daily webhook task error", ex);
            }
        }, 20L * 20L, 20L * 30L); // start po 20s, co 30s sprawdzaj
    }

    public ZonedDateTime getNextWebhookRun() { return nextWebhookRun; }
    public LocalDate getLastWebhookSentDate() { return lastWebhookSentDate; }

    public static StaffStatsPlugin getInstance() { return instance; }
    public DatabaseManager getDatabase() { return database; }
    public ActivityTracker getActivityTracker() { return activityTracker; }
    public LuckPermsHook getLuckPermsHook() { return luckPermsHook; }
    public WebhookManager getWebhook() { return webhook; }
}
