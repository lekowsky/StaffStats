package pl.kadrastats.staffstats.storage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public class StaffRecord {
    public final UUID uuid;
    public final String name;
    public final String group;
    public final long totalPlaytimeMs;
    public final long totalAfkMs;
    public final long lastLogin;
    public final long lastLogout;
    public final int sessionCount;
    public final long firstSeen;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault()).withLocale(Locale.forLanguageTag("pl-PL"));

    public StaffRecord(UUID uuid, String name, String group, long totalPlaytimeMs, long totalAfkMs,
                       long lastLogin, long lastLogout, int sessionCount, long firstSeen) {
        this.uuid = uuid;
        this.name = name;
        this.group = group;
        this.totalPlaytimeMs = totalPlaytimeMs;
        this.totalAfkMs = totalAfkMs;
        this.lastLogin = lastLogin;
        this.lastLogout = lastLogout;
        this.sessionCount = sessionCount;
        this.firstSeen = firstSeen;
    }

    public long activeMs() { return Math.max(0, totalPlaytimeMs - totalAfkMs); }

    public static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static String formatDate(long epochMs) {
        if (epochMs <= 0) return "nigdy";
        return FMT.format(Instant.ofEpochMilli(epochMs));
    }

    public static String formatAgo(long epochMs) {
        if (epochMs <= 0) return "nigdy";
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60000) return "przed chwilą";
        return formatDuration(diff) + " temu";
    }
}
