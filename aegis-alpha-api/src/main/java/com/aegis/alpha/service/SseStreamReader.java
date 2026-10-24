package com.aegis.alpha.service;

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

    /**
     * Cancellation handle (P2-17/P2-19): {@link #cancel()} stops reading and closes the upstream
     * connection. After cancellation no further handler callbacks are made.
     */
    public static final class Handle {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpURLConnection connection) {
            this.connection = connection;
            if (cancelled) {
                cancel();
            }
        }
    }

    public static void readSse(String urlStr, String jsonBody, SseEventHandler handler) {
        readSse(urlStr, jsonBody, null, handler);
    }

    public static void readSse(String urlStr, String jsonBody, String authorization, SseEventHandler handler) {
        readSse(urlStr, jsonBody, authorization, handler, null);
    }

    public static void readSse(String urlStr, String jsonBody, String authorization, SseEventHandler handler,
                               Handle handle) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            if (handle != null) {
                handle.attach(connection);
            }
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (authorization != null && !authorization.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", authorization.startsWith("Bearer ")
                        ? authorization
                        : "Bearer " + authorization);
            }
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
                if (handle != null && handle.isCancelled()) {
                    reader.close();
                    return;
                }
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

            if (handle != null && handle.isCancelled()) {
                reader.close();
                return;
            }
            if (currentData.length() > 0) {
                handler.onEvent(currentEvent, currentData.toString().trim());
            }
            reader.close();
            handler.onComplete();

        } catch (Exception ex) {
            if (handle != null && handle.isCancelled()) {
                return;  // the disconnect we caused; not an upstream error
            }
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