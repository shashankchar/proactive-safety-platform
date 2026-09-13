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
    private static final int NOTIFICATION_ID = 1001;

    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private TextToSpeech textToSpeech;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Location lastLocation;
    private long lastWarningAt = 0L;
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (lastLocation != null) {
                onLocationChanged(lastLocation);
            }
            handler.postDelayed(this, 5000L);
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
        requestLocationUpdates();
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 5000L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
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
        publishCooperativeLocation(location, assessment);
    }

    private void publishCooperativeLocation(Location location, SafetyAssessment assessment) {
        new Thread(() -> {
            CooperativeAlert cooperativeAlert = null;
            try {
                cooperativeAlert = CooperativeSafetyClient.publishLocation(this, location);
            } catch (Exception ignored) {
            }

            CooperativeAlert finalAlert = cooperativeAlert;
            String level = finalAlert != null && finalAlert.present ? finalAlert.level : assessment.level;
            String body = finalAlert != null && finalAlert.present ? finalAlert.message : assessment.action;
            String title = level + " road risk";

            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, body));

            if ((finalAlert != null && finalAlert.present) || assessment.shouldWarn()) {
                warnUser(body);
            }
        }).start();
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
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 3f, this);
            requested = true;
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 10f, this);
            requested = true;
        }
        if (!requested) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("Location is off", "Enable phone location/GPS for safety monitoring.")
            );
        }
    }

    private void warnUser(String warningText) {
        long now = System.currentTimeMillis();
        if (now - lastWarningAt < 12000L) return;
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
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Road safety monitoring",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Live GPS risk warnings for known danger zones.");
        notificationManager.createNotificationChannel(channel);
    }
}
