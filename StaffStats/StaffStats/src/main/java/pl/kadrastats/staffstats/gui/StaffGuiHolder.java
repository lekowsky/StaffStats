package pl.kadrastats.staffstats.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Znacznik naszych GUI – pewna identyfikacja okna (zamiast zawodnego porównywania tytułu).
 * Trzyma też aktualną stronę paginacji.
 */
public class StaffGuiHolder implements InventoryHolder {

    private Inventory inventory;
    private int page;
    private int totalPages = 1;

    public StaffGuiHolder(int page) { this.page = page; }

    @Override
    public Inventory getInventory() { return inventory; }

    void attach(Inventory inventory) { this.inventory = inventory; }

    public int getPage() { return page; }
    public int getTotalPages() { return totalPages; }
    void setPage(int page) { this.page = page; }
    void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}
