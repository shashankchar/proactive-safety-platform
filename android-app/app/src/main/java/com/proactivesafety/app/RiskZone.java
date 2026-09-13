package com.proactivesafety.app;

final class RiskZone {
    final String id;
    final String name;
    final double latitude;
    final double longitude;
    final String zoneRisk;
    final String roadType;
    final int accidentCount;
    final int recommendedSpeedKmh;
    final String visibilityNote;

    RiskZone(
            String id,
            String name,
            double latitude,
            double longitude,
            String zoneRisk,
            String roadType,
            int accidentCount,
            int recommendedSpeedKmh,
            String visibilityNote
    ) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.zoneRisk = zoneRisk;
        this.roadType = roadType;
        this.accidentCount = accidentCount;
        this.recommendedSpeedKmh = recommendedSpeedKmh;
        this.visibilityNote = visibilityNote;
    }
}
