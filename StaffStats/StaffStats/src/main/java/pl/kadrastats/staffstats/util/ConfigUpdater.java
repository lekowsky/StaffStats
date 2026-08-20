package pl.kadrastats.staffstats.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

public class ConfigUpdater {

    private final JavaPlugin plugin;

    public ConfigUpdater(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void run() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveDefaultConfig();
            return;
        }

        boolean forceOverwrite = plugin.getConfig().getBoolean("config-force-overwrite", false);
        boolean autoUpdate = plugin.getConfig().getBoolean("config-auto-update", true);

        if (forceOverwrite) {
            // backup
            backup(configFile);
            plugin.saveResource("config.yml", true);
            plugin.reloadConfig();
            plugin.getLogger().warning("config.yml ZOSTAŁ NADPISANY (config-force-overwrite=true) – stary zapisany jako .backup");
            // turn off flag to avoid loop
            plugin.getConfig().set("config-force-overwrite", false);
            try { plugin.getConfig().save(configFile); } catch (Exception ignored) {}
            return;
        }

        if (!autoUpdate) return;

        // merge missing keys
        try (InputStream defStream = plugin.getResource("config.yml")) {
            if (defStream == null) return;
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream));
            FileConfiguration current = plugin.getConfig();
            boolean changed = false;
            for (String key : defConfig.getKeys(true)) {
                if (!defConfig.isConfigurationSection(key)) {
                    if (!current.contains(key)) {
                        current.set(key, defConfig.get(key));
                        changed = true;
                    }
                }
            }
            if (changed) {
                backup(configFile);
                current.save(configFile);
                plugin.getLogger().info("Config zaktualizowany – dodano brakujące klucze. Backup: config.yml.backup-*");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Config auto-update failed: " + e.getMessage());
        }
    }

    private void backup(File configFile) {
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File backup = new File(configFile.getParentFile(), "config.yml.backup-" + ts);
            Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }
}
