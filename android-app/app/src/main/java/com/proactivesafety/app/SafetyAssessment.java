package com.proactivesafety.app;

import java.util.ArrayList;
import java.util.List;

final class SafetyAssessment {
    int score;
    String level;
    String action;
    RiskZone nearestZone;
    double distanceMeters;
    CooperativeAlert cooperativeAlert;
    final List<String> factors = new ArrayList<>();

    boolean shouldWarn() {
        return "HIGH".equals(level) || "CRITICAL".equals(level);
    }
}
