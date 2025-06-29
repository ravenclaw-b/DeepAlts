package net.ravenclaw.deepalts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class IPCheck {
    private static final int TIMEOUT_MS = 5000; // 5 second timeout
    private static final String USER_AGENT = "DeepAlts-Plugin/1.0";

    public static boolean isProxy(String ip) {
        // Validate IP format first
        if (!isValidIP(ip)) {
            System.err.println("Invalid IP format: " + ip);
            return false;
        }

        // Skip private/local IPs
        if (isPrivateIP(ip)) {
            System.out.println("Skipping private IP: " + ip);
            return false;
        }

        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            String apiUrl = "http://ip-api.com/json/" + ip + "?fields=status,message,proxy,hosting";
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();

            // Configure connection
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();

            // Handle rate limiting
            if (responseCode == 429) {
                String retryAfter = connection.getHeaderField("X-Ttl");
                System.err.println("Rate limited by ip-api. Retry after: " + retryAfter + " seconds");
                return false;
            }

            if (responseCode != 200) {
                System.err.println("IP-API returned error code: " + responseCode + " for IP: " + ip);
                return false;
            }

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder jsonResponse = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonResponse.append(line);
            }

            String response = jsonResponse.toString();

            if (response.contains("\"status\":\"fail\"")) {
                System.err.println("IP-API returned failure for " + ip + ": " + response);
                return false;
            }

            // Log remaining requests for monitoring
            String remainingRequests = connection.getHeaderField("X-Rl");
            if (remainingRequests != null) {
                System.out.println("IP-API requests remaining: " + remainingRequests);
            }

            // Return true if either proxy or hosting is true
            boolean isProxy = response.contains("\"proxy\":true") || response.contains("\"hosting\":true");
            System.out.println(ip + " is proxy/hosting: " + isProxy);
            return isProxy;

        } catch (SocketTimeoutException e) {
            System.err.println("Timeout checking proxy status for " + ip + ": " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Error checking proxy status for " + ip + ": " + e.getMessage());
            return false;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {}
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Basic IP validation
     */
    private static boolean isValidIP(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Check if IP is private/local (RFC 1918, loopback, etc.)
     */
    private static boolean isPrivateIP(String ip) {
        if (!isValidIP(ip)) {
            return false;
        }

        String[] parts = ip.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);

        // Loopback (127.x.x.x)
        if (first == 127) {
            return true;
        }

        // Private Class A (10.x.x.x)
        if (first == 10) {
            return true;
        }

        // Private Class B (172.16.x.x - 172.31.x.x)
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }

        // Private Class C (192.168.x.x)
        if (first == 192 && second == 168) {
            return true;
        }

        // Link-local (169.254.x.x)
        if (first == 169 && second == 254) {
            return true;
        }

        return false;
    }
}