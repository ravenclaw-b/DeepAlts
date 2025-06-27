package net.ravenclaw.deepAlts;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DeepAltsManager implements Listener {

    private final Map<UUID, Set<String>> uuidToIpsMap = new HashMap<>();
    private final Map<UUID, String> uuidToLatestIpMap = new HashMap<>();
    private final DeepAltsGraph altGraph;
    private final Plugin plugin;
    private final File dataFile;

    public DeepAltsManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.altGraph = new DeepAltsGraph(plugin);

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        // Update IP maps
        uuidToIpsMap.computeIfAbsent(uuid, k -> new HashSet<>()).add(ip);
        uuidToLatestIpMap.put(uuid, ip);

        // Update the graph with the new connection
        altGraph.updateOnPlayerJoin(uuid, ip, uuidToIpsMap);

        // Save both data and graph
        saveAsync();
        altGraph.saveAsync();
    }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
                String key = entry.getKey().toString();
                config.set("ips." + key, new ArrayList<>(entry.getValue()));
            }
            for (Map.Entry<UUID, String> entry : uuidToLatestIpMap.entrySet()) {
                config.set("latest." + entry.getKey().toString(), entry.getValue());
            }

            try {
                config.save(dataFile);
                plugin.getLogger().info("DeepAlts data saved.");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save DeepAlts data: " + e.getMessage());
            }
        });
    }

    public void loadAsync(Runnable afterLoad) {
        CompletableFuture.runAsync(() -> {
            // Load IP data first
            loadIpData();

            // Then load the graph, but if it doesn't exist, rebuild it from IP data
            altGraph.loadAsync(() -> {
                // If graph is empty (new install or corrupted), rebuild from IP data
                if (altGraph.size() == 0 && !uuidToIpsMap.isEmpty()) {
                    plugin.getLogger().info("Graph is empty, rebuilding from IP data...");
                    altGraph.rebuildFromIpData(uuidToIpsMap);
                    altGraph.saveAsync();
                }

                if (afterLoad != null) {
                    Bukkit.getScheduler().runTask(plugin, afterLoad);
                }
            });
        });
    }

    private void loadIpData() {
        if (!dataFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection ipsSection = config.getConfigurationSection("ips");
        if (ipsSection != null) {
            for (String key : ipsSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<String> ipList = ipsSection.getStringList(key);
                    uuidToIpsMap.put(uuid, new HashSet<>(ipList));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in IP data: " + key);
                }
            }
        }

        ConfigurationSection latestSection = config.getConfigurationSection("latest");
        if (latestSection != null) {
            for (String key : latestSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String ip = latestSection.getString(key);
                    if (ip != null) {
                        uuidToLatestIpMap.put(uuid, ip);
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in latest IP data: " + key);
                }
            }
        }
    }

    /** Returns UUIDs that shared the player's most recent IP */
    public Set<UUID> getAlts(UUID uuid) {
        Set<UUID> alts = new HashSet<>();
        String latestIp = uuidToLatestIpMap.get(uuid);
        if (latestIp == null) return alts;

        for (Map.Entry<UUID, String> entry : uuidToLatestIpMap.entrySet()) {
            if (!entry.getKey().equals(uuid) && latestIp.equals(entry.getValue())) {
                alts.add(entry.getKey());
            }
        }
        return alts;
    }

    /** Returns all connected UUIDs via shared IPs */
    public Set<UUID> getDeepAlts(UUID start) {
        return altGraph.getDeepAlts(start);
    }

    /**
     * Forces a rebuild of the graph from current IP data
     * Useful for maintenance or if the graph gets corrupted
     */
    public void rebuildGraph() {
        altGraph.rebuildFromIpData(uuidToIpsMap);
        altGraph.saveAsync();
        plugin.getLogger().info("Graph rebuilt from IP data.");
    }

    /**
     * Saves both IP data and graph
     */
    public void saveAll() {
        saveAsync();
        altGraph.saveAsync();
    }

    public Map<UUID, Set<String>> getUuidToIpsMap() {
        return uuidToIpsMap;
    }

    public Map<UUID, String> getUuidToLatestIpMap() {
        return uuidToLatestIpMap;
    }

    public DeepAltsGraph getAltGraph() {
        return altGraph;
    }

    public @NotNull Plugin getPlugin() {
        return plugin;
    }
}