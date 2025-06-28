package net.ravenclaw.deepAlts;

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
     * Updates the graph when a player joins with an IP
     * Connects this UUID to all other UUIDs that have used this IP (only if IP is not a proxy)
     */
    public void updateOnPlayerJoin(UUID playerUuid, String ip, Map<UUID, Set<String>> uuidToIpsMap, boolean isProxy) {
        // Skip creating connections if the IP is a proxy
        if (isProxy) {
            return;
        }

        // Find all UUIDs that have used this IP
        Set<UUID> uuidsWithSameIp = new HashSet<>();
        for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
            if (entry.getValue().contains(ip)) {
                uuidsWithSameIp.add(entry.getKey());
            }
        }

        // Connect the current player to all other players with the same IP
        for (UUID otherUuid : uuidsWithSameIp) {
            if (!otherUuid.equals(playerUuid)) {
                addConnection(playerUuid, otherUuid);
            }
        }
    }

    /**
     * Rebuilds the entire graph from IP data, excluding proxy IPs
     * This method now checks uncached IPs against the proxy API
     */
    public void rebuildFromIpData(Map<UUID, Set<String>> uuidToIpsMap, ProxyCache proxyCache) {
        altGraph.clear();

        // Collect all unique IPs first
        Set<String> allIps = new HashSet<>();
        for (Set<String> ips : uuidToIpsMap.values()) {
            allIps.addAll(ips);
        }

        plugin.getLogger().info("Starting graph rebuild with " + allIps.size() + " unique IPs to check...");

        // Check all uncached IPs for proxy status
        List<CompletableFuture<Void>> proxyCheckFutures = new ArrayList<>();

        for (String ip : allIps) {
            Boolean cachedStatus = proxyCache.getCachedProxyStatus(ip);
            if (cachedStatus == null) {
                // IP not cached, need to check it
                CompletableFuture<Void> future = proxyCache.isProxy(ip).thenAccept(isProxy -> {
                    // The result is automatically cached by the ProxyCache.isProxy method
                    plugin.getLogger().info("Checked uncached IP " + ip + " during rebuild: " + (isProxy ? "proxy" : "not proxy"));
                });
                proxyCheckFutures.add(future);
            }
        }

        // Wait for all proxy checks to complete, then rebuild the graph
        CompletableFuture<Void> allChecks = CompletableFuture.allOf(proxyCheckFutures.toArray(new CompletableFuture[0]));

        allChecks.thenRun(() -> {
            plugin.getLogger().info("All proxy checks completed. Building graph connections...");
            buildGraphFromCheckedIps(uuidToIpsMap, proxyCache);
            plugin.getLogger().info("Graph rebuild completed with " + altGraph.size() + " nodes.");
            // Save the graph after successful rebuild
            saveAsync();
        }).exceptionally(throwable -> {
            plugin.getLogger().severe("Error during graph rebuild proxy checks: " + throwable.getMessage());
            // Still try to build the graph with whatever data we have
            buildGraphFromCheckedIps(uuidToIpsMap, proxyCache);
            // Save even after partial rebuild
            saveAsync();
            return null;
        });
    }

    /**
     * Helper method to build the graph after all proxy statuses are determined
     */
    private void buildGraphFromCheckedIps(Map<UUID, Set<String>> uuidToIpsMap, ProxyCache proxyCache) {
        // Create a map of IP -> Set of UUIDs (excluding proxy IPs)
        Map<String, Set<UUID>> ipToUuids = new HashMap<>();
        int skippedProxyIps = 0;
        int unknownIps = 0;

        for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
            UUID uuid = entry.getKey();
            for (String ip : entry.getValue()) {
                Boolean isProxy = proxyCache.getCachedProxyStatus(ip);

                if (isProxy == null) {
                    // Still unknown after checks - treat as not proxy but log it
                    unknownIps++;
                    plugin.getLogger().warning("IP " + ip + " proxy status unknown after rebuild checks, treating as not proxy");
                    ipToUuids.computeIfAbsent(ip, k -> new HashSet<>()).add(uuid);
                } else if (isProxy) {
                    // Skip proxy IPs
                    skippedProxyIps++;
                    continue;
                } else {
                    // Not a proxy, include it
                    ipToUuids.computeIfAbsent(ip, k -> new HashSet<>()).add(uuid);
                }
            }
        }

        // Connect all UUIDs that share a non-proxy IP
        int connectionsCreated = 0;
        for (Set<UUID> uuids : ipToUuids.values()) {
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
                ", Proxy IPs skipped: " + skippedProxyIps +
                ", Unknown IPs: " + unknownIps);
    }

    /**
     * Returns all connected UUIDs via shared IPs using BFS
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
     * Gets direct connections for a UUID (one hop away)
     */
    public Set<UUID> getDirectAlts(UUID uuid) {
        return altGraph.getOrDefault(uuid, Collections.emptySet());
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

    /**
     * Gets the raw graph map (mainly for debugging or advanced usage)
     */
    public Map<UUID, Set<UUID>> getAltGraph() {
        return Collections.unmodifiableMap(altGraph);
    }

    /**
     * Clears the entire graph
     */
    public void clear() {
        altGraph.clear();
    }

    /**
     * Gets the number of nodes in the graph
     */
    public int size() {
        return altGraph.size();
    }

    /**
     * Checks if a UUID exists in the graph
     */
    public boolean contains(UUID uuid) {
        return altGraph.containsKey(uuid);
    }
}