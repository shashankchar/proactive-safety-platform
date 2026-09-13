package com.proactivesafety.app;

import android.location.Location;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class RiskEngine {
    private static final List<RiskZone> ZONES = Arrays.asList(
            new RiskZone(
                    "RZ001",
                    "Highway Cross Junction A",
                    10.0154,
                    76.3419,
                    "HIGH",
                    "Four-road highway crossing",
                    12,
                    30,
                    "Poor night visibility"
            ),
            new RiskZone(
                    "RZ002",
                    "Market Road Blind Turn",
                    10.0188,
                    76.3491,
                    "MEDIUM",
                    "Urban blind turn",
                    5,
                    25,
                    "Blocked side view"
            )
    );

    private RiskEngine() {
    }

    static SafetyAssessment assess(Location location) {
        SafetyAssessment assessment = new SafetyAssessment();
        assessment.nearestZone = nearestZone(location);
        assessment.distanceMeters = distanceMeters(location, assessment.nearestZone);

        int score = 0;
        if (assessment.nearestZone != null) {
            RiskZone zone = assessment.nearestZone;

            if (assessment.distanceMeters <= 120) {
                score += 35;
                assessment.factors.add("Inside immediate danger radius");
            } else if (assessment.distanceMeters <= 300) {
                score += 25;
                assessment.factors.add("Approaching known risk zone");
            } else if (assessment.distanceMeters <= 700) {
                score += 12;
                assessment.factors.add("Risk zone ahead");
            }

            if ("CRITICAL".equals(zone.zoneRisk)) {
                score += 28;
                assessment.factors.add("Zone marked CRITICAL");
            } else if ("HIGH".equals(zone.zoneRisk)) {
                score += 22;
                assessment.factors.add("Zone marked HIGH");
            } else if ("MEDIUM".equals(zone.zoneRisk)) {
                score += 12;
                assessment.factors.add("Zone marked MEDIUM");
            }

            if (zone.roadType.toLowerCase(Locale.US).contains("cross")) {
                score += 14;
                assessment.factors.add("Cross-junction conflict points");
            }

            int speedKmh = speedKmh(location);
            if (speedKmh > zone.recommendedSpeedKmh + 20) {
                score += 18;
                assessment.factors.add("Speed is far above recommended limit");
            } else if (speedKmh > zone.recommendedSpeedKmh) {
                score += 10;
                assessment.factors.add("Speed is above recommended limit");
            }

            if (zone.accidentCount >= 10) {
                score += 10;
                assessment.factors.add("High accident history");
            } else if (zone.accidentCount >= 4) {
                score += 5;
                assessment.factors.add("Recorded accident history");
            }
        }

        if (isNight()) {
            score += 10;
            assessment.factors.add("Night travel reduces speed judgement");
        }

        assessment.score = Math.min(100, score);
        assessment.level = level(assessment.score);
        assessment.action = action(assessment.level);
        return assessment;
    }

    static int speedKmh(Location location) {
        if (location == null || !location.hasSpeed()) return 0;
        return Math.max(0, Math.round(location.getSpeed() * 3.6f));
    }

    static List<RiskZone> zones() {
        return ZONES;
    }

    private static RiskZone nearestZone(Location location) {
        if (location == null) return null;
        RiskZone best = null;
        double bestDistance = Double.MAX_VALUE;

        for (RiskZone zone : ZONES) {
            double distance = distanceMeters(location, zone);
            if (distance < bestDistance) {
                best = zone;
                bestDistance = distance;
            }
        }

        return best;
    }

    private static double distanceMeters(Location location, RiskZone zone) {
        if (location == null || zone == null) return Double.NaN;

        float[] result = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                zone.latitude,
                zone.longitude,
                result
        );
        return result[0];
    }

    private static boolean isNight() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 18 || hour < 6;
    }

    private static String level(int score) {
        if (score >= 81) return "CRITICAL";
        if (score >= 61) return "HIGH";
        if (score >= 31) return "MEDIUM";
        return "LOW";
    }

    private static String action(String level) {
        if ("CRITICAL".equals(level)) return "STOP. Do not enter the junction.";
        if ("HIGH".equals(level)) return "Reduce speed and prepare to stop.";
        if ("MEDIUM".equals(level)) return "Slow down and check all directions.";
        return "Continue with normal caution.";
    }
}
