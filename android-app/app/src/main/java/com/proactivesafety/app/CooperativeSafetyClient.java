package com.proactivesafety.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CooperativeSafetyClient {
    static final String PREFS = "proactive_safety";
    static final String KEY_SERVER_URL = "server_url";
    static final String KEY_OLA_API_KEY = "ola_maps_api_key";
    static final String DEFAULT_SERVER_URL = "https://proactive-safety-backend.shashankcharyaswork.chatgpt.site";
    private static CooperativeWebSocketClient webSocketClient;

    private CooperativeSafetyClient() {
    }

    interface AlertListener {
        void onAlert(CooperativeAlert alert);
    }

    static String serverUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    static void saveServerUrl(Context context, String value) {
        String url = value == null ? "" : value.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_URL, url)
                .apply();
        stopLiveSession();
    }

    static String olaApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_OLA_API_KEY, "");
    }

    static void saveOlaApiKey(Context context, String value) {
        String apiKey = value == null ? "" : value.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OLA_API_KEY, apiKey)
                .apply();
    }

    static String vehicleId(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) return "android-unknown";
        return "android-" + androidId;
    }

    static synchronized void startLiveSession(Context context, AlertListener listener) {
        if (webSocketClient != null && webSocketClient.isConnected()) return;

        try {
            String wsUrl = serverUrl(context)
                    .replaceFirst("^https://", "wss://")
                    .replaceFirst("^http://", "ws://") + "/api/cooperative/ws";
            webSocketClient = new CooperativeWebSocketClient(wsUrl, new CooperativeWebSocketClient.Listener() {
                @Override
                public void onMessage(String message) {
                    try {
                        if (listener != null) listener.onAlert(parseAlertResponse(message));
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onClosed() {
                }
            });
            webSocketClient.connect();
        } catch (Exception ignored) {
            webSocketClient = null;
        }
    }

    static synchronized void stopLiveSession() {
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
        }
    }

    static boolean sendLiveLocation(Context context, Location location) {
        try {
            if (webSocketClient == null || !webSocketClient.isConnected()) return false;
            return webSocketClient.send(locationPayload(context, location).toString());
        } catch (Exception error) {
            return false;
        }
    }

    static JSONObject locationPayload(Context context, Location location) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("vehicleId", vehicleId(context));
        payload.put("latitude", location.getLatitude());
        payload.put("longitude", location.getLongitude());
        payload.put("speedKmh", RiskEngine.speedKmh(location));
        payload.put("headingDeg", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
        payload.put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : 0);
        payload.put("clientTime", System.currentTimeMillis());

        if (location.hasBearing() && location.hasSpeed()) {
            payload.put("predicted2s", predictedPoint(location, 2));
            payload.put("predicted5s", predictedPoint(location, 5));
        }

        return payload;
    }

    private static JSONObject predictedPoint(Location location, int seconds) throws Exception {
        double speedMetersPerSecond = Math.max(0, location.getSpeed());
        double distanceMeters = speedMetersPerSecond * seconds;
        double headingRadians = Math.toRadians(location.getBearing());
        double latOffset = Math.cos(headingRadians) * distanceMeters / 111_320d;
        double lngOffset = Math.sin(headingRadians) * distanceMeters /
                (111_320d * Math.cos(Math.toRadians(location.getLatitude())));

        JSONObject point = new JSONObject();
        point.put("seconds", seconds);
        point.put("latitude", location.getLatitude() + latOffset);
        point.put("longitude", location.getLongitude() + lngOffset);
        return point;
    }

    static CooperativeAlert publishLocation(Context context, Location location) throws Exception {
        String endpoint = serverUrl(context) + "/api/cooperative/location";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] body = locationPayload(context, location).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
        ));

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Cooperative backend error: " + status);
        }

        return parseAlertResponse(response.toString());
    }

    static CooperativeAlert parseAlertResponse(String response) throws Exception {
        JSONObject json = new JSONObject(response);
        int activeVehicles = json.optInt("activeVehicles", 1);
        List<NearbyVehicle> nearby = parseNearbyVehicles(json.optJSONArray("nearbyVehicles"));
        JSONArray alerts = json.optJSONArray("alerts");
        if (alerts == null || alerts.length() == 0) {
            return CooperativeAlert.none(activeVehicles, nearby);
        }

        JSONObject alert = alerts.getJSONObject(0);
        double otherLatitude = 0;
        double otherLongitude = 0;
        boolean hasOtherLocation = false;
        String otherVehicleId = alert.optString("otherVehicleId", "");
        for (NearbyVehicle vehicle : nearby) {
            if (otherVehicleId.equals(vehicle.vehicleId)) {
                    otherLatitude = vehicle.latitude;
                    otherLongitude = vehicle.longitude;
                    hasOtherLocation = true;
                    break;
            }
        }

        return new CooperativeAlert(
                true,
                alert.optString("level", "HIGH"),
                alert.optInt("score", 70),
                alert.optString("message", "Nearby app-user vehicle conflict detected."),
                otherVehicleId,
                alert.optString("direction", ""),
                alert.optInt("closingSpeedKmh", 0),
                alert.optDouble("secondsToConflict", 0),
                activeVehicles,
                otherLatitude,
                otherLongitude,
                hasOtherLocation,
                nearby
        );
    }

    private static List<NearbyVehicle> parseNearbyVehicles(JSONArray nearbyVehicles) throws Exception {
        List<NearbyVehicle> nearby = new ArrayList<>();
        if (nearbyVehicles == null) return nearby;

        for (int i = 0; i < nearbyVehicles.length(); i++) {
            JSONObject vehicle = nearbyVehicles.getJSONObject(i);
            boolean hasHeading = !vehicle.isNull("headingDeg");
            nearby.add(new NearbyVehicle(
                    vehicle.optString("vehicleId", ""),
                    vehicle.optDouble("latitude", 0),
                    vehicle.optDouble("longitude", 0),
                    vehicle.optInt("speedKmh", 0),
                    hasHeading ? vehicle.optDouble("headingDeg", 0) : 0,
                    vehicle.optLong("ageMs", 0),
                    hasHeading
            ));
        }
        return nearby;
    }

    static String describe(CooperativeAlert alert) {
        if (alert == null) return "Cooperative safety unavailable.";
        if (!alert.present) {
            return String.format(Locale.US, "No app-user conflict. Active app vehicles: %d", alert.activeVehicles);
        }
        return String.format(
                Locale.US,
                "%s\nOther app user: %s\nDirection: %s\nClosing speed: %d km/h\nTime to conflict: %.1f sec",
                alert.message,
                alert.otherVehicleId,
                alert.direction,
                alert.closingSpeedKmh,
                alert.secondsToConflict
        );
    }

    static boolean ping(Context context) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl(context) + "/api/health").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);
        int status = connection.getResponseCode();
        connection.disconnect();
        return status >= 200 && status < 300;
    }
}
