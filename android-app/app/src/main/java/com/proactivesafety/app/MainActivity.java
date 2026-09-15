package com.proactivesafety.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends android.app.Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 4001;
    private static final int NOTIFICATION_REQUEST = 4002;
    private static final int COLOR_BG = Color.rgb(7, 19, 15);
    private static final int COLOR_SURFACE = Color.rgb(16, 35, 29);
    private static final int COLOR_SURFACE_ALT = Color.rgb(15, 50, 54);
    private static final int COLOR_TEAL = Color.rgb(41, 199, 164);
    private static final int COLOR_SAFE = Color.rgb(90, 211, 157);
    private static final int COLOR_CAUTION = Color.rgb(255, 204, 77);
    private static final int COLOR_HIGH = Color.rgb(255, 145, 77);
    private static final int COLOR_DANGER = Color.rgb(255, 77, 77);
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_TEXT_SECONDARY = Color.rgb(167, 184, 177);
    private static final int COLOR_MUTED = Color.rgb(115, 132, 125);
    private static final int COLOR_BORDER = Color.rgb(41, 66, 58);

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
    private TextView speedValueView;
    private TextView nearbyUsersView;
    private TextView conflictDistanceView;
    private TextView conflictTitleView;
    private TextView conflictMetaView;
    private TextView riskPillView;
    private TextView actionTitleView;
    private TextView actionMessageView;
    private TextView actionMetaView;
    private View conflictBanner;
    private View actionPanel;
    private Button monitorButton;
    private boolean monitoring = false;
    private boolean cloudConnected = false;
    private long lastCooperativePublishAt = 0L;
    private long lastMapRenderAt = 0L;
    private CooperativeAlert lastCooperativeAlert;
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
        assessment.cooperativeAlert = lastCooperativeAlert;
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
            setConnectionStatus(false, "GPS off");
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private View buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(COLOR_BG);

        realMapView = new RealMapView(this);
        screen.addView(realMapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(10), dp(8));
        topBar.setBackground(createRoundedBackground(Color.argb(235, 16, 35, 29), dp(20), 0, 0));
        topBar.setElevation(dp(6));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(64)
        );
        topParams.setMargins(dp(14), getStatusBarHeight() + dp(8), dp(14), 0);
        screen.addView(topBar, topParams);

        TextView badge = text("PS", 12, COLOR_BG, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(createRoundedBackground(COLOR_TEAL, dp(16), 0, 0));
        topBar.addView(badge, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(10), 0, dp(8), 0);
        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBlock.addView(text("Proactive Safety", 18, COLOR_TEXT, true));
        statusView = text("Checking", 11, COLOR_TEXT_SECONDARY, true);
        titleBlock.addView(statusView);

        monitorButton = hudButton("Start", COLOR_TEAL, COLOR_BG);
        monitorButton.setOnClickListener(view -> toggleMonitoring());
        topBar.addView(monitorButton, new LinearLayout.LayoutParams(dp(70), dp(42)));

        Button settingsButton = hudButton("Settings", Color.argb(215, 15, 50, 54), COLOR_TEXT);
        settingsButton.setContentDescription("Open settings");
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(78), dp(42));
        settingsParams.setMargins(dp(8), 0, 0, 0);
        topBar.addView(settingsButton, settingsParams);

        LinearLayout warning = new LinearLayout(this);
        warning.setOrientation(LinearLayout.VERTICAL);
        warning.setPadding(dp(16), dp(10), dp(16), dp(10));
        warning.setBackground(createRoundedBackground(Color.argb(235, 16, 35, 29), dp(20), dp(1), COLOR_BORDER));
        warning.setElevation(dp(8));
        warning.setVisibility(View.GONE);
        conflictBanner = warning;
        conflictTitleView = text("VEHICLE APPROACHING", 18, COLOR_TEXT, true);
        conflictMetaView = text("--", 13, COLOR_TEXT_SECONDARY, true);
        warning.addView(conflictTitleView);
        warning.addView(conflictMetaView);
        FrameLayout.LayoutParams warningParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        warningParams.setMargins(dp(18), getStatusBarHeight() + dp(84), dp(18), 0);
        screen.addView(warning, warningParams);

        LinearLayout speedHud = new LinearLayout(this);
        speedHud.setOrientation(LinearLayout.VERTICAL);
        speedHud.setGravity(Gravity.CENTER);
        speedHud.setPadding(dp(8), dp(8), dp(8), dp(8));
        speedHud.setBackground(createRoundedBackground(Color.argb(220, 7, 19, 15), dp(24), dp(1), COLOR_BORDER));
        speedHud.setElevation(dp(6));
        speedValueView = text("--", 34, COLOR_TEXT, true);
        speedValueView.setGravity(Gravity.CENTER);
        TextView speedUnit = text("km/h", 12, COLOR_TEXT_SECONDARY, true);
        speedUnit.setGravity(Gravity.CENTER);
        speedHud.addView(speedValueView);
        speedHud.addView(speedUnit);
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(dp(96), dp(96));
        speedParams.gravity = Gravity.START | Gravity.BOTTOM;
        speedParams.setMargins(dp(18), 0, 0, dp(184));
        screen.addView(speedHud, speedParams);

        nearbyUsersView = text("0 nearby", 13, COLOR_TEXT, true);
        nearbyUsersView.setGravity(Gravity.CENTER);
        nearbyUsersView.setPadding(dp(12), 0, dp(12), 0);
        nearbyUsersView.setBackground(createRoundedBackground(Color.argb(220, 15, 50, 54), dp(18), dp(1), COLOR_BORDER));
        FrameLayout.LayoutParams nearbyParams = new FrameLayout.LayoutParams(dp(118), dp(38));
        nearbyParams.gravity = Gravity.START | Gravity.BOTTOM;
        nearbyParams.setMargins(dp(18), 0, 0, dp(140));
        screen.addView(nearbyUsersView, nearbyParams);

        Button sosButton = hudButton("SOS", COLOR_DANGER, COLOR_TEXT);
        sosButton.setTextSize(17);
        sosButton.setContentDescription("Emergency SOS");
        sosButton.setOnClickListener(view -> handleSosClick());
        FrameLayout.LayoutParams sosParams = new FrameLayout.LayoutParams(dp(76), dp(64));
        sosParams.gravity = Gravity.END | Gravity.BOTTOM;
        sosParams.setMargins(0, 0, dp(18), dp(156));
        screen.addView(sosButton, sosParams);

        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(18), dp(14), dp(18), dp(14));
        bottomPanel.setBackground(createRoundedBackground(Color.argb(238, 16, 35, 29), dp(26), dp(1), COLOR_BORDER));
        bottomPanel.setElevation(dp(10));
        actionPanel = bottomPanel;

        LinearLayout riskRow = new LinearLayout(this);
        riskRow.setOrientation(LinearLayout.HORIZONTAL);
        riskRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomPanel.addView(riskRow, matchWrap());

        riskPillView = text("SAFE", 13, COLOR_SAFE, true);
        riskPillView.setGravity(Gravity.CENTER);
        riskPillView.setPadding(dp(12), 0, dp(12), 0);
        riskPillView.setBackground(createRoundedBackground(Color.argb(45, 90, 211, 157), dp(15), dp(1), COLOR_SAFE));
        riskRow.addView(riskPillView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));

        conflictDistanceView = text("No conflict", 13, COLOR_TEXT_SECONDARY, true);
        conflictDistanceView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        riskRow.addView(conflictDistanceView, new LinearLayout.LayoutParams(0, dp(32), 1f));

        actionTitleView = text("READY", 24, COLOR_TEXT, true);
        actionTitleView.setPadding(0, dp(8), 0, 0);
        bottomPanel.addView(actionTitleView);
        actionMessageView = text("Start monitoring for live safety alerts", 15, COLOR_TEXT_SECONDARY, false);
        bottomPanel.addView(actionMessageView);
        actionMetaView = text("", 12, COLOR_MUTED, false);
        bottomPanel.addView(actionMetaView);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(dp(14), 0, dp(14), dp(18));
        screen.addView(bottomPanel, bottomParams);

        // Kept for compatibility with existing render code; not attached to the main HUD.
        levelView = riskPillView;
        scoreView = text("", 1, Color.TRANSPARENT, false);
        speedView = speedValueView;
        zoneView = nearbyUsersView;
        distanceView = conflictDistanceView;
        cooperativeView = text("", 1, Color.TRANSPARENT, false);
        actionView = actionMessageView;
        factorView = text("", 1, Color.TRANSPARENT, false);

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

    private Button hudButton(String label, int fillColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(createRoundedBackground(fillColor, dp(18), dp(1), Color.argb(90, 255, 255, 255)));
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

    private GradientDrawable createRoundedBackground(int color, int radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) drawable.setStroke(strokeWidthPx, strokeColor);
        return drawable;
    }

    private void showSettingsDialog() {
        ScrollView settingsScroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(8), dp(8), 0);
        settingsScroll.addView(container);

        container.addView(settingsHeader("Connection"));
        EditText serverInput = dialogInput("Backend URL", CooperativeSafetyClient.serverUrl(this));
        container.addView(serverInput);

        Button testButton = smallButton("Test Cloud Connection");
        testButton.setOnClickListener(view -> testBackendConnection());
        container.addView(testButton);

        container.addView(settingsLine("Current cloud status", cloudConnected ? "Connected" : "Not connected"));
        container.addView(settingsLine("Data refresh interval", "Adaptive: 0.5 sec to 5 sec"));

        container.addView(settingsHeader("Map"));
        EditText olaInput = dialogInput("Ola Maps API Key", CooperativeSafetyClient.olaApiKey(this));
        container.addView(olaInput);

        container.addView(settingsHeader("Location and device"));
        container.addView(settingsLine("GPS Status", isAnyLocationProviderEnabled() ? "Active" : "Off"));
        container.addView(settingsLine("Vehicle ID", CooperativeSafetyClient.vehicleId(this)));
        if (lastLocation != null) {
            container.addView(settingsLine("Raw latitude", String.format(Locale.US, "%.6f", lastLocation.getLatitude())));
            container.addView(settingsLine("Raw longitude", String.format(Locale.US, "%.6f", lastLocation.getLongitude())));
            container.addView(settingsLine("Location accuracy", lastLocation.hasAccuracy() ? String.format(Locale.US, "%.0f m", lastLocation.getAccuracy()) : "Unknown"));
        } else {
            container.addView(settingsLine("Raw location", "Waiting for GPS"));
        }

        container.addView(settingsHeader("Alert preferences"));
        container.addView(settingsCheck("Alert sound", true));
        container.addView(settingsCheck("Vibration", true));
        container.addView(settingsCheck("Voice alert", true));

        container.addView(settingsHeader("Developer"));
        container.addView(settingsCheck("Debug mode", false));
        container.addView(settingsLine("Raw risk score", "Hidden on main UI"));

        container.addView(settingsHeader("About"));
        container.addView(settingsLine("About", "App-to-app cooperative safety using GPS, speed, heading and cloud sharing."));

        new AlertDialog.Builder(this)
                .setTitle("App Settings")
                .setView(settingsScroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    CooperativeSafetyClient.saveServerUrl(this, serverInput.getText().toString());
                    CooperativeSafetyClient.saveOlaApiKey(this, olaInput.getText().toString());
                    setConnectionStatus(false, "Checking");
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

    private TextView settingsLine(String label, String value) {
        TextView view = text(label + "\n" + value, 13, Color.rgb(24, 35, 33), false);
        view.setPadding(0, dp(6), 0, dp(8));
        return view;
    }

    private TextView settingsHeader(String label) {
        TextView view = text(label, 12, Color.rgb(15, 50, 54), true);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private CheckBox settingsCheck(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(13);
        box.setChecked(checked);
        box.setTextColor(Color.rgb(24, 35, 33));
        box.setPadding(0, dp(2), 0, dp(2));
        return box;
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
        monitorButton.setText("Stop");
        setConnectionStatus(cloudConnected, cloudConnected ? "Connected" : "Checking");

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
        monitorButton.setText("Start");
        setConnectionStatus(cloudConnected, cloudConnected ? "Connected" : "Paused");
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
            setConnectionStatus(false, "GPS off");
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
        if (speedKmh < 20) return 2000L;
        if (speedKmh >= 60) return 500L;
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
        applyRiskTheme("LOW");
        scoreView.setText("Risk Score\n--");
        speedValueView.setText("--");
        nearbyUsersView.setText("-- nearby");
        conflictDistanceView.setText("No conflict");
        if (conflictBanner != null) conflictBanner.setVisibility(View.GONE);
        actionTitleView.setText("READY");
        actionMessageView.setText("Start monitoring for live safety alerts");
        actionMetaView.setText("");
        monitorButton.setText("Start");
        setConnectionStatus(cloudConnected, cloudConnected ? "Connected" : "Checking");
        if (realMapView != null) {
            realMapView.update(null, null, null, monitoring);
        }
    }

    private void renderAssessment(Location location, SafetyAssessment assessment) {
        String effectiveLevel = moreSevereLevel(assessment.level, assessment.cooperativeAlert);
        applyRiskTheme(effectiveLevel);
        speedValueView.setText(String.valueOf(Math.max(0, RiskEngine.speedKmh(location))));
        renderCooperativeMetrics(assessment.cooperativeAlert);
        renderConflictBanner(assessment.cooperativeAlert, effectiveLevel);
        renderActionPanel(effectiveLevel, assessment.action, assessment.cooperativeAlert);
        setConnectionStatus(cloudConnected, cloudConnected ? "Connected" : "GPS Active");

        long now = System.currentTimeMillis();
        if (realMapView != null && now - lastMapRenderAt >= 300L) {
            lastMapRenderAt = now;
            realMapView.update(location, assessment, assessment.cooperativeAlert, monitoring);
        }
    }

    private void publishCooperativeLocation(Location location) {
        new Thread(() -> {
            try {
                CooperativeAlert alert = CooperativeSafetyClient.publishLocation(this, location);
                runOnUiThread(() -> {
                    setConnectionStatus(true, "Connected");
                    renderCooperativeAlert(alert);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setConnectionStatus(false, "Cloud Lost");
                    cooperativeView.setText("App-to-App Alert\nCloud connection lost. App-user alerts paused.");
                });
            }
        }).start();
    }

    private void testBackendConnection() {
        new Thread(() -> {
            try {
                boolean ok = CooperativeSafetyClient.ping(this);
                runOnUiThread(() -> setConnectionStatus(ok, ok ? "Connected" : "Cloud Lost"));
            } catch (Exception error) {
                runOnUiThread(() -> setConnectionStatus(false, "Cloud Lost"));
            }
        }).start();
    }

    private void setConnectionStatus(boolean connected, String label) {
        cloudConnected = connected;
        if (statusView == null) return;
        statusView.setText((connected ? "● " : "● ") + label);
        int warningColor = label.toLowerCase(Locale.US).contains("lost") || label.toLowerCase(Locale.US).contains("off")
                ? Color.rgb(255, 77, 77)
                : Color.rgb(255, 204, 77);
        statusView.setTextColor(connected ? Color.rgb(90, 211, 157) : warningColor);
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    private void renderCooperativeAlert(CooperativeAlert alert) {
        lastCooperativeAlert = alert;
        renderCooperativeMetrics(alert);
        String localLevel = "LOW";
        String localAction = "Continue with caution";
        SafetyAssessment assessment = lastLocation == null ? null : RiskEngine.assess(lastLocation);
        if (assessment != null) {
            localLevel = assessment.level;
            localAction = assessment.action;
            assessment.cooperativeAlert = alert;
        }
        String effectiveLevel = moreSevereLevel(localLevel, alert);
        applyRiskTheme(effectiveLevel);
        renderConflictBanner(alert, effectiveLevel);
        renderActionPanel(effectiveLevel, localAction, alert);

        if (alert != null && alert.present) {
            scoreView.setText("Risk Score\n" + Math.max(alert.score, 0) + "/100");
        }

        if (realMapView != null) {
            realMapView.update(lastLocation, assessment, alert, monitoring);
        }
    }

    private void renderCooperativeMetrics(CooperativeAlert alert) {
        int activeVehicles = alert == null ? 0 : alert.activeVehicles;
        nearbyUsersView.setText(activeVehicles + " nearby");

        if (alert == null) {
            conflictDistanceView.setText("No conflict");
            return;
        }

        if (!alert.present) {
            conflictDistanceView.setText("No conflict");
            return;
        }

        conflictDistanceView.setText(formatConflictDistance(alert));
    }

    private void renderConflictBanner(CooperativeAlert alert, String effectiveLevel) {
        if (conflictBanner == null) return;
        if (alert == null || !alert.present || severity(alert.level) < severity("MEDIUM")) {
            conflictBanner.setVisibility(View.GONE);
            return;
        }

        int accent = colorForLevel(alert.level);
        String direction = safeDirection(alert.direction);
        if ("CRITICAL".equals(normalizeLevel(alert.level))) {
            conflictTitleView.setText("STOP - VEHICLE FROM " + direction.toUpperCase(Locale.US));
        } else if ("HIGH".equals(normalizeLevel(alert.level))) {
            conflictTitleView.setText("HIGH COLLISION RISK");
        } else {
            conflictTitleView.setText("VEHICLE APPROACHING");
        }
        String meta = formatConflictDistance(alert);
        String seconds = formatSecondsToConflict(alert.secondsToConflict);
        if (!seconds.isEmpty()) meta = meta + " | " + seconds;
        conflictMetaView.setText(meta);
        conflictTitleView.setTextColor(accent);
        conflictBanner.setBackground(createRoundedBackground(Color.argb(238, 16, 35, 29), dp(20), dp(2), accent));
        conflictBanner.setVisibility(View.VISIBLE);
    }

    private void renderActionPanel(String level, String localAction, CooperativeAlert alert) {
        String normalized = normalizeLevel(level);
        if (alert != null && alert.present && severity(alert.level) >= severity("MEDIUM")) {
            if ("CRITICAL".equals(normalizeLevel(alert.level))) {
                actionTitleView.setText("STOP NOW");
                actionMessageView.setText("Do not enter the junction");
            } else if ("HIGH".equals(normalizeLevel(alert.level))) {
                actionTitleView.setText("SLOW DOWN");
                actionMessageView.setText(shortAction(alert.message, "Collision path detected ahead"));
            } else {
                actionTitleView.setText("VEHICLE APPROACHING");
                actionMessageView.setText(shortAction(alert.message, "Reduce speed and check your surroundings"));
            }
            actionMetaView.setText(formatConflictDistance(alert) + optionalSeconds(alert));
            return;
        }

        if ("CRITICAL".equals(normalized)) {
            actionTitleView.setText("STOP NOW");
            actionMessageView.setText(shortAction(localAction, "Do not enter the danger area"));
        } else if ("HIGH".equals(normalized)) {
            actionTitleView.setText("SLOW DOWN");
            actionMessageView.setText(shortAction(localAction, "High road risk ahead"));
        } else if ("MEDIUM".equals(normalized)) {
            actionTitleView.setText("CAUTION");
            actionMessageView.setText(shortAction(localAction, "Reduce speed and stay alert"));
        } else {
            actionTitleView.setText("ROAD CLEAR");
            actionMessageView.setText(monitoring ? "Continue with caution" : "Start monitoring for live safety alerts");
        }
        actionMetaView.setText("");
    }

    private void applyRiskTheme(String level) {
        String normalized = normalizeLevel(level);
        int color = colorForLevel(normalized);
        String label;
        if ("CRITICAL".equals(normalized)) label = "DANGER";
        else if ("HIGH".equals(normalized)) label = "HIGH RISK";
        else if ("MEDIUM".equals(normalized)) label = "CAUTION";
        else label = "SAFE";

        riskPillView.setText(label);
        riskPillView.setTextColor(color);
        riskPillView.setBackground(createRoundedBackground(Color.argb(55, Color.red(color), Color.green(color), Color.blue(color)), dp(15), dp(1), color));
        if (actionPanel != null) {
            actionPanel.setBackground(createRoundedBackground(Color.argb(238, 16, 35, 29), dp(26), dp(1), color));
        }
        nearbyUsersView.setTextColor(severity(normalized) >= severity("HIGH") ? color : COLOR_TEXT);
    }

    private String moreSevereLevel(String localLevel, CooperativeAlert alert) {
        String cooperativeLevel = alert != null && alert.present ? alert.level : "LOW";
        return severity(cooperativeLevel) > severity(localLevel) ? normalizeLevel(cooperativeLevel) : normalizeLevel(localLevel);
    }

    private int severity(String level) {
        String normalized = normalizeLevel(level);
        if ("CRITICAL".equals(normalized)) return 3;
        if ("HIGH".equals(normalized)) return 2;
        if ("MEDIUM".equals(normalized)) return 1;
        return 0;
    }

    private String normalizeLevel(String level) {
        if (level == null) return "LOW";
        String upper = level.trim().toUpperCase(Locale.US);
        if ("CRITICAL".equals(upper) || "HIGH".equals(upper) || "MEDIUM".equals(upper)) return upper;
        return "LOW";
    }

    private String formatConflictDistance(CooperativeAlert alert) {
        if (alert == null || !alert.present) return "No conflict";
        int distanceMeters = alert.distanceMeters;
        if (distanceMeters <= 0 && alert.hasOtherLocation && lastLocation != null) {
            float[] result = new float[1];
            Location.distanceBetween(
                    lastLocation.getLatitude(),
                    lastLocation.getLongitude(),
                    alert.otherLatitude,
                    alert.otherLongitude,
                    result
            );
            distanceMeters = Math.round(result[0]);
        }
        String distance = distanceMeters > 0 ? distanceMeters + " m" : "--";
        String direction = safeDirection(alert.direction);
        return direction.isEmpty() ? distance : distance + " | from " + direction;
    }

    private String formatSecondsToConflict(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return "";
        return String.format(Locale.US, "%.0f sec", seconds);
    }

    private String optionalSeconds(CooperativeAlert alert) {
        String seconds = alert == null ? "" : formatSecondsToConflict(alert.secondsToConflict);
        return seconds.isEmpty() ? "" : " | " + seconds;
    }

    private String safeDirection(String direction) {
        if (direction == null) return "";
        String clean = direction.trim().toLowerCase(Locale.US).replace('_', '-');
        if (clean.length() > 16) return "";
        return clean;
    }

    private String shortAction(String message, String fallback) {
        if (message == null || message.trim().isEmpty()) return fallback;
        String clean = message.replace("CRITICAL:", "").replace("HIGH RISK:", "").trim();
        return clean.length() > 62 ? fallback : clean;
    }

    private int colorForLevel(String level) {
        String normalized = normalizeLevel(level);
        if ("CRITICAL".equals(normalized)) return COLOR_DANGER;
        if ("HIGH".equals(normalized)) return COLOR_HIGH;
        if ("MEDIUM".equals(normalized)) return COLOR_CAUTION;
        return COLOR_SAFE;
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

    private void handleSosClick() {
        showSosDialog();
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
                setConnectionStatus(false, "GPS off");
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
