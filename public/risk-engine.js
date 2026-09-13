(function attachRiskEngine(global) {
  const LEVELS = [
    { name: "LOW", min: 0, max: 30 },
    { name: "MEDIUM", min: 31, max: 60 },
    { name: "HIGH", min: 61, max: 80 },
    { name: "CRITICAL", min: 81, max: 100 }
  ];

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function kmhToMps(speed) {
    return speed / 3.6;
  }

  function timeToConflictSeconds(distanceMeters, userSpeedKmh, otherSpeedKmh) {
    const closingSpeed = kmhToMps(Math.max(0, userSpeedKmh) + Math.max(0, otherSpeedKmh));
    if (closingSpeed <= 0) return Infinity;
    return distanceMeters / closingSpeed;
  }

  function levelFromScore(score) {
    const normalized = clamp(Math.round(score), 0, 100);
    return LEVELS.find((level) => normalized >= level.min && normalized <= level.max).name;
  }

  function scoreFactor(label, points, evidence) {
    return { label, points: clamp(points, 0, 100), evidence };
  }

  function calculateRisk(input) {
    const scenario = {
      roadType: "Four-road cross junction",
      zoneRisk: "HIGH",
      visibility: "poor",
      timeOfDay: "night",
      userSpeedKmh: 35,
      approachingVehicles: [],
      distanceToConflictMeters: 80,
      accidentCount: 0,
      ...input
    };

    const factors = [];
    const zoneRiskPoints = { LOW: 6, MEDIUM: 14, HIGH: 24, CRITICAL: 30 }[scenario.zoneRisk] || 10;
    factors.push(scoreFactor("Known risk zone", zoneRiskPoints, scenario.zoneRisk));

    if (/junction|crossing|intersection/i.test(scenario.roadType)) {
      factors.push(scoreFactor("Cross-junction conflict points", 18, scenario.roadType));
    }

    if (scenario.timeOfDay === "night") {
      factors.push(scoreFactor("Night travel", 9, "Reduced speed and distance judgement"));
    }

    if (scenario.visibility === "poor") {
      factors.push(scoreFactor("Poor visibility", 14, "Headlights and low lighting reduce judgement"));
    } else if (scenario.visibility === "moderate") {
      factors.push(scoreFactor("Moderate visibility", 7, "Visibility is partially limited"));
    }

    if (scenario.userSpeedKmh > 50) {
      factors.push(scoreFactor("High user speed", 14, `${scenario.userSpeedKmh} km/h`));
    } else if (scenario.userSpeedKmh > 30) {
      factors.push(scoreFactor("Elevated user speed", 8, `${scenario.userSpeedKmh} km/h`));
    }

    if (scenario.accidentCount >= 10) {
      factors.push(scoreFactor("Accident history", 10, `${scenario.accidentCount} recorded incidents`));
    } else if (scenario.accidentCount >= 4) {
      factors.push(scoreFactor("Accident history", 5, `${scenario.accidentCount} recorded incidents`));
    }

    const vehicleFindings = scenario.approachingVehicles.map((vehicle) => {
      const ttc = timeToConflictSeconds(
        Number(vehicle.distanceMeters || scenario.distanceToConflictMeters),
        scenario.userSpeedKmh,
        Number(vehicle.speedKmh || 0)
      );
      const closingSpeedKmh = scenario.userSpeedKmh + Number(vehicle.speedKmh || 0);
      return { ...vehicle, ttc, closingSpeedKmh };
    });

    const mostUrgent = vehicleFindings.reduce((best, current) => {
      if (!best || current.ttc < best.ttc) return current;
      return best;
    }, null);

    if (mostUrgent) {
      const ttc = mostUrgent.ttc;
      let points = 8;
      if (ttc <= 4) points = 30;
      else if (ttc <= 7) points = 24;
      else if (ttc <= 10) points = 16;

      factors.push(
        scoreFactor(
          "Unsafe crossing gap",
          points,
          `${mostUrgent.direction} vehicle, ${Math.round(mostUrgent.closingSpeedKmh)} km/h closing speed, ${ttc.toFixed(1)}s to conflict`
        )
      );

      if (mostUrgent.speedKmh >= 80) {
        factors.push(scoreFactor("Fast approaching vehicle", 12, `${mostUrgent.speedKmh} km/h from ${mostUrgent.direction}`));
      }
    }

    const score = clamp(factors.reduce((sum, factor) => sum + factor.points, 0), 0, 100);
    const level = levelFromScore(score);
    const action =
      level === "CRITICAL"
        ? "STOP. Do not enter the junction."
        : level === "HIGH"
          ? "Reduce speed and prepare to stop."
          : level === "MEDIUM"
            ? "Slow down and check all directions."
            : "Continue with normal caution.";

    return {
      score: Math.round(score),
      level,
      action,
      factors,
      mostUrgent,
      vehicleFindings
    };
  }

  const api = { calculateRisk, timeToConflictSeconds, levelFromScore, kmhToMps };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    global.RiskEngine = api;
  }
})(typeof window !== "undefined" ? window : globalThis);
