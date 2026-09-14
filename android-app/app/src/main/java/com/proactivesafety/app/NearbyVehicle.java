package com.proactivesafety.app;

final class NearbyVehicle {
    final String vehicleId;
    final double latitude;
    final double longitude;
    final int speedKmh;
    final double headingDeg;
    final long ageMs;
    final boolean hasHeading;

    NearbyVehicle(
            String vehicleId,
            double latitude,
            double longitude,
            int speedKmh,
            double headingDeg,
            long ageMs,
            boolean hasHeading
    ) {
        this.vehicleId = vehicleId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKmh = speedKmh;
        this.headingDeg = headingDeg;
        this.ageMs = ageMs;
        this.hasHeading = hasHeading;
    }
}
