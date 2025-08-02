package net.ravenclaw.deepalts;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DeepAltsGraph {

    private final Map<UUID, Set<UUID>> altGraph = new HashMap<>();
    private final Plugin plugin;
    private final File graphFile;

    public DeepAltsGraph(Plugin plugin) {
        this.plugin = plugin;
        this.graphFile = new File(plugin.getDataFolder(), "graph.yml");

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
    }

    /**
     * Adds a connection between two UUIDs in the graph
     */
    public void addConnection(UUID uuid1, UUID uuid2) {
        if (uuid1.equals(uuid2)) return;

        altGraph.computeIfAbsent(uuid1, k -> new HashSet<>()).add(uuid2);
        altGraph.computeIfAbsent(uuid2, k -> new HashSet<>()).add(uuid1);
    }

    /**
     * Updates the graph when a player joins with a hashed IP
     * Connects this UUID to all other UUIDs that have used this hashed IP (only if IP is not a proxy)
     */
    public void updateOnPlayerJoin(UUID playerUuid, String hashedIp, Map<UUID, Set<String>> uuidToHashedIpsMap, boolean isProxy) {
        // Skip creating connections if the IP is a proxy
        if (isProxy) {
            return;
        }

        // Find all UUIDs that have used this hashed IP
        Set<UUID> uuidsWithSameHashedIp = new HashSet<>();
        for (Map.Entry<UUID, Set<String>> entry : uuidToHashedIpsMap.entrySet()) {
            if (entry.getValue().contains(hashedIp)) {
                uuidsWithSameHashedIp.add(entry.getKey());
            }
        }

        // Connect the current player to all other players with the same hashed IP
        for (UUID otherUuid : uuidsWithSameHashedIp) {
            if (!otherUuid.equals(playerUuid)) {
                addConnection(playerUuid, otherUuid);
            }
        }
    }

    /**
     * Rebuilds the entire graph from hashed IP data, excluding proxy IPs
     * This method checks cached proxy statuses and builds connections accordingly
     */
    public void rebuildFromHashedIpData(Map<UUID, Set<String>> uuidToHashedIpsMap, ProxyCache proxyCache) {
        altGraph.clear();

        // Collect all unique hashed IPs
        Set<String> allHashedIps = new HashSet<>();
        for (Set<String> hashedIps : uuidToHashedIpsMap.values()) {
            allHashedIps.addAll(hashedIps);
        }

        plugin.getLogger().info("Starting graph rebuild with " + allHashedIps.size() + " unique hashed IPs...");

        // Build the graph using cached proxy information
        buildGraphFromHashedIps(uuidToHashedIpsMap, proxyCache);

        plugin.getLogger().info("Graph rebuild completed with " + altGraph.size() + " nodes.");
    }

    /**
     * Helper method to build the graph from hashed IP data
     */
    private void buildGraphFromHashedIps(Map<UUID, Set<String>> uuidToHashedIpsMap, ProxyCache proxyCache) {
        // Create a map of hashedIp -> Set of UUIDs (excluding proxy IPs)
        Map<String, Set<UUID>> hashedIpToUuids = new HashMap<>();
        int skippedProxyIps = 0;
        int unknownIps = 0;

        for (Map.Entry<UUID, Set<String>> entry : uuidToHashedIpsMap.entrySet()) {
            UUID uuid = entry.getKey();
            for (String hashedIp : entry.getValue()) {
                Boolean isProxy = proxyCache.getCachedProxyStatus(hashedIp);

                if (isProxy == null) {
                    // Unknown proxy status - treat as not proxy but log it
                    unknownIps++;
                    plugin.getLogger().warning("Hashed IP " + hashedIp.substring(0, 8) + "... proxy status unknown, treating as not proxy");
                    hashedIpToUuids.computeIfAbsent(hashedIp, k -> new HashSet<>()).add(uuid);
                } else if (isProxy) {
                    // Skip proxy IPs
                    skippedProxyIps++;
                    continue;
                } else {
                    // Not a proxy, include it
                    hashedIpToUuids.computeIfAbsent(hashedIp, k -> new HashSet<>()).add(uuid);
                }
            }
        }

        // Connect all UUIDs that share a non-proxy hashed IP
        int connectionsCreated = 0;
        for (Set<UUID> uuids : hashedIpToUuids.values()) {
            for (UUID u1 : uuids) {
                for (UUID u2 : uuids) {
                    if (!u1.equals(u2)) {
                        addConnection(u1, u2);
                        connectionsCreated++;
                    }
                }
            }
        }

        plugin.getLogger().info("Graph rebuild stats - Connections created: " + connectionsCreated +
                ", Proxy hashed IPs skipped: " + skippedProxyIps +
                ", Unknown hashed IPs: " + unknownIps);
    }

    /**
     * Returns all connected UUIDs via shared hashed IPs using BFS
     */
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

        visited.remove(start); // Remove the starting UUID from results
        return visited;
    }

    /**
     * Saves the graph to file asynchronously
     */
    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            YamlConfiguration config = new YamlConfiguration();

            for (Map.Entry<UUID, Set<UUID>> entry : altGraph.entrySet()) {
                String key = entry.getKey().toString();
                List<String> connections = new ArrayList<>();
                for (UUID connectedUuid : entry.getValue()) {
                    connections.add(connectedUuid.toString());
                }
                config.set("graph." + key, connections);
            }

            try {
                config.save(graphFile);
                plugin.getLogger().info("DeepAlts graph saved.");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save DeepAlts graph: " + e.getMessage());
            }
        });
    }

    /**
     * Loads the graph from file asynchronously
     */
    public void loadAsync(Runnable afterLoad) {
        CompletableFuture.runAsync(() -> {
            if (!graphFile.exists()) {
                if (afterLoad != null) {
                    plugin.getServer().getScheduler().runTask(plugin, afterLoad);
                }
                return;
            }

            YamlConfiguration config = YamlConfiguration.loadConfiguration(graphFile);

            ConfigurationSection graphSection = config.getConfigurationSection("graph");
            if (graphSection != null) {
                for (String key : graphSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<String> connectionsList = graphSection.getStringList(key);

                        Set<UUID> connections = new HashSet<>();
                        for (String connectionStr : connectionsList) {
                            try {
                                connections.add(UUID.fromString(connectionStr));
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Invalid UUID in graph data: " + connectionStr);
                            }
                        }

                        if (!connections.isEmpty()) {
                            altGraph.put(uuid, connections);
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in graph data: " + key);
                    }
                }
            }

            plugin.getLogger().info("DeepAlts graph loaded with " + altGraph.size() + " nodes.");

            if (afterLoad != null) {
                plugin.getServer().getScheduler().runTask(plugin, afterLoad);
            }
        });
    }

    public int size() {
        return altGraph.size();
    }
}