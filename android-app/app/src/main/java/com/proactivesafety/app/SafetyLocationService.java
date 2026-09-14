package com.proactivesafety.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class SafetyLocationService extends Service implements LocationListener {
    static final String CHANNEL_ID = "safety_monitoring";
    static final String ALERT_CHANNEL_ID = "safety_alerts";
    private static final int NOTIFICATION_ID = 1001;

    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private TextToSpeech textToSpeech;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Location lastLocation;
    private SafetyAssessment lastAssessment;
    private long lastWarningAt = 0L;
    private long lastPublishedAt = 0L;
    private long lastHttpFallbackAt = 0L;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (lastLocation != null) {
                onLocationChanged(lastLocation);
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.setLanguage(Locale.US);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring road risk", "GPS safety monitoring is active."));
        CooperativeSafetyClient.startLiveSession(this, this::handleCooperativeAlert);
        requestLocationUpdates();
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 1000L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        CooperativeSafetyClient.stopLiveSession();
        handler.removeCallbacks(heartbeat);
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
        SafetyAssessment assessment = RiskEngine.assess(location);
        lastAssessment = assessment;
        long now = System.currentTimeMillis();
        if (now - lastPublishedAt >= cooperativeIntervalMs(location)) {
            lastPublishedAt = now;
            publishCooperativeLocation(location, assessment);
        }
    }

    private long cooperativeIntervalMs(Location location) {
        int speedKmh = RiskEngine.speedKmh(location);
        if (speedKmh < 5) return 5000L;
        if (speedKmh < 20) return 2000L;
        if (speedKmh >= 60) return 500L;
        return 1000L;
    }

    private void publishCooperativeLocation(Location location, SafetyAssessment assessment) {
        boolean socketSent = CooperativeSafetyClient.sendLiveLocation(this, location);
        long now = System.currentTimeMillis();
        if (socketSent && now - lastHttpFallbackAt < 5000L) {
            updateNotification(null, assessment);
            return;
        }
        lastHttpFallbackAt = now;

        new Thread(() -> {
            CooperativeAlert cooperativeAlert = null;
            try {
                cooperativeAlert = CooperativeSafetyClient.publishLocation(this, location);
            } catch (Exception ignored) {
            }

            handleCooperativeAlert(cooperativeAlert);
        }).start();
    }

    private void handleCooperativeAlert(CooperativeAlert cooperativeAlert) {
        SafetyAssessment assessment = lastAssessment;
        if (assessment == null && lastLocation != null) assessment = RiskEngine.assess(lastLocation);
        if (assessment == null) return;

        updateNotification(cooperativeAlert, assessment);

        if ((cooperativeAlert != null && cooperativeAlert.present) || assessment.shouldWarn()) {
            warnUser(cooperativeAlert != null && cooperativeAlert.present ? cooperativeAlert.message : assessment.action);
        }
    }

    private void updateNotification(CooperativeAlert cooperativeAlert, SafetyAssessment assessment) {
        if (cooperativeAlert != null && cooperativeAlert.present) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(cooperativeAlert.level + " app-user risk", cooperativeAlert.message, true)
            );
            return;
        }

        if (assessment.shouldWarn()) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(assessment.level + " road risk", assessment.action, true)
            );
            return;
        }

        notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification("Safety monitoring active", "No current road risk.", false)
        );
    }


    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

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
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Location is off", "Enable phone location/GPS for safety monitoring.", true)
            );
        }
    }

    private void warnUser(String warningText) {
        long now = System.currentTimeMillis();
        if (now - lastWarningAt < 6000L) return;
        lastWarningAt = now;

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 350, 150, 350, 150, 650};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        }

        if (textToSpeech != null) {
            textToSpeech.speak(warningText, TextToSpeech.QUEUE_FLUSH, null, "risk-warning");
        }
    }

    private Notification buildNotification(String title, String body) {
        return buildNotification(title, body, false);
    }

    private Notification buildNotification(String title, String body, boolean alertPriority) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, alertPriority ? ALERT_CHANNEL_ID : CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(alertPriority ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Road safety monitoring",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Live GPS risk warnings for known danger zones.");
        notificationManager.createNotificationChannel(channel);

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Road safety alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alertChannel.setDescription("High priority alerts for app-user and road risks.");
        notificationManager.createNotificationChannel(alertChannel);
    }
}
