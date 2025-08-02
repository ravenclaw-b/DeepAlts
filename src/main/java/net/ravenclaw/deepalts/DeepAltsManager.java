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

    private final Map<UUID, Set<String>> uuidToHashedIpsMap = new ConcurrentHashMap<>(); // UUID -> Set of hashed IPs
    private final Map<UUID, String> uuidToLatestHashedIpMap = new ConcurrentHashMap<>(); // UUID -> latest hashed IP
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
            // Check proxy status and get hashed IP
            proxyCache.checkProxy(ip).thenAccept(result -> {
                String hashedIp = result.getHashedIp();
                boolean isProxy = result.isProxy();

                // Update IP maps with hashed IP (these are thread-safe with ConcurrentHashMap)
                uuidToHashedIpsMap.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(hashedIp);
                uuidToLatestHashedIpMap.put(uuid, hashedIp);

                // Save IP data immediately (async)
                saveAsync();

                // Update graph in a coordinated way
                graphLock.writeLock().lock();
                try {
                    // Update the graph with the new connection (only if not a proxy)
                    altGraph.updateOnPlayerJoin(uuid, hashedIp, getUuidToHashedIpsMapSnapshot(), isProxy);

                    // Save graph after update
                    altGraph.saveAsync();

                    if (isProxy) {
                        plugin.getLogger().info("Player " + uuid + " joined from proxy IP (hash: " + hashedIp.substring(0, 8) + "...) - not creating graph connections");
                    } else {
                        plugin.getLogger().info("Player " + uuid + " joined from IP (hash: " + hashedIp.substring(0, 8) + "...) - graph updated");
                    }
                } finally {
                    graphLock.writeLock().unlock();
                }
            }).exceptionally(throwable -> {
                plugin.getLogger().severe("Error processing proxy check for " + uuid + ": " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error in onAsyncPreLogin for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Creates a thread-safe snapshot of the UUID to hashed IPs map
     */
    private Map<UUID, Set<String>> getUuidToHashedIpsMapSnapshot() {
        Map<UUID, Set<String>> snapshot = new HashMap<>();
        for (Map.Entry<UUID, Set<String>> entry : uuidToHashedIpsMap.entrySet()) {
            snapshot.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return snapshot;
    }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                YamlConfiguration config = new YamlConfiguration();

                // Create snapshots to avoid concurrent modification
                Map<UUID, Set<String>> hashedIpsSnapshot = getUuidToHashedIpsMapSnapshot();
                Map<UUID, String> latestSnapshot = new HashMap<>(uuidToLatestHashedIpMap);

                for (Map.Entry<UUID, Set<String>> entry : hashedIpsSnapshot.entrySet()) {
                    String key = entry.getKey().toString();
                    config.set("hashed_ips." + key, new ArrayList<>(entry.getValue()));
                }
                for (Map.Entry<UUID, String> entry : latestSnapshot.entrySet()) {
                    config.set("latest_hashed." + entry.getKey().toString(), entry.getValue());
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
                    // Load hashed IP data
                    loadHashedIpData();

                    // Then load the graph, but if it doesn't exist, rebuild it from hashed IP data
                    altGraph.loadAsync(() -> {
                        // Use write lock for graph rebuild
                        graphLock.writeLock().lock();
                        try {
                            // If graph is empty (new install or corrupted), rebuild from hashed IP data
                            if (altGraph.size() == 0 && !uuidToHashedIpsMap.isEmpty()) {
                                plugin.getLogger().info("Graph is empty, rebuilding from hashed IP data (excluding proxies)...");
                                altGraph.rebuildFromHashedIpData(getUuidToHashedIpsMapSnapshot(), proxyCache);
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

    private void loadHashedIpData() {
        if (!dataFile.exists()) return;

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);

            // Load hashed IPs
            ConfigurationSection hashedIpsSection = config.getConfigurationSection("hashed_ips");
            if (hashedIpsSection != null) {
                for (String key : hashedIpsSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<String> hashedIpList = hashedIpsSection.getStringList(key);
                        Set<String> hashedIpSet = ConcurrentHashMap.newKeySet();
                        hashedIpSet.addAll(hashedIpList);
                        uuidToHashedIpsMap.put(uuid, hashedIpSet);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in hashed IP data: " + key);
                    }
                }
            }

            // Load latest hashed IPs
            ConfigurationSection latestHashedSection = config.getConfigurationSection("latest_hashed");
            if (latestHashedSection != null) {
                for (String key : latestHashedSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        String hashedIp = latestHashedSection.getString(key);
                        if (hashedIp != null && !hashedIp.trim().isEmpty()) {
                            uuidToLatestHashedIpMap.put(uuid, hashedIp);
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in latest hashed IP data: " + key);
                    }
                }
            }

            // Migrate old data format if it exists
            migrateOldDataFormat(config);

            plugin.getLogger().info("Loaded hashed IP data for " + uuidToHashedIpsMap.size() + " players");
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading hashed IP data: " + e.getMessage());
        }
    }

    /**
     * Migrates old format data (actual IPs) to new format (hashed IPs)
     */
    private void migrateOldDataFormat(YamlConfiguration config) {
        ConfigurationSection oldIpsSection = config.getConfigurationSection("ips");
        ConfigurationSection oldLatestSection = config.getConfigurationSection("latest");

        if (oldIpsSection != null || oldLatestSection != null) {
            plugin.getLogger().info("Migrating old IP data to hashed format...");
            boolean migrated = false;

            // Migrate old IPs section
            if (oldIpsSection != null) {
                for (String key : oldIpsSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        List<String> oldIpList = oldIpsSection.getStringList(key);

                        Set<String> hashedIpSet = ConcurrentHashMap.newKeySet();
                        for (String oldIp : oldIpList) {
                            String hashedIp = hashIp(oldIp);
                            hashedIpSet.add(hashedIp);
                        }

                        if (!hashedIpSet.isEmpty()) {
                            uuidToHashedIpsMap.put(uuid, hashedIpSet);
                            migrated = true;
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in old IP data: " + key);
                    }
                }
            }

            // Migrate old latest section
            if (oldLatestSection != null) {
                for (String key : oldLatestSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        String oldIp = oldLatestSection.getString(key);
                        if (oldIp != null && !oldIp.trim().isEmpty()) {
                            String hashedIp = hashIp(oldIp);
                            uuidToLatestHashedIpMap.put(uuid, hashedIp);
                            migrated = true;
                        }
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in old latest IP data: " + key);
                    }
                }
            }

            if (migrated) {
                plugin.getLogger().info("Migration completed. Old IP data will be removed on next save.");
                // Save the migrated data immediately
                saveAsync();
            }
        }
    }

    /**
     * Helper method to hash IP (same as in ProxyCache)
     */
    private String hashIp(String ip) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(ip.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            plugin.getLogger().severe("SHA-256 algorithm not available: " + e.getMessage());
            return String.valueOf(ip.hashCode());
        }
    }

    /** Returns UUIDs that shared the player's most recent hashed IP */
    public Set<UUID> getAlts(UUID uuid) {
        Set<UUID> alts = new HashSet<>();
        String latestHashedIp = uuidToLatestHashedIpMap.get(uuid);
        if (latestHashedIp == null) return alts;

        for (Map.Entry<UUID, String> entry : uuidToLatestHashedIpMap.entrySet()) {
            if (!entry.getKey().equals(uuid) && latestHashedIp.equals(entry.getValue())) {
                alts.add(entry.getKey());
            }
        }
        return alts;
    }

    /** Returns all connected UUIDs via shared hashed IPs */
    public Set<UUID> getDeepAlts(UUID start) {
        graphLock.readLock().lock();
        try {
            return altGraph.getDeepAlts(start);
        } finally {
            graphLock.readLock().unlock();
        }
    }

    /**
     * Forces a rebuild of the graph from current hashed IP data, excluding proxies
     * Useful for maintenance or if the graph gets corrupted
     */
    public void rebuildGraph() {
        graphLock.writeLock().lock();
        try {
            altGraph.rebuildFromHashedIpData(getUuidToHashedIpsMapSnapshot(), proxyCache);
            altGraph.saveAsync();
            plugin.getLogger().info("Graph rebuilt from hashed IP data (excluding proxies).");
        } catch (Exception e) {
            plugin.getLogger().severe("Error rebuilding graph: " + e.getMessage());
        } finally {
            graphLock.writeLock().unlock();
        }
    }

    /**
     * Saves both hashed IP data, graph and proxy cache
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

    public Map<UUID, Set<String>> getUuidToHashedIpsMap() {
        return Collections.unmodifiableMap(uuidToHashedIpsMap);
    }

    public Map<UUID, String> getUuidToLatestHashedIpMap() {
        return Collections.unmodifiableMap(uuidToLatestHashedIpMap);
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