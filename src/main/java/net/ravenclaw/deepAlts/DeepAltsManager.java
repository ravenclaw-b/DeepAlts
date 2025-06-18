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
    private final Map<UUID, Set<UUID>> altGraph = new HashMap<>();
    private final Plugin plugin;
    private final File dataFile;

    public DeepAltsManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");

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

        // Update graph
        for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
            UUID otherUuid = entry.getKey();
            if (!otherUuid.equals(uuid) && entry.getValue().contains(ip)) {
                altGraph.computeIfAbsent(uuid, k -> new HashSet<>()).add(otherUuid);
                altGraph.computeIfAbsent(otherUuid, k -> new HashSet<>()).add(uuid);
            }
        }

        saveAsync();
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

            rebuildGraph();

            Bukkit.getScheduler().runTask(plugin, afterLoad);
        });
    }


    private void rebuildGraph() {
        altGraph.clear();
        Map<String, Set<UUID>> ipToUuids = new HashMap<>();

        for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
            UUID uuid = entry.getKey();
            for (String ip : entry.getValue()) {
                ipToUuids.computeIfAbsent(ip, k -> new HashSet<>()).add(uuid);
            }
        }

        for (Set<UUID> uuids : ipToUuids.values()) {
            for (UUID u1 : uuids) {
                for (UUID u2 : uuids) {
                    if (!u1.equals(u2)) {
                        altGraph.computeIfAbsent(u1, k -> new HashSet<>()).add(u2);
                    }
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
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new LinkedList<>();

        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID neighbor : altGraph.getOrDefault(current, Collections.emptySet())) {
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        visited.remove(start);
        return visited;
    }

    public Map<UUID, Set<String>> getUuidToIpsMap() {
        return uuidToIpsMap;
    }

    public Map<UUID, String> getUuidToLatestIpMap() {
        return uuidToLatestIpMap;
    }

    public Map<UUID, Set<UUID>> getAltGraph() {
        return altGraph;
    }

    public @NotNull Plugin getPlugin() {
        return plugin;
    }
}