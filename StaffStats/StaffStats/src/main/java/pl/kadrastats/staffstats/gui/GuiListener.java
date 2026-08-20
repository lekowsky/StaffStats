package pl.kadrastats.staffstats.gui;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Obsługa GUI kadry. Okna identyfikowane są po StaffGuiHolder (a nie po tytule –
 * tytuł mógł kolidować z innymi pluginami). Dodatkowo: paginacja + live-refresh.
 */
public class GuiListener implements Listener {

    private final StaffStatsPlugin plugin;
    private final NamespacedKey navKey;
    private final Set<UUID> openViewers = ConcurrentHashMap.newKeySet();

    public GuiListener(StaffStatsPlugin plugin) {
        this.plugin = plugin;
        this.navKey = new NamespacedKey(plugin, StaffGui.NAV_KEY);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof StaffGuiHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getClickedInventory() != null && e.getClickedInventory().getHolder() instanceof StaffGuiHolder) {
            // klik w nasze GUI – zawsze zablokuj
            e.setCancelled(true);
            ItemStack item = e.getCurrentItem();
            if (item == null || item.getItemMeta() == null) return;

            // nawigacja stron
            String nav = item.getItemMeta().getPersistentDataContainer().get(navKey, PersistentDataType.STRING);
            if (nav != null) {
                int newPage = holder.getPage() + ("next".equals(nav) ? 1 : -1);
                StaffGui.populate(plugin, e.getView().getTopInventory(), holder, newPage, false);
                return;
            }

            // główka = pełny raport
            if (item.getType() == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta meta) {
                var owning = meta.getOwningPlayer();
                if (owning == null) return;
                UUID uuid = owning.getUniqueId();
                p.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> sendFullReport(p, uuid));
            }
        } else {
            // własny ekwipunek gracza – zablokuj tylko próby wrzucenia przedmiotów do GUI
            if (e.isShiftClick() || e.getHotbarButton() >= 0) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        // zablokuj przeciąganie przedmiotów do naszego GUI (kliknięcia blokuje onClick)
        if (!(e.getView().getTopInventory().getHolder() instanceof StaffGuiHolder holder)) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < topSize) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getHolder() instanceof StaffGuiHolder) {
            if (e.getPlayer() instanceof Player p) openViewers.add(p.getUniqueId());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof StaffGuiHolder) {
            if (e.getPlayer() instanceof Player p) openViewers.remove(p.getUniqueId());
        }
    }

    /** Live-refresh otwartych GUI – wywoływane cyklicznie z gui.live-refresh-seconds. */
    public void refreshOpenGuis() {
        if (openViewers.isEmpty()) return;
        for (UUID uuid : openViewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                openViewers.remove(uuid);
                continue;
            }
            if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof StaffGuiHolder holder)) continue;
            StaffGui.populate(plugin, p.getOpenInventory().getTopInventory(), holder, holder.getPage(), true);
        }
    }

    private void sendFullReport(Player viewer, UUID targetUuid) {
        var db = plugin.getDatabase();
        var tracker = plugin.getActivityTracker();
        StaffRecord rec = db.getRecord(targetUuid);
        ActivityTracker.Session live = tracker.getSession(targetUuid);

        String name = rec != null ? rec.name : Bukkit.getOfflinePlayer(targetUuid).getName();
        if (name == null) name = targetUuid.toString().substring(0,8);

        String group = rec != null ? rec.group : (live != null ? live.groupAtJoin : "??");
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

        String header = color(plugin.getConfig().getString("messages.report-header", "&8&m--------&r &b&lRAPORT &8&m--------"));
        viewer.sendMessage(header);
        viewer.sendMessage("§b➤ Gracz: §f" + name + " §8[" + group + "]");
        viewer.sendMessage("§7▸ Czas online łącznie: §a" + StaffRecord.formatDuration(totalPlay));
        viewer.sendMessage("§7▸ AFK: §c" + StaffRecord.formatDuration(totalAfk));
        viewer.sendMessage("§7▸ Aktywny: §b" + StaffRecord.formatDuration(active));
        viewer.sendMessage("§7▸ Sesji: §e" + sessions + (live != null ? " §a(+1 online)" : ""));
        viewer.sendMessage("§7▸ Średnia sesja: §f" + (sessions>0 ? StaffRecord.formatDuration(totalPlay / sessions) : "0s"));

        // LibertyBans – kary wydane (widok wg rangi gracza)
        List<String> punishTypes = pl.kadrastats.staffstats.util.PunishDisplay.typesFor(plugin, group);
        if (!punishTypes.isEmpty()) {
            java.util.Map<String, Long> counts = plugin.getDatabase().getPunishmentCounts(targetUuid);
            viewer.sendMessage("§7▸ Kary wydane:");
            for (String line : pl.kadrastats.staffstats.util.PunishDisplay.chatLines(counts, punishTypes)) {
                viewer.sendMessage("    " + line);
            }
        }

        viewer.sendMessage("§7▸ Ostatnie logowanie: §f" + StaffRecord.formatDate(lastLogin) + " §8(" + StaffRecord.formatAgo(lastLogin) + ")");
        viewer.sendMessage("§7▸ Ostatnie wylogowanie: §f" + (live != null ? "§aONLINE" : StaffRecord.formatDate(lastLogout)));
        if (live != null) {
            viewer.sendMessage("§a● Aktualna sesja: " + StaffRecord.formatDuration(live.currentPlaytime()) + (live.isAfk() ? " §c[AFK]" : ""));
        }
        viewer.sendMessage("§8§m----------------------------------------");
    }

    private String color(String s){ return s == null ? "" : s.replace('&','§'); }
}
