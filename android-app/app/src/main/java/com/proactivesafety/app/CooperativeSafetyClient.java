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
import java.util.Locale;

final class CooperativeSafetyClient {
    static final String PREFS = "proactive_safety";
    static final String KEY_SERVER_URL = "server_url";
    static final String DEFAULT_SERVER_URL = "http://10.199.230.148:3000";

    private CooperativeSafetyClient() {
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
    }

    static String vehicleId(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) return "android-unknown";
        return "android-" + androidId;
    }

    static CooperativeAlert publishLocation(Context context, Location location) throws Exception {
        String endpoint = serverUrl(context) + "/api/cooperative/location";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject payload = new JSONObject();
        payload.put("vehicleId", vehicleId(context));
        payload.put("latitude", location.getLatitude());
        payload.put("longitude", location.getLongitude());
        payload.put("speedKmh", RiskEngine.speedKmh(location));
        payload.put("headingDeg", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
        payload.put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : 0);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
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

        JSONObject json = new JSONObject(response.toString());
        int activeVehicles = json.optInt("activeVehicles", 1);
        JSONArray alerts = json.optJSONArray("alerts");
        if (alerts == null || alerts.length() == 0) {
            return CooperativeAlert.none(activeVehicles);
        }

        JSONObject alert = alerts.getJSONObject(0);
        JSONArray nearbyVehicles = json.optJSONArray("nearbyVehicles");
        double otherLatitude = 0;
        double otherLongitude = 0;
        boolean hasOtherLocation = false;
        String otherVehicleId = alert.optString("otherVehicleId", "");
        if (nearbyVehicles != null) {
            for (int i = 0; i < nearbyVehicles.length(); i++) {
                JSONObject vehicle = nearbyVehicles.getJSONObject(i);
                if (otherVehicleId.equals(vehicle.optString("vehicleId", ""))) {
                    otherLatitude = vehicle.optDouble("latitude", 0);
                    otherLongitude = vehicle.optDouble("longitude", 0);
                    hasOtherLocation = true;
                    break;
                }
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
                hasOtherLocation
        );
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
