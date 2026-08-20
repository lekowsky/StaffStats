package pl.kadrastats.staffstats.util;

import pl.kadrastats.staffstats.StaffStatsPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wyświetlanie statystyk kar (LibertyBans).
 * Kluczowa zasada: LICZONE są wszystkie typy kar niezależnie od rangi (dane są stałe),
 * a rangą filtrujemy dopiero WYŚWIETLANIE (libertybans.group-punishment-view).
 * Dzięki temu zmiana rangi nigdy nie psuje danych – zmienia się tylko widok.
 */
public final class PunishDisplay {

    /** Kolejność i styl wyświetlania: typ -> ikona + kolor liczby. */
    private static final String[][] STYLE = {
            // typ, ikona+kolor etykiety
            {"ban",  "§c🚫"},
            {"mute", "§e🔇"},
            {"kick", "§6👢"},
            {"warn", "§6⚠"}
    };

    private PunishDisplay() {}

    /** Typy kar pokazywane dla danej rangi (config: libertybans.group-punishment-view.<ranga>). */
    public static List<String> typesFor(StaffStatsPlugin plugin, String group) {
        String g = group != null && !group.isBlank() ? group.toLowerCase(Locale.ROOT) : "default";
        List<String> list = plugin.getConfig().getStringList("libertybans.group-punishment-view." + g);
        if (list.isEmpty()) {
            list = plugin.getConfig().getStringList("libertybans.group-punishment-view.default");
        }
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) out.add(s.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Kompaktowa linia do lore GUI, np. "🚫 3 §8| 🔇 12 §8| 👢 5" (tylko typy z listy, w stałej kolejności). */
    public static String loreLine(Map<String, Long> counts, List<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String[] style : STYLE) {
            if (!types.contains(style[0])) continue;
            if (sb.length() > 0) sb.append(" §8| ");
            sb.append(style[1]).append("§f ").append(counts.getOrDefault(style[0], 0L));
        }
        return sb.toString();
    }

    /** Linie do raportu na czacie (pełne nazwy). */
    public static List<String> chatLines(Map<String, Long> counts, List<String> types) {
        List<String> out = new ArrayList<>();
        for (String[] style : STYLE) {
            if (!types.contains(style[0])) continue;
            out.add(style[1] + label(style[0]) + ": §f" + counts.getOrDefault(style[0], 0L));
        }
        return out;
    }

    /** Pełna polska nazwa typu kary. */
    public static String label(String type) {
        return switch (type) {
            case "ban" -> "§lBany";
            case "mute" -> "§lMute";
            case "kick" -> "§lKicke";
            case "warn" -> "§lWarny";
            default -> type;
        };
    }
}
