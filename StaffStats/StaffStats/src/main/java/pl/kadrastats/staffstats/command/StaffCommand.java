package pl.kadrastats.staffstats.command;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.gui.StaffGui;
import pl.kadrastats.staffstats.storage.DatabaseManager;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * StaffStats – jedna komenda /staff
 * Subkomendy admina:
 *   reload
 *   reset <gracz>
 *   webhook <test|daily|schedule|<nick>>
 *   help
 *
 * Użycie gracza:
 *   /staff              – otwiera GUI (paginowane)
 *   /staff <nick>       – szybki raport w chacie
 *   /staff top [ranga]  – ranking kadry (czas aktywny)
 */
public class StaffCommand implements CommandExecutor, TabCompleter {

    private final StaffStatsPlugin plugin;
    private final ActivityTracker tracker;
    private final DatabaseManager db;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    /** Cache nazw graczy do tab-complete (30 s) – wcześniej każde TAB uderzało w bazę na main thread. */
    private List<String> namesCache = new ArrayList<>();
    private long namesCacheAt = 0;

    public StaffCommand(StaffStatsPlugin plugin, ActivityTracker tracker, DatabaseManager db) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.db = db;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("staffstats.view")) {
            sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") +
                    plugin.getConfig().getString("messages.no-permission", "&cBrak uprawnień.")));
            return true;
        }

        // sprzątanie cooldownów (mapa rosła w nieskończoność)
        long nowSec = System.currentTimeMillis() / 1000;
        if (cooldown.size() > 200) {
            cooldown.values().removeIf(t -> nowSec - t > 300);
        }

        if (args.length == 0) {
            return openGui(sender, label);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // --- ADMIN ---
        if (sub.equals("reload") && sender.hasPermission("staffstats.admin")) {
            plugin.reloadPlugin();
            sender.sendMessage(color("&aStaffStats przeładowano."));
            return true;
        }

        if (sub.equals("help")) {
            sendHelp(sender, label);
            return true;
        }

        if (sub.equals("top")) {
            return handleTop(sender, args);
        }

        if (sub.equals("reset") && sender.hasPermission("staffstats.admin")) {
            if (args.length < 2) {
                sender.sendMessage(color("&cUżycie: /" + label + " reset <gracz>"));
                return true;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            boolean ok = db.resetPlayer(op.getUniqueId());
            sender.sendMessage(ok ? color("&aZresetowano " + args[1]) : color("&cBrak danych."));
            return true;
        }

        if (sub.equals("webhook") && sender.hasPermission("staffstats.admin")) {
            return handleWebhook(sender, label, args);
        }

        // --- /staff <nick> = szybki raport ---
        // Jeśli pierwszy argument nie jest znaną subkomendą, traktuj jako nick
        if (!isKnownSubcommand(sub)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            sendQuickReport(sender, target.getUniqueId());
            return true;
        }

        // fallback – otwórz GUI
        return openGui(sender, label);
    }

    /** /staff top [ranga] – ranking kadry po czasie aktywnym (z doliczeniem sesji live). */
    private boolean handleTop(CommandSender sender, String[] args) {
        String group = args.length > 1 ? args[1] : null;
        int limit = Math.max(1, Math.min(15, plugin.getConfig().getInt("performance.max-top-results", 54)));
        List<StaffRecord> top = db.getTop(group, limit);

        sender.sendMessage(color(plugin.getConfig().getString("messages.top-header",
                "&8&m----------&r &b&lTOP KADRY &8&m----------")));
        if (group != null) sender.sendMessage(color("&7Ranga: &f" + group));
        if (top.isEmpty()) {
            sender.sendMessage(color("&7Brak danych" + (group != null ? " dla rangi " + group : "") + "."));
        } else {
            int i = 1;
            for (StaffRecord r : top) {
                ActivityTracker.Session live = tracker.getSession(r.uuid);
                long play = r.totalPlaytimeMs + (live != null ? live.currentPlaytime() : 0);
                long afk = r.totalAfkMs + (live != null ? live.currentAfk() : 0);
                long active = Math.max(0, play - afk);
                String status = live != null ? (live.isAfk() ? " &c💤" : " &a●") : "";
                sender.sendMessage(color("&e" + i++ + ". &f" + r.name
                        + " &8[" + (r.group != null ? r.group : "?") + "]&7 – &b"
                        + StaffRecord.formatDuration(active) + status));
            }
        }
        sender.sendMessage(color("&8&m-------------------------------"));
        return true;
    }

    private boolean handleWebhook(CommandSender sender, String label, String[] args) {
        var webhook = plugin.getWebhook();
        if (args.length < 2) {
            sender.sendMessage(color("&eUżycie: /" + label + " webhook <test|daily|schedule|<nick>>"));
            sender.sendMessage(color("&7Webhook enabled: " + (webhook != null && webhook.isEnabled() ? "&aTAK" : "&cNIE")));
            var next = plugin.getNextWebhookRun();
            if (next != null) sender.sendMessage(color("&7Następny auto-raport: &f" + next + " Europe/Warsaw"));
            var last = plugin.getLastWebhookSentDate();
            sender.sendMessage(color("&7Ostatnio wysłano: " + (last != null ? "&a" + last : "&7nigdy")));
            return true;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);

        if (!mode.equals("schedule") && !mode.equals("test") && (webhook == null || !webhook.isEnabled())) {
            sender.sendMessage(color("&cWebhook wyłączony w config.yml (webhook.enabled=false) lub brak URL"));
            return true;
        }

        switch (mode) {
            case "daily" -> {
                sender.sendMessage(color("&7Wysyłam dzienny raport..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> webhook.sendDailySummary(db));
                sender.sendMessage(color("&aWysłano daily webhook."));
                return true;
            }
            case "test" -> {
                sender.sendMessage(color("&7Wysyłam testowy webhook..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> webhook.sendTest());
                sender.sendMessage(color("&aTest webhook wysłany – sprawdź Discord."));
                return true;
            }
            case "schedule" -> {
                var next = plugin.getNextWebhookRun();
                var last = plugin.getLastWebhookSentDate();
                sender.sendMessage(color("&b--- Webhook Schedule ---"));
                sender.sendMessage(color("&7Enabled: " + (webhook != null && webhook.isEnabled() ? "&aTAK" : "&cNIE")));
                sender.sendMessage(color("&7Godzina: &f" + plugin.getConfig().getInt("webhook.daily-summary-hour", 22) + ":" +
                        String.format("%02d", plugin.getConfig().getInt("webhook.daily-summary-minute", 0)) + " Europe/Warsaw"));
                sender.sendMessage(color("&7Następny: &e" + (next != null ? next.toString() : "brak")));
                sender.sendMessage(color("&7Ostatnio wysłano: &f" + (last != null ? last.toString() : "nigdy")));
                sender.sendMessage(color("&7Teraz: &f" + java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Warsaw"))));
                return true;
            }
            default -> {
                // /staff webhook <nick>
                OfflinePlayer op = Bukkit.getOfflinePlayer(mode);
                UUID uuid = op.getUniqueId();
                String targetName = args[1];
                sender.sendMessage(color("&7Wysyłam raport gracza &e" + targetName + " &7na Discord..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> webhook.sendPlayerReport(uuid, sender.getName()));
                sender.sendMessage(color("&aRaport wysłany."));
                return true;
            }
        }
    }

    private boolean openGui(CommandSender sender, String label) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Użyj: /" + label + " <nick> | /" + label + " top  (konsola nie ma GUI)");
            List<StaffRecord> top = db.getTop(null, 10);
            sender.sendMessage("=== TOP KADRA ===");
            int i = 1;
            for (StaffRecord r : top) {
                sender.sendMessage(i++ + ". " + r.name + " – " + StaffRecord.formatDuration(r.activeMs()));
            }
            return true;
        }

        // cooldown anty-spam
        long now = System.currentTimeMillis() / 1000;
        long last = cooldown.getOrDefault(p.getUniqueId(), 0L);
        int cd = plugin.getConfig().getInt("performance.command-cooldown-seconds", 2);
        if (now - last < cd && !p.hasPermission("staffstats.admin")) {
            p.sendMessage(color("&cPoczekaj " + (cd - (now - last)) + "s"));
            return true;
        }
        cooldown.put(p.getUniqueId(), now);

        p.sendMessage(color(plugin.getConfig().getString("messages.gui-opening", "&7Otwieram panel kadry...")));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var inv = StaffGui.build(plugin, 0);
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(inv));
        });
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(color("&8&m----------&r &bStaffStats &8&m----------"));
        sender.sendMessage(color("&e/" + label + " &7– otwórz GUI kadry"));
        sender.sendMessage(color("&e/" + label + " <nick> &7– szybki raport w chacie"));
        sender.sendMessage(color("&e/" + label + " top [ranga] &7– ranking kadry"));
        if (sender.hasPermission("staffstats.admin")) {
            sender.sendMessage(color("&cAdmin:"));
            sender.sendMessage(color(" &7/" + label + " reload &8- przeładuj config"));
            sender.sendMessage(color(" &7/" + label + " reset <gracz> &8- wyczyść statystyki"));
            sender.sendMessage(color(" &7/" + label + " webhook test &8- test Discorda"));
            sender.sendMessage(color(" &7/" + label + " webhook daily &8- wymuś raport 24h"));
            sender.sendMessage(color(" &7/" + label + " webhook schedule &8- kiedy następny raport"));
            sender.sendMessage(color(" &7/" + label + " webhook <nick> &8- raport gracza na DC"));
        }
        sender.sendMessage(color("&8&m----------------------------------"));
    }

    private void sendQuickReport(CommandSender sender, UUID uuid) {
        StaffRecord rec = db.getRecord(uuid);
        ActivityTracker.Session live = tracker.getSession(uuid);
        if (rec == null && live == null) {
            sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") +
                    plugin.getConfig().getString("messages.player-not-tracked", "&cTen gracz nie jest w kadrze.")));
            return;
        }

        String name = rec != null ? rec.name : Bukkit.getOfflinePlayer(uuid).getName();
        if (name == null) name = uuid.toString().substring(0, 8);
        String group = rec != null ? rec.group : (live != null ? live.groupAtJoin : "unknown");

        long totalPlay = rec != null ? rec.totalPlaytimeMs : 0;
        long totalAfk = rec != null ? rec.totalAfkMs : 0;
        long lastLogin = rec != null ? rec.lastLogin : 0;
        long lastLogout = rec != null ? rec.lastLogout : 0;
        int sessions = rec != null ? rec.sessionCount : 0;

        if (live != null) {
            totalPlay += live.currentPlaytime();
            totalAfk += live.currentAfk();
            lastLogin = Math.max(lastLogin, live.loginTime);
        }

        long active = Math.max(0, totalPlay - totalAfk);
        double afkPerc = totalPlay > 0 ? (totalAfk * 100.0 / totalPlay) : 0;

        sender.sendMessage(color(plugin.getConfig().getString("messages.report-header", "&8&m--------&r &b&lRAPORT &8&m--------")));
        sender.sendMessage(color("&bRaport gracza: &f" + name));
        sender.sendMessage(color("&7Ranga: &f" + (group != null ? group : "unknown")));
        sender.sendMessage(color("&7Online łącznie: &a" + StaffRecord.formatDuration(totalPlay)));
        sender.sendMessage(color("&7AFK: &c" + StaffRecord.formatDuration(totalAfk) +
                String.format(Locale.US, " &8(%.1f%%)", afkPerc)));
        sender.sendMessage(color("&7Aktywny: &b" + StaffRecord.formatDuration(active)));
        sender.sendMessage(color("&7Sesje: &e" + sessions + (live != null ? " &a(+1 online)" : "")));
        if (sessions > 0) {
            sender.sendMessage(color("&7Śr. sesja: &f" + StaffRecord.formatDuration(totalPlay / Math.max(1, sessions))));
        }

        // LibertyBans – kary wydane (widok wg rangi gracza)
        List<String> punishTypes = pl.kadrastats.staffstats.util.PunishDisplay.typesFor(plugin, group);
        if (!punishTypes.isEmpty()) {
            java.util.Map<String, Long> counts = db.getPunishmentCounts(uuid);
            sender.sendMessage(color("&7▸ Kary wydane:"));
            for (String line : pl.kadrastats.staffstats.util.PunishDisplay.chatLines(counts, punishTypes)) {
                sender.sendMessage(color("    " + line));
            }
        }

        sender.sendMessage(color("&7Ostatnie logowanie: &f" + StaffRecord.formatDate(lastLogin) + " &8(" + StaffRecord.formatAgo(lastLogin) + ")"));
        if (live != null) {
            sender.sendMessage(color("&7Status: &a🟢 ONLINE" + (live.isAfk() ? " &c[AFK]" : "")));
            sender.sendMessage(color("&7Aktualna sesja: &f" + StaffRecord.formatDuration(live.currentPlaytime())));
        } else {
            sender.sendMessage(color("&7Ostatnie wylogowanie: &f" + StaffRecord.formatDate(lastLogout) + " &8(" + StaffRecord.formatAgo(lastLogout) + ")"));
            sender.sendMessage(color("&7Status: &c🔴 offline"));
        }
        sender.sendMessage(color("&8&m------------------------------"));
    }

    private boolean isKnownSubcommand(String s) {
        return s.equalsIgnoreCase("reload") ||
               s.equalsIgnoreCase("reset") ||
               s.equalsIgnoreCase("webhook") ||
               s.equalsIgnoreCase("help") ||
               s.equalsIgnoreCase("top");
    }

    private String color(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    // ---- TAB COMPLETE ----
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            out.add("help");
            out.add("top");
            if (sender.hasPermission("staffstats.admin")) {
                out.add("reload");
                out.add("reset");
                out.add("webhook");
            }
            out.addAll(knownNames());
            String low = args[0].toLowerCase(Locale.ROOT);
            return out.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(low))
                    .sorted()
                    .distinct()
                    .limit(25)
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String low = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("top")) {
                return tracker.getTrackedGroups().stream()
                        .sorted()
                        .filter(g -> g.startsWith(low))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("reset") && sender.hasPermission("staffstats.admin")) {
                return knownNames().stream()
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(low))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("webhook") && sender.hasPermission("staffstats.admin")) {
                List<String> opts = new ArrayList<>(List.of("test", "daily", "schedule"));
                opts.addAll(knownNames());
                return opts.stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(low))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    /** Nazwy z cache 30 s (gracze online + baza) – bez zapytania do SQLite przy każdym TAB. */
    private List<String> knownNames() {
        long now = System.currentTimeMillis();
        if (now - namesCacheAt > 30_000L) {
            List<String> out = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            try {
                db.getAll(50).forEach(r -> { if (r.name != null) out.add(r.name); });
            } catch (Exception ignored) {}
            namesCache = out;
            namesCacheAt = now;
        }
        return namesCache;
    }
}
