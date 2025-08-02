package net.ravenclaw.deepalts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyCache {

    private final Map<String, Boolean> proxyCache = new ConcurrentHashMap<>(); // hashedIp -> isProxy
    private final Map<String, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>(); // hashedIp -> future
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
     * Hashes an IP address using SHA-256
     */
    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("SHA-256 algorithm not available: " + e.getMessage());
            // Fallback to hashCode if SHA-256 fails (less secure but functional)
            return String.valueOf(ip.hashCode());
        }
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
                    //plugin.getLogger().info("Proxy API rate limit reset. Available permits: " + rateLimitSemaphore.availablePermits());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in rate limit reset task: " + e.getMessage());
            }
        }, 0L, 20L * 10); // Run every 10 seconds to check
    }

    /**
     * Checks if an IP is a proxy, using cache first, then API if not cached
     * Prevents duplicate requests for the same IP and respects rate limits
     * Returns both the proxy status and the hashed IP
     */
    public CompletableFuture<ProxyResult> checkProxy(String ip) {
        // Validate input
        if (ip == null || ip.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ProxyResult(false, hashIp("")));
        }

        // Normalize IP (trim whitespace)
        ip = ip.trim();
        final String actualIp = ip;
        final String hashedIp = hashIp(ip);

        // Check cache first
        if (proxyCache.containsKey(hashedIp)) {
            return CompletableFuture.completedFuture(new ProxyResult(proxyCache.get(hashedIp), hashedIp));
        }

        // Check if there's already a pending request for this hashed IP
        CompletableFuture<Boolean> existingRequest = pendingRequests.get(hashedIp);
        if (existingRequest != null) {
            return existingRequest.thenApply(isProxy -> new ProxyResult(isProxy, hashedIp));
        }

        // Create new request
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Try to add to pending requests atomically
        CompletableFuture<Boolean> existingFuture = pendingRequests.putIfAbsent(hashedIp, future);
        if (existingFuture != null) {
            // Another thread already created a request for this hashed IP
            return existingFuture.thenApply(isProxy -> new ProxyResult(isProxy, hashedIp));
        }

        // We're the first thread to request this IP, so make the API call
        CompletableFuture.runAsync(() -> {
            try {
                // Try to acquire rate limit permit
                if (!rateLimitSemaphore.tryAcquire()) {
                    plugin.getLogger().warning("Rate limit exceeded for proxy API. Assuming IP hash " + hashedIp.substring(0, 8) + "... is not a proxy.");
                    boolean defaultResult = false; // Default to not proxy when rate limited
                    proxyCache.put(hashedIp, defaultResult);
                    future.complete(defaultResult);
                    return;
                }

                try {
                    // Make API request with actual IP, but only store the hash
                    boolean isProxy = IPCheck.isProxy(actualIp);
                    requestCount.incrementAndGet();

                    // Cache the result with hashed IP
                    proxyCache.put(hashedIp, isProxy);

                    // Complete the future
                    future.complete(isProxy);

                    // Log for monitoring (only show hash prefix for privacy)
                    plugin.getLogger().info("Proxy check for IP hash " + hashedIp.substring(0, 8) + "...: " + isProxy +
                            " (Requests made: " + requestCount.get() + "/45, Available permits: " + rateLimitSemaphore.availablePermits() + ")");

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to check proxy status for IP hash " + hashedIp.substring(0, 8) + "...: " + e.getMessage());
                    // Default to not proxy on error
                    boolean defaultResult = false;
                    proxyCache.put(hashedIp, defaultResult);
                    future.complete(defaultResult);
                } finally {
                    // Don't release the semaphore here - it gets reset by the timer task
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Unexpected error in proxy check for IP hash " + hashedIp.substring(0, 8) + "...: " + e.getMessage());
                boolean defaultResult = false;
                proxyCache.put(hashedIp, defaultResult);
                future.complete(defaultResult);
            } finally {
                // Always remove from pending requests when done
                pendingRequests.remove(hashedIp);
            }
        });

        // Save cache after potential update
        future.thenRun(this::saveAsync);

        return future.thenApply(isProxy -> new ProxyResult(isProxy, hashedIp));
    }

    /**
     * Checks if a hashed IP is cached as a proxy (synchronous, cache only)
     */
    public Boolean getCachedProxyStatus(String hashedIp) {
        if (hashedIp == null) return null;
        return proxyCache.get(hashedIp);
    }

    public int getCacheSize() {
        return proxyCache.size();
    }

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
                Map<String, Boolean> snapshot = new HashMap<>(proxyCache);

                for (Map.Entry<String, Boolean> entry : snapshot.entrySet()) {
                    // hashedIp is already safe for YAML keys
                    config.set("proxies." + entry.getKey(), entry.getValue());
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
                    boolean migrationNeeded = false;

                    for (String key : config.getConfigurationSection("proxies").getKeys(false)) {
                        boolean isProxy = config.getBoolean("proxies." + key);

                        // Check if this key looks like a plain IP (contains dots or colons)
                        if (isPlainIp(key)) {
                            // This is an old format entry with actual IP
                            String hashedIp = hashIp(restoreIpFromKey(key));
                            proxyCache.put(hashedIp, isProxy);
                            migrationNeeded = true;
                            plugin.getLogger().info("Migrated proxy cache entry from plain IP to hash format");
                        } else {
                            // This is already a hashed IP or new format
                            proxyCache.put(key, isProxy);
                        }
                    }

                    // If we migrated any entries, save the updated cache
                    if (migrationNeeded) {
                        plugin.getLogger().info("Proxy cache migration completed. Saving updated format...");
                        saveAsync();
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
     * Checks if a key looks like a plain IP address
     */
    private boolean isPlainIp(String key) {
        // Check for IPv4 (contains dots but not too many underscores that would indicate it's a converted key)
        if (key.contains(".") && !key.contains("_")) {
            return true;
        }
        // Check for IPv6 (contains colons but not underscores)
        if (key.contains(":") && !key.contains("_")) {
            return true;
        }
        // If it's all hex characters and 64 chars long, it's likely already a SHA-256 hash
        if (key.length() == 64 && key.matches("[a-f0-9]+")) {
            return false;
        }
        // If it contains underscores, it might be the old converted format (dots/colons replaced with underscores)
        if (key.contains("_")) {
            return true;
        }
        return false;
    }

    /**
     * Restores IP from the old key format (converts underscores back to dots/colons)
     */
    private String restoreIpFromKey(String key) {
        // In the old format, dots and colons were replaced with underscores for YAML compatibility

        // First, try IPv4 format (should have exactly 3 dots)
        String[] parts = key.split("_");
        if (parts.length == 4) {
            // Likely IPv4, restore with dots
            return String.join(".", parts);
        } else if (parts.length > 4) {
            // Likely IPv6, restore with colons
            return String.join(":", parts);
        } else {
            // Fallback: if it's not the expected format, return as-is
            return key.replace("_", ".");
        }
    }

    /**
     * Gets a copy of the cache for debugging purposes (shows only hashed IPs)
     * This is primarily for administrative monitoring
     */
    public Map<String, Boolean> getCacheSnapshot() {
        return new HashMap<>(proxyCache);
    }

    /**
     * Result class to return both proxy status and hashed IP
     */
    public static class ProxyResult {
        private final boolean isProxy;
        private final String hashedIp;

        public ProxyResult(boolean isProxy, String hashedIp) {
            this.isProxy = isProxy;
            this.hashedIp = hashedIp;
        }

        public boolean isProxy() {
            return isProxy;
        }

        public String getHashedIp() {
            return hashedIp;
        }
    }
}