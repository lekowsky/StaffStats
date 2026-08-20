package pl.kadrastats.staffstats.gui;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class StaffGui {

    public static final String NAV_KEY = "nav";

    /** Krótkotrwała pamięć podręczna rekordów (dla gui.refresh-on-open: false / zmiany stron). */
    private static List<StaffRecord> recordCache = null;
    private static long recordCacheAt = 0;

    /** Buduje nowe GUI na wybranej stronie (0-based). */
    public static Inventory build(StaffStatsPlugin plugin, int page) {
        int rows = rows(plugin);
        String title = color(plugin.getConfig().getString("gui.title", "&8&lKADRA &7- Statystyki"));
        StaffGuiHolder holder = new StaffGuiHolder(page);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.attach(inv);
        populate(plugin, inv, holder, page, plugin.getConfig().getBoolean("gui.refresh-on-open", true));
        return inv;
    }

    /**
     * Wypełnia/odświeża ISTNIEJĄCE GUI (paginacja + live-refresh).
     * Dolny rząd: strzałki ◀ ▶ (gdy więcej niż 1 strona) + info o stronie.
     */
    public static void populate(StaffStatsPlugin plugin, Inventory inv, StaffGuiHolder holder, int page, boolean fresh) {
        List<StaffRecord> all = loadRecords(plugin, fresh);
        if (!plugin.getConfig().getBoolean("gui.show-offline-heads", true)) {
            ActivityTracker tracker = plugin.getActivityTracker();
            all = all.stream().filter(r -> tracker.getSession(r.uuid) != null).toList();
        }

        int headSlots = Math.max(9, inv.getSize() - 9);
        int totalPages = Math.max(1, (all.size() + headSlots - 1) / headSlots);
        int p = Math.max(0, Math.min(page, totalPages - 1));
        holder.setPage(p);
        holder.setTotalPages(totalPages);

        inv.clear();

        int start = p * headSlots;
        int slot = 0;
        ActivityTracker tracker = plugin.getActivityTracker();
        for (int i = start; i < all.size() && slot < headSlots; i++, slot++) {
            inv.setItem(slot, createHead(plugin, tracker, all.get(i)));
        }

        if (all.isEmpty()) {
            inv.setItem(inv.getSize() / 2, infoItem(plugin));
        }

        bottomRow(plugin, inv, p, totalPages, all.size());
        filler(inv);
    }

    private static void bottomRow(StaffStatsPlugin plugin, Inventory inv, int page, int totalPages, int totalStaff) {
        int base = inv.getSize() - 9;
        if (totalPages > 1) {
            inv.setItem(base, navItem(plugin, "prev", "§e◀ Poprzednia strona", page));
            inv.setItem(base + 8, navItem(plugin, "next", "§eNastępna strona ▶", page));
        }
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName("§bKadra §7- strona §f" + (page + 1) + "§7/§f" + totalPages);
            List<String> lore = new ArrayList<>();
            lore.add("§7Osób w bazie: §f" + totalStaff);
            lore.add("§7Sortowanie: §franga → czas aktywny");
            lore.add("");
            lore.add("§e▶ Kliknij główkę = pełny raport");
            im.setLore(lore);
            info.setItemMeta(im);
        }
        inv.setItem(base + 4, info);
    }

    private static ItemStack navItem(StaffStatsPlugin plugin, String dir, String name, int page) {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            im.getPersistentDataContainer().set(new NamespacedKey(plugin, NAV_KEY), PersistentDataType.STRING, dir);
            it.setItemMeta(im);
        }
        return it;
    }

    private static ItemStack infoItem(StaffStatsPlugin plugin) {
        ItemStack info = new ItemStack(Material.PAPER);
        var im = info.getItemMeta();
        im.setDisplayName("§cBrak danych kadry");
        List<String> lore = new ArrayList<>();
        lore.add("§7Nie znaleziono żadnych administratorów w bazie.");
        lore.add("");
        lore.add("§ePoczekaj aż ktoś z kadry wejdzie na serwer,");
        lore.add("§ealbo sprawdź tracked-groups w config.yml");
        lore.add("");
        lore.add("§8Tracked groups: " + String.join(", ", plugin.getActivityTracker().getTrackedGroups()));
        im.setLore(lore);
        info.setItemMeta(im);
        return info;
    }

    private static void filler(Inventory inv) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var meta = filler.getItemMeta();
        meta.setDisplayName("§8");
        filler.setItemMeta(meta);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);
    }

    private static List<StaffRecord> loadRecords(StaffStatsPlugin plugin, boolean fresh) {
        long now = System.currentTimeMillis();
        if (!fresh && recordCache != null && now - recordCacheAt < 5000) return recordCache;
        List<StaffRecord> all = plugin.getDatabase().getAll(1000);
        all.sort((a, b) -> {
            int pa = getPriority(plugin, a.group);
            int pb = getPriority(plugin, b.group);
            if (pa != pb) return Integer.compare(pb, pa);
            return Long.compare(b.activeMs(), a.activeMs());
        });
        recordCache = all;
        recordCacheAt = now;
        return all;
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

    private static int rows(StaffStatsPlugin plugin) {
        return Math.max(3, Math.min(6, plugin.getConfig().getInt("gui.rows", 6)));
    }

    private static int getPriority(StaffStatsPlugin plugin, String group) {
        if (group == null) return 0;
        return plugin.getConfig().getInt("group-priority." + group.toLowerCase(Locale.ROOT),
                plugin.getConfig().getInt("group-priority.default", 10));
    }

    private static String color(String s) { return s == null ? "" : s.replace('&', '§'); }
}
