package pl.kadrastats.staffstats.gui;

import pl.kadrastats.staffstats.StaffStatsPlugin;
import pl.kadrastats.staffstats.storage.StaffRecord;
import pl.kadrastats.staffstats.tracker.ActivityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

public class GuiListener implements Listener {

    private final StaffStatsPlugin plugin;

    public GuiListener(StaffStatsPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String viewTitle = e.getView().getTitle();
        String expected = color(plugin.getConfig().getString("gui.title", "&8&lKADRA"));
        // simple contains check
        if (!viewTitle.contains("KADRA") && !viewTitle.contains("Kadra") && !viewTitle.equals(expected)) return;
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return;
        var owning = meta.getOwningPlayer();
        if (owning == null) return;
        UUID uuid = owning.getUniqueId();
        p.closeInventory();

        // send full report
        Bukkit.getScheduler().runTask(plugin, () -> sendFullReport(p, uuid));
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
        viewer.sendMessage("§7▸ Ostatnie logowanie: §f" + StaffRecord.formatDate(lastLogin) + " §8(" + StaffRecord.formatAgo(lastLogin) + ")");
        viewer.sendMessage("§7▸ Ostatnie wylogowanie: §f" + (live != null ? "§aONLINE" : StaffRecord.formatDate(lastLogout)));
        if (live != null) {
            viewer.sendMessage("§a● Aktualna sesja: " + StaffRecord.formatDuration(live.currentPlaytime()) + (live.isAfk() ? " §c[AFK]" : ""));
        }
        viewer.sendMessage("§8§m----------------------------------------");
    }

    private String color(String s){ return s == null ? "" : s.replace('&','§'); }
}
