package com.proactivesafety.app;

import java.util.Collections;
import java.util.List;

final class CooperativeAlert {
    final boolean present;
    final String level;
    final int score;
    final String message;
    final String otherVehicleId;
    final String direction;
    final int distanceMeters;
    final int closingSpeedKmh;
    final double secondsToConflict;
    final int activeVehicles;
    final double otherLatitude;
    final double otherLongitude;
    final boolean hasOtherLocation;
    final List<NearbyVehicle> nearbyVehicles;

    CooperativeAlert(
            boolean present,
            String level,
            int score,
            String message,
            String otherVehicleId,
            String direction,
            int distanceMeters,
            int closingSpeedKmh,
            double secondsToConflict,
            int activeVehicles,
            double otherLatitude,
            double otherLongitude,
            boolean hasOtherLocation,
            List<NearbyVehicle> nearbyVehicles
    ) {
        this.present = present;
        this.level = level;
        this.score = score;
        this.message = message;
        this.otherVehicleId = otherVehicleId;
        this.direction = direction;
        this.distanceMeters = distanceMeters;
        this.closingSpeedKmh = closingSpeedKmh;
        this.secondsToConflict = secondsToConflict;
        this.activeVehicles = activeVehicles;
        this.otherLatitude = otherLatitude;
        this.otherLongitude = otherLongitude;
        this.hasOtherLocation = hasOtherLocation;
        this.nearbyVehicles = nearbyVehicles == null ? Collections.emptyList() : nearbyVehicles;
    }

    static CooperativeAlert none(int activeVehicles) {
        return none(activeVehicles, Collections.emptyList());
    }

    static CooperativeAlert none(int activeVehicles, List<NearbyVehicle> nearbyVehicles) {
        return new CooperativeAlert(false, "LOW", 0, "No app-user conflict detected.", "", "", 0, 0, 0, activeVehicles, 0, 0, false, nearbyVehicles);
    }
}
