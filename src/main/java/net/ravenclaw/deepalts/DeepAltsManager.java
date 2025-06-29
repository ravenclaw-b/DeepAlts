package net.ravenclaw.deepalts;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DeepAltsManager implements Listener {

    private final Map<UUID, Set<String>> uuidToIpsMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> uuidToLatestIpMap = new ConcurrentHashMap<>();
    private final DeepAltsGraph altGraph;
    private final ProxyCache proxyCache;
    private final Plugin plugin;
    private final File dataFile;

    // Lock for coordinating graph updates
    private final ReentrantReadWriteLock graphLock = new ReentrantReadWriteLock();

    public DeepAltsManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.altGraph = new DeepAltsGraph(plugin);
        this.proxyCache = new ProxyCache(plugin);

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
    }

    @EventHandler
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        // Don't process if login is already denied
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        // Validate IP
        if (ip == null || ip.trim().isEmpty()) {
            plugin.getLogger().warning("Received null or empty IP for player " + uuid);
            return;
        }

        try {
            // Update IP maps first (these are thread-safe with ConcurrentHashMap)
            uuidToIpsMap.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(ip);
            uuidToLatestIpMap.put(uuid, ip);

            // Save IP data immediately (async)
            saveAsync();

            // Check proxy status and update graph in a coordinated way
            proxyCache.isProxy(ip).thenAccept(isProxy -> {
                // Use write lock to ensure graph updates are atomic
                graphLock.writeLock().lock();
                try {
                    // Update the graph with the new connection (only if not a proxy)
                    altGraph.updateOnPlayerJoin(uuid, ip, getUuidToIpsMapSnapshot(), isProxy);

                    // Save graph after update
                    altGraph.saveAsync();

                    if (isProxy) {
                        plugin.getLogger().info("Player " + uuid + " joined from proxy IP " + ip + " - not creating graph connections");
                    } else {
                        plugin.getLogger().info("Player " + uuid + " joined from IP " + ip + " - graph updated");
                    }
                } finally {
                    graphLock.writeLock().unlock();
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().severe("Error processing proxy check for " + uuid + " from " + ip + ": " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error in onAsyncPreLogin for " + uuid + " from " + ip + ": " + e.getMessage());
        }
    }

    /**
     * Creates a thread-safe snapshot of the UUID to IPs map
     */
    private Map<UUID, Set<String>> getUuidToIpsMapSnapshot() {
        Map<UUID, Set<String>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> entry : uuidToIpsMap.entrySet()) {
            snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return snapshot;
    }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                YamlConfiguration config = new YamlConfiguration();

                // Create snapshots to avoid concurrent modification
                Map<UUID, Set<String>> ipsSnapshot = getUuidToIpsMapSnapshot();
                Map<UUID, String> latestSnapshot = new HashMap<>(uuidToLatestIpMap);

                for (Map.Entry<UUID, Set<String>> entry : ipsSnapshot.entrySet()) {
                    String key = entry.getKey().toString();
                    config.set("ips." + key, new ArrayList<>(entry.getValue()));
                }
                for (Map.Entry<UUID, String> entry : latestSnapshot.entrySet()) {
                    config.set("latest." + entry.getKey().toString(), entry.getValue());
                }

                config.save(dataFile);
                plugin.getLogger().info("DeepAlts data saved.");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save DeepAlts data: " + e.getMessage());
            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error saving DeepAlts data: " + e.getMessage());
            }
        });
    }

    public void loadAsync(Runnable afterLoad) {
        CompletableFuture.runAsync(() -> {
            try {
                // Load proxy cache first
                proxyCache.loadAsync(() -> {
                    // Load IP data
                    loadIpData();

                    // Then load the graph, but if it doesn't exist, rebuild it from IP data
                    altGraph.loadAsync(() -> {
                        // Use write lock for graph rebuild
                        graphLock.writeLock().lock();
                        try {
                            // If graph is empty (new install or corrupted), rebuild from IP data
                            if (altGraph.size() == 0 && !uuidToIpsMap.isEmpty()) {
                                plugin.getLogger().info("Graph is empty, rebuilding from IP data (excluding proxies)...");
                                altGraph.rebuildFromIpData(getUuidToIpsMapSnapshot(), proxyCache);
                                altGraph.saveAsync();
                            }
                        } finally {
                            graphLock.writeLock().unlock();
                        }

                        if (afterLoad != null) {
                            Bukkit.getScheduler().runTask(plugin, afterLoad);
                        }
                    });
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Error during loadAsync: " + e.getMessage());
                if (afterLoad != null) {
                    Bukkit.getScheduler().runTask(plugin, afterLoad);
                }
            }
        });
    }

    private void loadIpData() {
        if (!dataFile.exists()) return;

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);

            ConfigurationSection ipsSection = config.getConfigurationSection("ips");
            if (ipsSection != null) {
                for (String key : ipsSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<String> ipList = ipsSection.getStringList(key);
                        Set<String> ipSet = ConcurrentHashMap.newKeySet();
                        ipSet.addAll(ipList);
                        uuidToIpsMap.put(uuid, ipSet);
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
                        if (ip != null && !ip.trim().isEmpty()) {
                            uuidToLatestIpMap.put(uuid, ip);
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in latest IP data: " + key);
                    }
                }
            }

            plugin.getLogger().info("Loaded IP data for " + uuidToIpsMap.size() + " players");
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading IP data: " + e.getMessage());
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
        graphLock.readLock().lock();
        try {
            return altGraph.getDeepAlts(start);
        } finally {
            graphLock.readLock().unlock();
        }
    }

    /**
     * Forces a rebuild of the graph from current IP data, excluding proxies
     * Useful for maintenance or if the graph gets corrupted
     */
    public void rebuildGraph() {
        graphLock.writeLock().lock();
        try {
            altGraph.rebuildFromIpData(getUuidToIpsMapSnapshot(), proxyCache);
            altGraph.saveAsync();
            plugin.getLogger().info("Graph rebuilt from IP data (excluding proxies).");
        } catch (Exception e) {
            plugin.getLogger().severe("Error rebuilding graph: " + e.getMessage());
        } finally {
            graphLock.writeLock().unlock();
        }
    }

    /**
     * Saves both IP data and graph and proxy cache
     */
    public void saveAll() {
        try {
            saveAsync();

            graphLock.readLock().lock();
            try {
                altGraph.saveAsync();
            } finally {
                graphLock.readLock().unlock();
            }

            proxyCache.saveAsync();
        } catch (Exception e) {
            plugin.getLogger().severe("Error in saveAll: " + e.getMessage());
        }
    }

    public Map<UUID, Set<String>> getUuidToIpsMap() {
        return Collections.unmodifiableMap(uuidToIpsMap);
    }

    public Map<UUID, String> getUuidToLatestIpMap() {
        return Collections.unmodifiableMap(uuidToLatestIpMap);
    }

    public DeepAltsGraph getAltGraph() {
        return altGraph;
    }

    public ProxyCache getProxyCache() {
        return proxyCache;
    }

    public @NotNull Plugin getPlugin() {
        return plugin;
    }

    /**
     * Gets the current proxy cache rate limit status for monitoring
     */
    public String getProxyRateLimitStatus() {
        return proxyCache.getRateLimitStatus();
    }
}