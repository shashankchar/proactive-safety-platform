package com.proactivesafety.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.view.View;

import java.util.Locale;

public class SafetyMapView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private SafetyAssessment assessment;
    private CooperativeAlert cooperativeAlert;
    private Location location;
    private boolean monitoring;

    public SafetyMapView(Context context) {
        super(context);
        setMinimumHeight(dp(310));
    }

    public void update(Location location, SafetyAssessment assessment, CooperativeAlert cooperativeAlert, boolean monitoring) {
        this.location = location;
        this.assessment = assessment;
        this.cooperativeAlert = cooperativeAlert;
        this.monitoring = monitoring;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float scale = getResources().getDisplayMetrics().density;

        drawBackground(canvas, width, height);
        drawRoads(canvas, width, height, scale);
        drawRiskZone(canvas, width, height);
        drawUserArrow(canvas, width, height);
        drawCooperativeThreat(canvas, width, height);
        drawStatus(canvas, width, height);
    }

    private void drawBackground(Canvas canvas, int width, int height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(9, 22, 19));
        canvas.drawRoundRect(new RectF(0, 0, width, height), dp(18), dp(18), paint);

        paint.setColor(Color.rgb(13, 31, 27));
        for (int i = -height; i < width; i += dp(56)) {
            canvas.drawLine(i, height, i + height, 0, paint);
        }
    }

    private void drawRoads(Canvas canvas, int width, int height, float scale) {
        float centerX = width * 0.52f;
        float centerY = height * 0.48f;
        float roadWidth = dp(62);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(roadWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.rgb(38, 48, 48));
        canvas.drawLine(centerX, -dp(30), centerX, height + dp(30), paint);
        canvas.drawLine(-dp(30), centerY, width + dp(30), centerY, paint);
        canvas.drawLine(width * 0.12f, height * 0.18f, width * 0.88f, height * 0.82f, paint);

        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.rgb(120, 135, 132));
        canvas.drawLine(centerX - roadWidth / 2, 0, centerX - roadWidth / 2, height, paint);
        canvas.drawLine(centerX + roadWidth / 2, 0, centerX + roadWidth / 2, height, paint);
        canvas.drawLine(0, centerY - roadWidth / 2, width, centerY - roadWidth / 2, paint);
        canvas.drawLine(0, centerY + roadWidth / 2, width, centerY + roadWidth / 2, paint);

        paint.setStrokeWidth(dp(4));
        paint.setColor(Color.rgb(255, 213, 83));
        paint.setStrokeCap(Paint.Cap.SQUARE);
        for (float y = -dp(20); y < height; y += dp(34)) {
            canvas.drawLine(centerX, y, centerX, y + dp(18), paint);
        }
        for (float x = -dp(20); x < width; x += dp(34)) {
            canvas.drawLine(x, centerY, x + dp(18), centerY, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(13 * scale);
        paint.setColor(Color.rgb(204, 221, 216));
        canvas.drawText("N", width - dp(38), dp(44), paint);
    }

    private void drawRiskZone(Canvas canvas, int width, int height) {
        if (assessment == null || assessment.nearestZone == null) return;

        float centerX = width * 0.52f;
        float centerY = height * 0.48f;
        int color = colorForLevel(assessment.level);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, 58));
        canvas.drawCircle(centerX, centerY, dp(78), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(withAlpha(color, 190));
        canvas.drawCircle(centerX, centerY, dp(78), paint);
    }

    private void drawUserArrow(Canvas canvas, int width, int height) {
        float x = width * 0.52f;
        float y = height * 0.72f;
        float rotation = location != null && location.hasBearing() ? location.getBearing() : 0f;

        canvas.save();
        canvas.rotate(rotation, x, y);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(41, 168, 255), 75));
        canvas.drawCircle(x, y, dp(42), paint);

        path.reset();
        path.moveTo(x, y - dp(34));
        path.lineTo(x - dp(18), y + dp(26));
        path.lineTo(x, y + dp(14));
        path.lineTo(x + dp(18), y + dp(26));
        path.close();

        paint.setColor(Color.rgb(44, 180, 255));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(Color.WHITE);
        canvas.drawPath(path, paint);
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(12));
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        canvas.drawText("YOU", x - dp(13), y + dp(56), paint);
        paint.setFakeBoldText(false);
    }

    private void drawCooperativeThreat(Canvas canvas, int width, int height) {
        if (cooperativeAlert == null) return;

        if (cooperativeAlert.nearbyVehicles != null) {
            for (NearbyVehicle vehicle : cooperativeAlert.nearbyVehicles) {
                drawNearbyVehicle(canvas, width, height, vehicle, cooperativeAlert.present && cooperativeAlert.otherVehicleId.equals(vehicle.vehicleId));
            }
        }

        if (!cooperativeAlert.present) return;

        float centerX = width * 0.52f;
        float centerY = height * 0.72f;

        if (cooperativeAlert.hasOtherLocation) {
            float[] point = relativePoint(cooperativeAlert.otherLatitude, cooperativeAlert.otherLongitude, width, height);
            if (point != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(6));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.rgb(255, 77, 77));
                canvas.drawLine(point[0], point[1], centerX, centerY, paint);
                return;
            }
        }

        centerY = height * 0.48f;
        float vehicleX = centerX + dp(120);
        float vehicleY = centerY;

        if ("left".equals(cooperativeAlert.direction)) vehicleX = centerX - dp(120);
        if ("front".equals(cooperativeAlert.direction)) {
            vehicleX = centerX;
            vehicleY = centerY - dp(115);
        }
        if ("behind".equals(cooperativeAlert.direction)) {
            vehicleX = centerX;
            vehicleY = centerY + dp(115);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.rgb(255, 77, 77), 74));
        canvas.drawCircle(vehicleX, vehicleY, dp(52), paint);

        paint.setColor(Color.rgb(255, 77, 77));
        canvas.drawRoundRect(new RectF(vehicleX - dp(26), vehicleY - dp(15), vehicleX + dp(26), vehicleY + dp(15)), dp(8), dp(8), paint);
        paint.setColor(Color.rgb(40, 6, 6));
        paint.setTextSize(dp(12));
        paint.setFakeBoldText(true);
        canvas.drawText("APP", vehicleX - dp(13), vehicleY + dp(4), paint);
        paint.setFakeBoldText(false);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(7));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.rgb(255, 77, 77));
        canvas.drawLine(vehicleX, vehicleY, centerX, centerY, paint);
    }

    private void drawNearbyVehicle(Canvas canvas, int width, int height, NearbyVehicle vehicle, boolean danger) {
        float[] point = relativePoint(vehicle.latitude, vehicle.longitude, width, height);
        if (point == null) return;

        float x = point[0];
        float y = point[1];
        float rotation = vehicle.hasHeading ? (float) vehicle.headingDeg - (location != null && location.hasBearing() ? location.getBearing() : 0f) : 0f;
        int color = danger ? Color.rgb(255, 77, 77) : Color.rgb(255, 204, 77);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, danger ? 85 : 55));
        canvas.drawCircle(x, y, dp(danger ? 34 : 28), paint);

        canvas.save();
        canvas.rotate(rotation, x, y);
        path.reset();
        path.moveTo(x, y - dp(22));
        path.lineTo(x - dp(12), y + dp(18));
        path.lineTo(x, y + dp(10));
        path.lineTo(x + dp(12), y + dp(18));
        path.close();
        paint.setColor(color);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        canvas.drawPath(path, paint);
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(10));
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        String label = vehicle.speedKmh + " km/h";
        canvas.drawText(label, x - dp(18), y + dp(36), paint);
        paint.setFakeBoldText(false);
    }

    private void drawStatus(Canvas canvas, int width, int height) {
        String level = assessment == null ? "LOW" : assessment.level;
        int color = cooperativeAlert != null && cooperativeAlert.present
                ? colorForLevel(cooperativeAlert.level)
                : colorForLevel(level);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.BLACK, 130));
        canvas.drawRoundRect(new RectF(dp(14), dp(14), width - dp(14), dp(78)), dp(12), dp(12), paint);

        paint.setColor(color);
        paint.setTextSize(dp(22));
        paint.setFakeBoldText(true);
        canvas.drawText(level, dp(28), dp(43), paint);

        paint.setTextSize(dp(13));
        paint.setColor(Color.rgb(220, 238, 233));
        String subtitle = monitoring ? "Live navigation safety active" : "Monitoring stopped";
        canvas.drawText(subtitle, dp(28), dp(65), paint);

        if (location != null) {
            paint.setTextSize(dp(12));
            paint.setFakeBoldText(false);
            String gps = String.format(Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude());
            canvas.drawText(gps, dp(20), height - dp(18), paint);
        }
        paint.setFakeBoldText(false);
    }

    private int colorForLevel(String level) {
        if ("CRITICAL".equals(level)) return Color.rgb(255, 77, 77);
        if ("HIGH".equals(level)) return Color.rgb(255, 145, 77);
        if ("MEDIUM".equals(level)) return Color.rgb(255, 204, 77);
        return Color.rgb(90, 211, 157);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float[] relativePoint(double latitude, double longitude, int width, int height) {
        if (location == null) return null;

        float[] result = new float[2];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                latitude,
                longitude,
                result
        );

        float radarMeters = 250f;
        float maxRadius = Math.min(width, height) * 0.38f;
        float clamped = Math.min(result[0], radarMeters);
        double angle = Math.toRadians(result[1] - (location.hasBearing() ? location.getBearing() : 0));
        float x = width * 0.52f + (float) Math.sin(angle) * (clamped / radarMeters) * maxRadius;
        float y = height * 0.72f - (float) Math.cos(angle) * (clamped / radarMeters) * maxRadius;
        return new float[]{x, y};
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
