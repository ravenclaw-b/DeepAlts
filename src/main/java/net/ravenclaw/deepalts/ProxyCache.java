package net.ravenclaw.deepalts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyCache {

    private final Map<String, Boolean> proxyCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final File cacheFile;

    // Rate limiting variables
    private final Semaphore rateLimitSemaphore = new Semaphore(45); // 45 requests per minute
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong requestCount = new AtomicLong(0);

    // Rate limit window in milliseconds (60 seconds)
    private static final long RATE_LIMIT_WINDOW = 60 * 1000;

    public ProxyCache(Plugin plugin) {
        this.plugin = plugin;
        this.cacheFile = new File(plugin.getDataFolder(), "proxy_cache.yml");

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // Start rate limit reset task
        startRateLimitResetTask();
    }

    /**
     * Starts a task to reset rate limits every minute
     */
    private void startRateLimitResetTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                long currentTime = System.currentTimeMillis();
                long timeSinceReset = currentTime - lastResetTime.get();

                if (timeSinceReset >= RATE_LIMIT_WINDOW) {
                    // Reset rate limit
                    int permitsToRelease = 45 - rateLimitSemaphore.availablePermits();
                    if (permitsToRelease > 0) {
                        rateLimitSemaphore.release(permitsToRelease);
                    }
                    requestCount.set(0);
                    lastResetTime.set(currentTime);
                    plugin.getLogger().info("Proxy API rate limit reset. Available permits: " + rateLimitSemaphore.availablePermits());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in rate limit reset task: " + e.getMessage());
            }
        }, 0L, 20L * 10); // Run every 10 seconds to check
    }

    /**
     * Checks if an IP is a proxy, using cache first, then API if not cached
     * Prevents duplicate requests for the same IP and respects rate limits
     */
    public CompletableFuture<Boolean> isProxy(String ip) {
        // Validate input
        if (ip == null || ip.trim().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        // Normalize IP (trim whitespace)
        ip = ip.trim();

        // Check cache first
        if (proxyCache.containsKey(ip)) {
            return CompletableFuture.completedFuture(proxyCache.get(ip));
        }

        // Check if there's already a pending request for this IP
        CompletableFuture<Boolean> existingRequest = pendingRequests.get(ip);
        if (existingRequest != null) {
            return existingRequest;
        }

        // Create new request
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Try to add to pending requests atomically
        CompletableFuture<Boolean> existingFuture = pendingRequests.putIfAbsent(ip, future);
        if (existingFuture != null) {
            // Another thread already created a request for this IP
            return existingFuture;
        }

        // We're the first thread to request this IP, so make the API call
        final String finalIp = ip;
        CompletableFuture.runAsync(() -> {
            try {
                // Try to acquire rate limit permit
                if (!rateLimitSemaphore.tryAcquire()) {
                    plugin.getLogger().warning("Rate limit exceeded for proxy API. Assuming IP " + finalIp + " is not a proxy.");
                    boolean defaultResult = false; // Default to not proxy when rate limited
                    proxyCache.put(finalIp, defaultResult);
                    future.complete(defaultResult);
                    return;
                }

                try {
                    // Make API request
                    boolean isProxy = IPCheck.isProxy(finalIp);
                    requestCount.incrementAndGet();

                    // Cache the result
                    proxyCache.put(finalIp, isProxy);

                    // Complete the future
                    future.complete(isProxy);

                    // Log for monitoring
                    plugin.getLogger().info("Proxy check for " + finalIp + ": " + isProxy +
                            " (Requests made: " + requestCount.get() + "/45, Available permits: " + rateLimitSemaphore.availablePermits() + ")");

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to check proxy status for " + finalIp + ": " + e.getMessage());
                    // Default to not proxy on error
                    boolean defaultResult = false;
                    proxyCache.put(finalIp, defaultResult);
                    future.complete(defaultResult);
                } finally {
                    // Don't release the semaphore here - it gets reset by the timer task
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error in proxy check for " + finalIp + ": " + e.getMessage());
                boolean defaultResult = false;
                proxyCache.put(finalIp, defaultResult);
                future.complete(defaultResult);
            } finally {
                // Always remove from pending requests when done
                pendingRequests.remove(finalIp);
            }
        });

        // Save cache after potential update
        future.thenRun(this::saveAsync);

        return future;
    }

    /**
     * Checks if an IP is cached as a proxy (synchronous, cache only)
     */
    public Boolean getCachedProxyStatus(String ip) {
        if (ip == null) return null;
        return proxyCache.get(ip.trim());
    }

    /**
     * Manually adds an IP to the proxy cache
     */
    public void cacheProxyStatus(String ip, boolean isProxy) {
        if (ip != null && !ip.trim().isEmpty()) {
            proxyCache.put(ip.trim(), isProxy);
            saveAsync();
        }
    }

    /**
     * Removes an IP from the cache (useful if proxy status changes)
     */
    public void removeCachedIp(String ip) {
        if (ip != null) {
            proxyCache.remove(ip.trim());
            saveAsync();
        }
    }

    /**
     * Clears the entire cache
     */
    public void clearCache() {
        proxyCache.clear();
        saveAsync();
    }

    /**
     * Gets the size of the cache
     */
    public int getCacheSize() {
        return proxyCache.size();
    }

    /**
     * Gets current rate limit status
     */
    public String getRateLimitStatus() {
        return String.format("Requests: %d/45, Available permits: %d, Time until reset: %d seconds",
                requestCount.get(),
                rateLimitSemaphore.availablePermits(),
                Math.max(0, (RATE_LIMIT_WINDOW - (System.currentTimeMillis() - lastResetTime.get())) / 1000)
        );
    }

    /**
     * Saves the proxy cache to file asynchronously
     */
    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                YamlConfiguration config = new YamlConfiguration();

                // Create a snapshot to avoid concurrent modification
                Map<String, Boolean> snapshot = new HashMap<>(proxyCache);

                for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
                    // Replace dots with underscores for YAML key compatibility
                    String safeKey = entry.getKey().replace(".", "_").replace(":", "_");
                    config.set("proxies." + safeKey, entry.getValue());
                }

                config.save(cacheFile);
                plugin.getLogger().info("Proxy cache saved with " + snapshot.size() + " entries.");
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save proxy cache: " + e.getMessage());
            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error saving proxy cache: " + e.getMessage());
            }
        });
    }

    /**
     * Loads the proxy cache from file asynchronously
     */
    public void loadAsync(Runnable afterLoad) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!cacheFile.exists()) {
                    if (afterLoad != null) {
                        plugin.getServer().getScheduler().runTask(plugin, afterLoad);
                    }
                    return;
                }

                YamlConfiguration config = YamlConfiguration.loadConfiguration(cacheFile);

                if (config.getConfigurationSection("proxies") != null) {
                    for (String key : config.getConfigurationSection("proxies").getKeys(false)) {
                        // Convert back from safe key format
                        String ip = key.replace("_", ".");
                        boolean isProxy = config.getBoolean("proxies." + key);
                        proxyCache.put(ip, isProxy);
                    }
                }

                plugin.getLogger().info("Proxy cache loaded with " + proxyCache.size() + " entries.");

                if (afterLoad != null) {
                    plugin.getServer().getScheduler().runTask(plugin, afterLoad);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error loading proxy cache: " + e.getMessage());
                if (afterLoad != null) {
                    plugin.getServer().getScheduler().runTask(plugin, afterLoad);
                }
            }
        });
    }

    /**
     * Gets a copy of the cache for debugging purposes
     */
    public Map<String, Boolean> getCacheSnapshot() {
        return new HashMap<>(proxyCache);
    }
}