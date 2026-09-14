package com.proactivesafety.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends android.app.Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 4001;
    private static final int NOTIFICATION_REQUEST = 4002;

    private LocationManager locationManager;
    private RealMapView realMapView;
    private TextView levelView;
    private TextView scoreView;
    private TextView speedView;
    private TextView zoneView;
    private TextView distanceView;
    private TextView cooperativeView;
    private TextView actionView;
    private TextView factorView;
    private TextView statusView;
    private Button monitorButton;
    private boolean monitoring = false;
    private long lastCooperativePublishAt = 0L;
    private long lastMapRenderAt = 0L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Location lastLocation;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (monitoring && lastLocation != null) {
                long now = System.currentTimeMillis();
                if (now - lastCooperativePublishAt >= cooperativeIntervalMs(lastLocation)) {
                    lastCooperativePublishAt = now;
                    publishCooperativeLocation(lastLocation);
                }
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        setContentView(buildUi());
        requestNotificationPermission();
        renderEmpty();
    }

    @Override
    protected void onDestroy() {
        stopLocalUpdates();
        handler.removeCallbacks(heartbeat);
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
        SafetyAssessment assessment = RiskEngine.assess(location);
        renderAssessment(location, assessment);
        long now = System.currentTimeMillis();
        if (now - lastCooperativePublishAt >= cooperativeIntervalMs(location)) {
            lastCooperativePublishAt = now;
            publishCooperativeLocation(location);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (!isAnyLocationProviderEnabled()) {
            statusView.setText("Phone location is off. Enable Location/GPS for live safety monitoring.");
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private View buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.rgb(7, 19, 15));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        screen.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, getStatusBarHeight() + 10, 22, 14);
        scrollView.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        TextView title = text("PROACTIVE SAFETY", 22, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button settingsButton = smallButton("Settings");
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        header.addView(settingsButton);
        root.addView(text("Live GPS + app-to-app cooperative safety", 13, Color.rgb(167, 198, 186), false));

        realMapView = new RealMapView(this);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(275)
        );
        mapParams.setMargins(0, dp(12), 0, dp(10));
        root.addView(realMapView, mapParams);

        levelView = compactHero("LOW", Color.rgb(90, 211, 157));
        root.addView(levelView);

        GridLayout metrics = new GridLayout(this);
        metrics.setColumnCount(2);
        metrics.setUseDefaultMargins(false);
        root.addView(metrics, matchWrap());

        scoreView = metricPanel("Risk Score", "--");
        speedView = metricPanel("Speed", "--");
        zoneView = metricPanel("Risk Zone", "--");
        distanceView = metricPanel("Distance", "--");
        metrics.addView(scoreView);
        metrics.addView(speedView);
        metrics.addView(zoneView);
        metrics.addView(distanceView);

        cooperativeView = panel("App-to-App Alert", "Waiting for live app users.");
        actionView = alertPanel("Recommended Action", "Start monitoring to use GPS safety alerts.");
        factorView = panel("Why warning happens", "--");
        statusView = text("Location permission is required.", 14, Color.rgb(167, 198, 186), false);

        root.addView(cooperativeView);
        root.addView(actionView);
        root.addView(factorView);
        root.addView(statusView);

        monitorButton = button("Start GPS Monitoring", Color.rgb(41, 199, 164), 64);
        monitorButton.setOnClickListener(view -> toggleMonitoring());
        screen.addView(monitorButton);

        Button sosButton = button("SOS Emergency", Color.rgb(255, 77, 77), 58);
        sosButton.setOnClickListener(view -> showSosDialog());
        root.addView(sosButton);

        TextView note = text("App users share GPS, speed, and heading through your backend.", 12, Color.rgb(167, 198, 186), false);
        note.setPadding(0, 24, 0, 0);
        root.addView(note);

        return screen;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView banner(String value, int color) {
        TextView view = text(value, 38, color, true);
        view.setPadding(0, 26, 0, 12);
        return view;
    }

    private TextView compactHero(String value, int color) {
        TextView view = text(value, 30, color, true);
        view.setPadding(0, 0, 0, 6);
        return view;
    }

    private TextView metricPanel(String label, String value) {
        TextView view = text(label + "\n" + value, 14, Color.WHITE, true);
        view.setBackgroundColor(Color.rgb(16, 35, 29));
        view.setPadding(14, 12, 14, 12);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(0, 0, dp(8), dp(10));
        view.setLayoutParams(params);
        return view;
    }

    private TextView panel(String label, String value) {
        TextView view = text(label + "\n" + value, 14, Color.WHITE, true);
        view.setBackgroundColor(Color.rgb(16, 35, 29));
        view.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 10, 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private TextView alertPanel(String label, String value) {
        return panel(label, value);
    }

    private Button button(String label, int color, int heightDp) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.rgb(6, 32, 25));
        button.setTextSize(18);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
        params.setMargins(dp(22), 10, dp(22), 12);
        button.setLayoutParams(params);
        return button;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.rgb(6, 32, 25));
        button.setTextSize(12);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setBackgroundColor(Color.rgb(255, 204, 77));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
        ));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) return getResources().getDimensionPixelSize(resourceId);
        return dp(24);
    }

    private void showSettingsDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(4), dp(6), dp(4), 0);

        EditText serverInput = dialogInput("Backend URL", CooperativeSafetyClient.serverUrl(this));
        EditText olaInput = dialogInput("Ola Maps API Key", CooperativeSafetyClient.olaApiKey(this));
        container.addView(serverInput);
        container.addView(olaInput);

        new AlertDialog.Builder(this)
                .setTitle("App Settings")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    CooperativeSafetyClient.saveServerUrl(this, serverInput.getText().toString());
                    CooperativeSafetyClient.saveOlaApiKey(this, olaInput.getText().toString());
                    statusView.setText("Settings saved. Checking backend.");
                    if (realMapView != null) realMapView.reloadMap();
                    testBackendConnection();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText dialogInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(10));
        input.setLayoutParams(params);
        return input;
    }

    private void toggleMonitoring() {
        if (monitoring) {
            stopMonitoring();
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_REQUEST
            );
            return;
        }

        startMonitoring();
    }

    private void startMonitoring() {
        monitoring = true;
        monitorButton.setText("Stop GPS Monitoring");
        statusView.setText("GPS monitoring active. Keep location on.");

        Intent intent = new Intent(this, SafetyLocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        requestLocalUpdates();
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 1000L);
    }

    private void stopMonitoring() {
        monitoring = false;
        monitorButton.setText("Start GPS Monitoring");
        statusView.setText("GPS monitoring stopped.");
        stopService(new Intent(this, SafetyLocationService.class));
        stopLocalUpdates();
        handler.removeCallbacks(heartbeat);
    }

    private void requestLocalUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        boolean requested = false;
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300L, 0f, this);
            requested = true;
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 3f, this);
            requested = true;
        }

        if (!requested) {
            statusView.setText("Phone location is off. Enable Location/GPS, then restart monitoring.");
            return;
        }

        Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastKnown == null) {
            lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        if (lastKnown != null) onLocationChanged(lastKnown);
    }

    private long cooperativeIntervalMs(Location location) {
        int speedKmh = RiskEngine.speedKmh(location);
        if (speedKmh < 5) return 5000L;
        return 1000L;
    }

    private boolean isAnyLocationProviderEnabled() {
        return locationManager != null &&
                (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private void stopLocalUpdates() {
        if (locationManager != null) locationManager.removeUpdates(this);
    }

    private void renderEmpty() {
        levelView.setText("LOW");
        scoreView.setText("Risk Score\n--");
        speedView.setText("Current Speed\n--");
        zoneView.setText("Nearest Risk Zone\n--");
        distanceView.setText("Distance to Zone\n--");
        cooperativeView.setText("App-to-App Cooperative Alert\nWaiting for live app users.");
        factorView.setText("Why warning happens\n--");
        if (realMapView != null) {
            realMapView.update(null, null, null, monitoring);
        }
    }

    private void renderAssessment(Location location, SafetyAssessment assessment) {
        int color = colorForLevel(assessment.level);
        levelView.setText(assessment.level);
        levelView.setTextColor(color);
        scoreView.setText("Risk Score\n" + assessment.score + "/100");
        speedView.setText("Current Speed\n" + RiskEngine.speedKmh(location) + " km/h");

        if (assessment.nearestZone != null) {
            zoneView.setText("Nearest Risk Zone\n" + assessment.nearestZone.name);
            distanceView.setText("Distance to Zone\n" + String.format(Locale.US, "%.0f m", assessment.distanceMeters));
        }

        actionView.setText("Recommended Action\n" + assessment.action);
        factorView.setText("Why warning happens\n" + String.join("\n", assessment.factors));

        statusView.setText(String.format(
                Locale.US,
                "GPS: %.6f, %.6f",
                location.getLatitude(),
                location.getLongitude()
        ));

        long now = System.currentTimeMillis();
        if (realMapView != null && now - lastMapRenderAt >= 500L) {
            lastMapRenderAt = now;
            realMapView.update(location, assessment, assessment.cooperativeAlert, monitoring);
        }
    }

    private void publishCooperativeLocation(Location location) {
        new Thread(() -> {
            try {
                CooperativeAlert alert = CooperativeSafetyClient.publishLocation(this, location);
                runOnUiThread(() -> renderCooperativeAlert(alert));
            } catch (Exception error) {
                runOnUiThread(() -> cooperativeView.setText(
                        "App-to-App Alert\nBackend not reachable\n" + shortError(error)
                ));
            }
        }).start();
    }

    private void testBackendConnection() {
        new Thread(() -> {
            try {
                boolean ok = CooperativeSafetyClient.ping(this);
                runOnUiThread(() -> statusView.setText(ok
                        ? "Backend connected: " + CooperativeSafetyClient.serverUrl(this)
                        : "Backend did not respond."));
            } catch (Exception error) {
                runOnUiThread(() -> statusView.setText("Backend failed: " + shortError(error)));
            }
        }).start();
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    private void renderCooperativeAlert(CooperativeAlert alert) {
        cooperativeView.setText("App-to-App Cooperative Alert\n" + CooperativeSafetyClient.describe(alert));

        if (alert != null && alert.present) {
            levelView.setText(alert.level);
            levelView.setTextColor(colorForLevel(alert.level));
            scoreView.setText("Risk Score\n" + Math.max(alert.score, 0) + "/100");
            actionView.setText("Recommended Action\n" + alert.message);
        }

        SafetyAssessment assessment = lastLocation == null ? null : RiskEngine.assess(lastLocation);
        if (assessment != null) {
            assessment.cooperativeAlert = alert;
        }
        if (realMapView != null) {
            realMapView.update(lastLocation, assessment, alert, monitoring);
        }
    }

    private int colorForLevel(String level) {
        if ("CRITICAL".equals(level)) return Color.rgb(255, 77, 77);
        if ("HIGH".equals(level)) return Color.rgb(255, 145, 77);
        if ("MEDIUM".equals(level)) return Color.rgb(255, 204, 77);
        return Color.rgb(90, 211, 157);
    }

    private void showSosDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Emergency SOS")
                .setMessage("Use this only in a real emergency. Share your location with emergency contacts or call local emergency services.")
                .setPositiveButton("Call 112", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMonitoring();
            } else {
                statusView.setText("Location permission denied. Open settings to allow GPS safety monitoring.");
                new AlertDialog.Builder(this)
                        .setTitle("Location Required")
                        .setMessage("Proactive Safety needs location permission to detect nearby road risk zones.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }
}
