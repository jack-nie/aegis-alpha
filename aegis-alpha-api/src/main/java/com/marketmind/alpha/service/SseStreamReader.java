package com.marketmind.alpha.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SseStreamReader {

    public interface SseEventHandler {
        void onEvent(String eventName, String data);
        void onError(Exception ex);
        void onComplete();
    }

    public static void readSse(String urlStr, String jsonBody, SseEventHandler handler) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);

            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder errorMsg = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorMsg.append(line);
                }
                errorReader.close();
                handler.onError(new RuntimeException("HTTP " + responseCode + ": " + errorMsg.toString()));
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String currentEvent = "message";
            StringBuilder currentData = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (currentData.length() > 0) {
                        handler.onEvent(currentEvent, currentData.toString().trim());
                    }
                    currentEvent = "message";
                    currentData = new StringBuilder();
                } else if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (currentData.length() > 0) {
                        currentData.append("\n");
                    }
                    currentData.append(line.substring(5));
                } else if (line.startsWith(":")) {
                    // comment / keepalive, ignore
                }
            }

            if (currentData.length() > 0) {
                handler.onEvent(currentEvent, currentData.toString().trim());
            }
            reader.close();
            handler.onComplete();

        } catch (Exception ex) {
            handler.onError(ex);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
}