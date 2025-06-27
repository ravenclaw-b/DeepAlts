package net.ravenclaw.deepAlts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class IPCheck {
    public static boolean isProxy(String ip) {
        try {
            String apiUrl = "http://ip-api.com/json/" + ip + "?fields=status,message,proxy";
            HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
            connection.setRequestMethod("GET");

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder jsonResponse = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                jsonResponse.append(line);
            }
            in.close();

            String response = jsonResponse.toString();
            return response.contains("\"proxy\":true");

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}