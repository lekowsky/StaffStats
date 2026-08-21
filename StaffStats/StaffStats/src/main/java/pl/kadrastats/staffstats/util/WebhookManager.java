package pl.kadrastats.staffstats.util;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.DatabaseManager;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class WebhookManager {

    private final StaffStatsPlugin plugin;
    private ExecutorService pool;
    private String url;
    private boolean enabled;
    private String username;
    private String avatar;

    public WebhookManager(StaffStatsPlugin plugin) {
        this.plugin = plugin;
        reload();
        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StaffStats-Webhook");
            t.setDaemon(true);
            return t;
        });
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("webhook.enabled", false);
        url = plugin.getConfig().getString("webhook.url", "");
        username = plugin.getConfig().getString("webhook.username", "StaffStats");
        avatar = plugin.getConfig().getString("webhook.avatar-url", "");
        if (enabled && (url == null || url.isBlank() || url.contains("TWOJE_ID"))) {
            plugin.getLogger().warning("Webhook włączony ale URL jest pusty / przykładowy – wyłączam.");
            enabled = false;
        }
    }

    public boolean isEnabled() { return enabled && url != null && url.startsWith("http"); }

    public void sendJoin(String player, String group) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("webhook.send-on-staff-join", true)) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🟢 " + player + " dołączył");
        embed.addProperty("description", "**Ranga:** `" + group + "`\n**Czas:** <t:" + Instant.now().getEpochSecond() + ":F>");
        embed.addProperty("color", plugin.getConfig().getInt("webhook.color-join", 65280));
        sendEmbed(embed);
    }

    public void sendQuit(ActivityTracker.SessionResult res) {
        if (!isEnabled() || !plugin.getConfig().getBoolean("webhook.send-on-staff-quit", true)) return;
        int min = plugin.getConfig().getInt("webhook.min-quit-session-minutes", 5);
        if (res.playtimeMs() < min * 60_000L) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🔴 " + res.name() + " opuścił serwer");
        String desc = "**Ranga:** `" + res.group() + "`\n" +
                "**Sesja:** " + StaffRecord.formatDuration(res.playtimeMs()) + "\n" +
                "**AFK w sesji:** " + StaffRecord.formatDuration(res.afkMs()) + "\n" +
                "**Aktywny:** " + StaffRecord.formatDuration(Math.max(0, res.playtimeMs() - res.afkMs()));
        embed.addProperty("description", desc);
        embed.addProperty("color", plugin.getConfig().getInt("webhook.color-quit", 16711680));
        embed.addProperty("timestamp", Instant.now().toString());
        sendEmbed(embed);
    }

    public void sendTest() {
        if (!isEnabled()) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "✅ StaffStats Webhook Test");
        embed.addProperty("description", "Połączenie działa poprawnie!\n\n**Serwer:** " + plugin.getServer().getName() + "\n**Wersja pluginu:** " + plugin.getDescription().getVersion() + "\n**Czas:** <t:" + Instant.now().getEpochSecond() + ":F>");
        embed.addProperty("color", 5763719);
        embed.addProperty("timestamp", Instant.now().toString());
        sendEmbed(embed, "Test wykonany komendą /staff webhook test");
    }

    public void sendPlayerReport(UUID uuid, String requester) {
        if (!isEnabled()) return;
        try {
            var db = plugin.getDatabase();
            var tracker = plugin.getActivityTracker();
            StaffRecord rec = db.getRecord(uuid);
            var live = tracker.getSession(uuid);
            String name = rec != null ? rec.name : org.bukkit.Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0,8);
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

            JsonObject embed = new JsonObject();
            embed.addProperty("title", "📋 Raport gracza: " + name);
            embed.addProperty("color", live != null ? 65280 : 3447003);
            StringBuilder desc = new StringBuilder();
            desc.append("**Ranga:** `").append(group).append("`\n");
            desc.append("**Online łącznie:** ").append(StaffRecord.formatDuration(totalPlay)).append("\n");
            desc.append("**AFK:** ").append(StaffRecord.formatDuration(totalAfk))
                .append(String.format(java.util.Locale.US, " (%.1f%%)", afkPerc)).append("\n");
            desc.append("**Aktywny:** ").append(StaffRecord.formatDuration(active)).append("\n\n");
            desc.append("**Sesje:** ").append(sessions).append(live != null ? " (+1 online)" : "").append("\n");
            if (sessions > 0) desc.append("**Śr. sesja:** ").append(StaffRecord.formatDuration(totalPlay / Math.max(1, sessions))).append("\n");
            // LibertyBans – kary wydane (widok wg rangi)
            List<String> punishTypes = pl.kadrastats.staffstats.util.PunishDisplay.typesFor(plugin, group);
            if (!punishTypes.isEmpty()) {
                java.util.Map<String, Long> counts = db.getPunishmentCounts(uuid);
                desc.append("**Kary wydane:** ").append(pl.kadrastats.staffstats.util.PunishDisplay.loreLine(counts, punishTypes)).append("\n");
            }
            desc.append("\n**Ostatnie logowanie:** <t:").append(lastLogin/1000).append(":F>\n");
            if (live != null) {
                desc.append("**Status:** 🟢 ONLINE");
                if (live.isAfk()) desc.append(" [AFK]");
                desc.append("\n**Aktualna sesja:** ").append(StaffRecord.formatDuration(live.currentPlaytime())).append("\n");
            } else {
                desc.append("**Ostatnie wylogowanie:** <t:").append(lastLogout/1000).append(":R>\n");
                desc.append("**Status:** 🔴 offline\n");
            }
            embed.addProperty("description", desc.toString());
            embed.addProperty("timestamp", Instant.now().toString());

            String footer = "Wysłane przez " + requester + " • /staff webhook " + name;
            sendEmbed(embed, footer);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "sendPlayerReport failed", e);
        }
    }

    public void sendDailySummary(DatabaseManager db) {
        if (!isEnabled()) return;
        long until = System.currentTimeMillis();
        long since = until - 24L * 3600_000L;

        // Pobierz statystyki DZIENNE z tabeli staff_sessions
        List<DatabaseManager.DailyStat> daily = db.getDailyStats(since, until, 25);

        // Dołóż aktualnie online graczy (ich sesja jeszcze nie zapisana w DB)
        try {
            var tracker = plugin.getActivityTracker();
            if (tracker != null) {
                var lp = plugin.getLuckPermsHook();
                java.util.Map<java.util.UUID, DatabaseManager.DailyStat> map = new java.util.HashMap<>();
                for (var d : daily) map.put(d.uuid, d);
                // przejrzyj sesje live
                // niestety sessions jest private – użyjemy Bukkit.getOnlinePlayers + tracker.getSession
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    var s = tracker.getSession(p.getUniqueId());
                    if (s == null) continue;
                    // czy ta osoba jest w kadrze?
                    if (!tracker.getTrackedGroups().contains(tracker.resolveGroup(p).toLowerCase(java.util.Locale.ROOT)) && !p.isOp()) continue;
                    long sessionStart = Math.max(s.loginTime, since);
                    long play = System.currentTimeMillis() - sessionStart;
                    if (play <= 0) continue;
                    long afk = s.currentAfk();
                    // jeśli sesja zaczęła się przed oknem 24h, proporcjonalnie przytnij afk
                    if (s.loginTime < since && (System.currentTimeMillis() - s.loginTime) > 0) {
                        double ratio = (double) play / (System.currentTimeMillis() - s.loginTime);
                        afk = (long)(afk * ratio);
                    }
                    DatabaseManager.DailyStat existing = map.get(p.getUniqueId());
                    if (existing != null) {
                        // scal – dodaj live
                        map.put(p.getUniqueId(), new DatabaseManager.DailyStat(
                                existing.uuid, existing.name, existing.group,
                                existing.playtimeMs + play,
                                existing.afkMs + afk,
                                existing.sessions + 1,
                                System.currentTimeMillis()
                        ));
                    } else {
                        String grp = tracker.resolveGroup(p);
                        map.put(p.getUniqueId(), new DatabaseManager.DailyStat(
                                p.getUniqueId(), p.getName(), grp,
                                play, afk, 1, System.currentTimeMillis()
                        ));
                    }
                }
                daily = new java.util.ArrayList<>(map.values());
                daily.sort((a,b) -> Long.compare(b.activeMs(), a.activeMs()));
            }
        } catch (Exception ignored) {}

        JsonObject embed = new JsonObject();
        int hh = plugin.getConfig().getInt("webhook.daily-summary-hour", 22);
        int mm = plugin.getConfig().getInt("webhook.daily-summary-minute", 0);
        embed.addProperty("title", "📊 Dzienny raport kadry – " + String.format("%02d:%02d", hh, mm));
        embed.addProperty("color", 3447003);
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Warsaw"));

        sb.append("**Okres:** <t:").append(since/1000).append(":f> – <t:").append(until/1000).append(":f>\n");
        sb.append("**Strefa:** Europe/Warsaw\n\n");

        if (daily.isEmpty()) {
            sb.append("_Nikt z kadry nie był aktywny w ciągu ostatnich 24h._\n");
        } else {
            sb.append("**Aktywność w ciągu ostatnich 24h:**\n\n");
            int i = 1;
            long totalDayMs = 0;
            long totalAfkMs = 0;
            for (DatabaseManager.DailyStat r : daily) {
                if (i > 20) break;
                long active = r.activeMs();
                totalDayMs += r.playtimeMs;
                totalAfkMs += r.afkMs;
                double afkPerc = r.playtimeMs > 0 ? (r.afkMs * 100.0 / r.playtimeMs) : 0;
                // bez emotki online – czysty raport
                sb.append("**").append(i).append(". ").append(r.name).append("** `[").append(r.group != null ? r.group : "?").append("]`\n");
                sb.append("⏱ **").append(StaffRecord.formatDuration(r.playtimeMs)).append("**");
                if (r.afkMs > 60000) {
                    sb.append(" | 💤 ").append(StaffRecord.formatDuration(r.afkMs))
                      .append(String.format(java.util.Locale.US, " (%.0f%%)", afkPerc));
                }
                sb.append("\n");
                sb.append("⚡ aktywny: **").append(StaffRecord.formatDuration(active)).append("**");
                sb.append(" | sesji: ").append(r.sessions).append("\n\n");
                i++;
            }
            // podsumowanie
            sb.append("**Suma kadry (24h):**\n");
            sb.append("⏱ ").append(StaffRecord.formatDuration(totalDayMs));
            sb.append(" | 💤 ").append(StaffRecord.formatDuration(totalAfkMs));
            sb.append(" | ⚡ ").append(StaffRecord.formatDuration(Math.max(0, totalDayMs - totalAfkMs))).append("\n\n");
        }

        // TOP all-time na dole (krótko) - aktywny czas, bez AFK
        List<StaffRecord> top = db.getTop(null, 5);
        if (!top.isEmpty()) {
            sb.append("**🏆 TOP all-time:**\n");
            int t = 1;
            for (StaffRecord r : top) {
                sb.append(t).append(". ").append(r.name).append(" – ").append(StaffRecord.formatDuration(r.activeMs())).append("\n");
                t++;
                if (sb.length() > 3300) break; // Discord limit ~4096
            }
            sb.append("\n");
        }

        // Lista osób, które nie logowały się dłużej niż 3 dni
        long inactiveCutoff = until - 3L * 24L * 3600_000L;
        List<StaffRecord> inactive = db.getInactiveSince(inactiveCutoff, 10);
        if (!inactive.isEmpty() && sb.length() < 3600) {
            sb.append("**⏳ Nieobecni >3 dni:**\n");
            int shown = 0;
            for (StaffRecord r : inactive) {
                // Jeśli ktoś aktualnie jest online, nie pokazuj go jako nieobecnego.
                try {
                    var tracker = plugin.getActivityTracker();
                    if (tracker != null && tracker.getSession(r.uuid) != null) continue;
                } catch (Exception ignored) {}
                sb.append("• ").append(r.name);
                if (r.group != null && !r.group.isBlank()) sb.append(" `[").append(r.group).append("]`");
                sb.append(" – ostatnio: ").append(StaffRecord.formatDate(r.lastLogin))
                  .append(" (").append(StaffRecord.formatAgo(r.lastLogin)).append(")\n");
                shown++;
                if (shown >= 10 || sb.length() > 3950) break;
            }
            if (shown == 0) {
                int start = sb.lastIndexOf("**⏳ Nieobecni >3 dni:**\n");
                if (start >= 0) sb.delete(start, sb.length());
            }
        }

        embed.addProperty("description", sb.toString());
        embed.addProperty("timestamp", Instant.now().toString());
        sendEmbed(embed, "Raport dzienny " + fmt.format(Instant.now()) + " | StaffStats");
    }

    private void sendEmbed(JsonObject embed) { sendEmbed(embed, null); }

    private void sendEmbed(JsonObject embed, String footerText) {
        if (!isEnabled()) return;
        pool.submit(() -> {
            HttpURLConnection con = null;
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("username", username);
                if (avatar != null && !avatar.isBlank()) payload.addProperty("avatar_url", avatar);
                JsonArray embeds = new JsonArray();
                if (footerText != null) {
                    JsonObject footer = new JsonObject();
                    footer.addProperty("text", footerText);
                    embed.add("footer", footer);
                }
                embeds.add(embed);
                payload.add("embeds", embeds);

                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("User-Agent", "StaffStats/" + plugin.getDescription().getVersion() + " (+Purpur)");
                try (OutputStream os = con.getOutputStream()) { os.write(data); }
                int code = con.getResponseCode();
                if (code < 200 || code >= 300) {
                    plugin.getLogger().warning("Webhook HTTP " + code + " – sprawdź URL w config.yml");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Webhook send failed", e);
            } finally {
                if (con != null) con.disconnect();
            }
        });
    }

    /**
     * Podsumowanie TYGODNIA – wysyłane SYNCHRONICZNIE (reset tygodnia może zaraz
     * zrestartować serwer, webhook musi zdążyć przed wyłączeniem procesu).
     */
    public void sendWeeklySummary(List<StaffRecord> top,
                                   java.util.Map<UUID, java.util.Map<String, Long>> punish,
                                   long fromMs, long toMs, long nextResetMs) {
        if (!isEnabled()) return;
        try {
            var db = plugin.getDatabase();
            var tracker = plugin.getActivityTracker();

            // uuid -> nick (do sekcji kar, których liderzy mogą nie być w top aktywności)
            java.util.Map<UUID, String> names = new java.util.HashMap<>();
            for (StaffRecord r : db.getAll(1000)) names.put(r.uuid, r.name);

            JsonObject embed = new JsonObject();
            embed.addProperty("title", "📊 Podsumowanie tygodnia kadry");
            embed.addProperty("color", 15105570);
            StringBuilder sb = new StringBuilder();
            sb.append("**Okres:** <t:").append(fromMs / 1000).append(":f> – <t:").append(toMs / 1000).append(":f>\n");
            sb.append("**Strefa:** Europe/Warsaw\n");
            sb.append("🔄 *Statystyki tygodnia zostały zresetowane.*\n");

            if (top.isEmpty()) {
                sb.append("\n_Nikt z kadry nie był aktywny w tym tygodniu._\n");
            } else {
                sb.append("\n**⚡ TOP aktywność (tydzień):**\n");
                int i = 1;
                long sumPlay = 0, sumAfk = 0;
                for (StaffRecord r : top) {
                    if (i > 15) break;
                    sumPlay += r.totalPlaytimeMs;
                    sumAfk += r.totalAfkMs;
                    double afkPerc = r.totalPlaytimeMs > 0 ? r.totalAfkMs * 100.0 / r.totalPlaytimeMs : 0;
                    sb.append("**").append(i++).append(".** ").append(r.name)
                            .append(" `[").append(r.group != null ? r.group : "?").append("]`")
                            .append(" ⚡ **").append(StaffRecord.formatDuration(r.activeMs())).append("**")
                            .append(" | ⏱ ").append(StaffRecord.formatDuration(r.totalPlaytimeMs))
                            .append(String.format(java.util.Locale.US, " | 💤 %.0f%%", afkPerc))
                            .append(" | sesji: ").append(r.sessionCount).append("\n");
                    if (sb.length() > 3000) break;
                }
                sb.append("\n**Suma kadry:** ⏱ ").append(StaffRecord.formatDuration(sumPlay))
                        .append(" | 💤 ").append(StaffRecord.formatDuration(sumAfk))
                        .append(" | ⚡ ").append(StaffRecord.formatDuration(Math.max(0, sumPlay - sumAfk))).append("\n");
            }

            // TOP kary tygodnia
            if (!punish.isEmpty()) {
                List<java.util.Map.Entry<UUID, java.util.Map<String, Long>>> sorted = new java.util.ArrayList<>(punish.entrySet());
                sorted.sort((a, b) -> Long.compare(
                        b.getValue().values().stream().mapToLong(Long::longValue).sum(),
                        a.getValue().values().stream().mapToLong(Long::longValue).sum()));
                sb.append("\n**⚖ TOP kary (tydzień):**\n");
                int shown = 0;
                for (var entry : sorted) {
                    if (shown >= 5 || sb.length() > 3700) break;
                    var counts = entry.getValue();
                    long total = counts.values().stream().mapToLong(Long::longValue).sum();
                    if (total <= 0) continue;
                    String name = names.getOrDefault(entry.getKey(), entry.getKey().toString().substring(0, 8));
                    sb.append("**").append(++shown).append(".** ").append(name)
                            .append(" – razem **").append(total).append("** • ")
                            .append(pl.kadrastats.staffstats.util.PunishDisplay.loreLineDiscord(counts,
                                    List.of("ban", "mute", "kick", "warn"))).append("\n");
                }
                if (shown == 0) sb.append("_Brak kar w tym tygodniu._\n");
            }

            // Nieobecni przez cały tydzień
            try {
                List<StaffRecord> inactive = db.getInactiveSince(fromMs, 10);
                int shown = 0;
                for (StaffRecord r : inactive) {
                    if (tracker != null && tracker.getSession(r.uuid) != null) continue;
                    if (shown == 0) sb.append("\n**😴 Nieobecni cały tydzień:**\n");
                    sb.append("• ").append(r.name);
                    if (r.group != null && !r.group.isBlank()) sb.append(" `[").append(r.group).append("]`");
                    sb.append(" – ostatnio: ").append(StaffRecord.formatDate(r.lastLogin)).append("\n");
                    shown++;
                    if (shown >= 10 || sb.length() > 3900) break;
                }
            } catch (Exception ignored) {}

            sb.append("\n**Następny reset:** <t:").append(nextResetMs / 1000).append(":F>");
            embed.addProperty("description", sb.toString());
            embed.addProperty("timestamp", Instant.now().toString());
            sendEmbedSync(embed, "Weekly reset • StaffStats");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "sendWeeklySummary failed", e);
        }
    }

    /** Wysłanie embeda bez kolejki (inline) – dla webhooków, które MUSZĄ zdążyć przed restartem. */
    private void sendEmbedSync(JsonObject embed, String footerText) {
        HttpURLConnection con = null;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("username", username);
            if (avatar != null && !avatar.isBlank()) payload.addProperty("avatar_url", avatar);
            JsonArray embeds = new JsonArray();
            if (footerText != null) {
                JsonObject footer = new JsonObject();
                footer.addProperty("text", footerText);
                embed.add("footer", footer);
            }
            embeds.add(embed);
            payload.add("embeds", embeds);

            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("User-Agent", "StaffStats/" + plugin.getDescription().getVersion() + " (+Purpur)");
            try (OutputStream os = con.getOutputStream()) { os.write(data); }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                plugin.getLogger().warning("Weekly webhook HTTP " + code + " – sprawdź URL w config.yml");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Webhook sync send failed", e);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    public void shutdown() {
        if (pool == null) return;
        // czekaj do 3s na wysłanie zgromadzonych webhooków (shutdownNow gubiłby kolejkę)
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
