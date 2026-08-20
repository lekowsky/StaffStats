package pl.kadrastats.staffstats.gui;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class StaffGui {

    public static final String TITLE_KEY = "KADRA";

    public static Inventory build(StaffStatsPlugin plugin) {
        int rows = Math.max(3, Math.min(6, plugin.getConfig().getInt("gui.rows", 6)));
        String title = color(plugin.getConfig().getString("gui.title", "&8&lKADRA &7- Statystyki"));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);

        var db = plugin.getDatabase();
        var tracker = plugin.getActivityTracker();

        List<StaffRecord> all = db.getAll(54);
        // sort by group priority then playtime
        all.sort((a,b) -> {
            int pa = getPriority(plugin, a.group);
            int pb = getPriority(plugin, b.group);
            if (pa != pb) return Integer.compare(pb, pa);
            return Long.compare(b.activeMs(), a.activeMs());
        });

        int slot = 0;
        for (StaffRecord rec : all) {
            if (slot >= inv.getSize()) break;
            inv.setItem(slot++, createHead(plugin, tracker, rec));
        }

        if (all.isEmpty()) {
            // info item center
            ItemStack info = new ItemStack(Material.PAPER);
            var im = info.getItemMeta();
            im.setDisplayName("§cBrak danych kadry");
            List<String> lore = new ArrayList<>();
            lore.add("§7Nie znaleziono żadnych administratorów w bazie.");
            lore.add("");
            lore.add("§ePoczekaj aż ktoś z kadry wejdzie na serwer,");
            lore.add("§ealbo dodaj ręcznie przez /staff <nick>");
            lore.add("");
            lore.add("§8Tracked groups: " + String.join(", ", tracker.getTrackedGroups()));
            im.setLore(lore);
            info.setItemMeta(im);
            inv.setItem(inv.getSize()/2, info);
        }

        // fill empty with glass
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var meta = filler.getItemMeta();
        meta.setDisplayName("§8");
        filler.setItemMeta(meta);
        for (int i=0;i<inv.getSize();i++) if (inv.getItem(i)==null) inv.setItem(i, filler);

        return inv;
    }

    private static ItemStack createHead(StaffStatsPlugin plugin, ActivityTracker tracker, StaffRecord rec) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(rec.uuid);
            meta.setOwningPlayer(op);

            // Nick w GUI bez prefixów/rang – sama nazwa gracza
            meta.setDisplayName("§f" + rec.name);

            ActivityTracker.Session live = tracker.getSession(rec.uuid);
            long totalPlay = rec.totalPlaytimeMs;
            long totalAfk = rec.totalAfkMs;
            long lastLogin = rec.lastLogin;
            boolean online = false;
            if (live != null) {
                online = true;
                totalPlay += live.currentPlaytime();
                totalAfk += live.currentAfk();
                lastLogin = Math.max(lastLogin, live.loginTime);
            }
            long active = Math.max(0, totalPlay - totalAfk);
            double afkPerc = totalPlay > 0 ? (totalAfk * 100.0 / totalPlay) : 0;

            List<String> lore = new ArrayList<>();
            lore.add("§8§m---------------------");
            lore.add("§7Ranga: §f" + (rec.group != null ? rec.group : "unknown"));
            lore.add("");
            lore.add("§f⏱ Czas online: §a" + StaffRecord.formatDuration(totalPlay));
            lore.add("§f💤 AFK: §c" + StaffRecord.formatDuration(totalAfk) + " §8(" + String.format(Locale.US, "%.1f", afkPerc) + "%)");
            lore.add("§f⚡ Aktywny: §b" + StaffRecord.formatDuration(active));
            lore.add("");
            lore.add("§7Sesje: §e" + rec.sessionCount + (online ? " §a(+online)" : ""));
            lore.add("§7Ostatnie logowanie:");
            lore.add("§f  " + StaffRecord.formatDate(lastLogin) + " §8(" + StaffRecord.formatAgo(lastLogin) + ")");
            lore.add("§7Ostatnie wylogowanie:");
            lore.add("§f  " + (online ? "§a§lONLINE TERAZ" : StaffRecord.formatDate(rec.lastLogout) + " §8(" + StaffRecord.formatAgo(rec.lastLogout) + ")"));
            if (live != null) {
                lore.add("");
                lore.add("§a● Online" + (live.isAfk() ? " §c[AFK]" : ""));
                lore.add("§7 Sesja: §f" + StaffRecord.formatDuration(live.currentPlaytime()));
            }
            lore.add("");
            lore.add("§e▶ Kliknij aby zobaczyć pełny raport na chacie");
            lore.add("§8§m---------------------");
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private static int getPriority(StaffStatsPlugin plugin, String group) {
        if (group == null) return 0;
        return plugin.getConfig().getInt("group-priority." + group.toLowerCase(Locale.ROOT),
                plugin.getConfig().getInt("group-priority.default", 10));
    }

    private static String color(String s) { return s == null ? "" : s.replace('&', '§'); }
}
