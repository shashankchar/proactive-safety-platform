const EARTH_METERS_PER_DEGREE_LAT = 111_320;
const STALE_AFTER_MS = 20_000;
const MAX_CONFLICT_SECONDS = 30;
const CRITICAL_CONFLICT_SECONDS = 8;
const HIGH_CONFLICT_SECONDS = 15;
const MAX_MISS_DISTANCE_METERS = 60;
const NEARBY_COMPARE_RADIUS_METERS = 1_200;

function toRadians(degrees) {
  return (degrees * Math.PI) / 180;
}

function kmhToMps(speedKmh) {
  return Math.max(0, Number(speedKmh || 0)) / 3.6;
}

function normalizeVehicle(input, now = Date.now()) {
  const vehicleId = String(input.vehicleId || "").trim();
  if (!vehicleId) throw new Error("vehicleId is required");

  const latitude = Number(input.latitude);
  const longitude = Number(input.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    throw new Error("Valid latitude and longitude are required");
  }

  return {
    vehicleId,
    latitude,
    longitude,
    speedKmh: Math.max(0, Number(input.speedKmh || 0)),
    headingDeg: Number.isFinite(Number(input.headingDeg)) ? Number(input.headingDeg) : null,
    accuracyMeters: Math.max(0, Number(input.accuracyMeters || 0)),
    updatedAt: now
  };
}

function projectRelativeMeters(origin, target) {
  const latMeters = (target.latitude - origin.latitude) * EARTH_METERS_PER_DEGREE_LAT;
  const lngMeters =
    (target.longitude - origin.longitude) *
    EARTH_METERS_PER_DEGREE_LAT *
    Math.cos(toRadians((origin.latitude + target.latitude) / 2));

  return { x: lngMeters, y: latMeters };
}

function velocityMetersPerSecond(vehicle) {
  if (!Number.isFinite(vehicle.headingDeg)) return { x: 0, y: 0 };

  const speed = kmhToMps(vehicle.speedKmh);
  const radians = toRadians(vehicle.headingDeg);
  return {
    x: Math.sin(radians) * speed,
    y: Math.cos(radians) * speed
  };
}

function bearingFromTo(origin, target) {
  const lat1 = toRadians(origin.latitude);
  const lat2 = toRadians(target.latitude);
  const deltaLng = toRadians(target.longitude - origin.longitude);
  const y = Math.sin(deltaLng) * Math.cos(lat2);
  const x =
    Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng);
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}

function directionLabel(origin, target) {
  const bearing = bearingFromTo(origin, target);
  if (bearing >= 315 || bearing < 45) return "front";
  if (bearing >= 45 && bearing < 135) return "right";
  if (bearing >= 135 && bearing < 225) return "behind";
  return "left";
}

function headingDifferenceDegrees(a, b) {
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
  const diff = Math.abs(((a - b + 540) % 360) - 180);
  return diff;
}

function isSameDirection(self, other) {
  const difference = headingDifferenceDegrees(self.headingDeg, other.headingDeg);
  return difference !== null && difference < 45;
}

function distanceMetersBetween(self, other) {
  const relativePosition = projectRelativeMeters(self, other);
  return Math.hypot(relativePosition.x, relativePosition.y);
}

function closestApproachAlert(self, other) {
  if (isSameDirection(self, other)) return null;

  const relativePosition = projectRelativeMeters(self, other);
  const selfVelocity = velocityMetersPerSecond(self);
  const otherVelocity = velocityMetersPerSecond(other);
  const relativeVelocity = {
    x: otherVelocity.x - selfVelocity.x,
    y: otherVelocity.y - selfVelocity.y
  };

  const relativeSpeedSq =
    relativeVelocity.x * relativeVelocity.x + relativeVelocity.y * relativeVelocity.y;
  if (relativeSpeedSq < 0.01) return null;

  const tClosest =
    -(
      relativePosition.x * relativeVelocity.x +
      relativePosition.y * relativeVelocity.y
    ) / relativeSpeedSq;

  if (tClosest < 0 || tClosest > MAX_CONFLICT_SECONDS) return null;

  const closest = {
    x: relativePosition.x + relativeVelocity.x * tClosest,
    y: relativePosition.y + relativeVelocity.y * tClosest
  };
  const missDistanceMeters = Math.hypot(closest.x, closest.y);
  const currentDistanceMeters = Math.hypot(relativePosition.x, relativePosition.y);
  if (currentDistanceMeters > 900 || missDistanceMeters > MAX_MISS_DISTANCE_METERS) return null;

  const closingSpeedKmh = self.speedKmh + other.speedKmh;
  const score = Math.min(100, Math.round(100 - missDistanceMeters + Math.max(0, MAX_CONFLICT_SECONDS - tClosest) * 2));
  const level = tClosest <= CRITICAL_CONFLICT_SECONDS || missDistanceMeters <= 22 ? "CRITICAL" : "HIGH";
  const direction = directionLabel(self, other);

  return {
    type: "PATH_CONFLICT",
    level,
    score,
    otherVehicleId: other.vehicleId,
    direction,
    distanceMeters: Math.round(currentDistanceMeters),
    secondsToConflict: Number(tClosest.toFixed(1)),
    missDistanceMeters: Math.round(missDistanceMeters),
    closingSpeedKmh: Math.round(closingSpeedKmh),
    message:
      level === "CRITICAL"
        ? `CRITICAL: app user vehicle from ${direction}. Stop before entering conflict area.`
        : `HIGH RISK: app user vehicle from ${direction}. Reduce speed and prepare to stop.`
  };
}

function nearestZone(vehicle, zones) {
  let best = null;
  let bestDistance = Infinity;

  for (const zone of zones || []) {
    const distance = Math.hypot(
      ...Object.values(projectRelativeMeters(vehicle, {
        latitude: zone.latitude,
        longitude: zone.longitude
      }))
    );
    if (distance < bestDistance) {
      best = zone;
      bestDistance = distance;
    }
  }

  return best ? { zone: best, distanceMeters: bestDistance } : null;
}

function etaToZoneSeconds(vehicle, zone) {
  const speed = kmhToMps(vehicle.speedKmh);
  if (speed <= 1) return Infinity;
  const distance = Math.hypot(
    ...Object.values(projectRelativeMeters(vehicle, {
      latitude: zone.latitude,
      longitude: zone.longitude
    }))
  );
  return distance / speed;
}

function junctionConflictAlert(self, other, zones) {
  if (isSameDirection(self, other)) return null;

  const selfZone = nearestZone(self, zones);
  const otherZone = nearestZone(other, zones);
  if (!selfZone || !otherZone) return null;
  if (selfZone.zone.id !== otherZone.zone.id) return null;
  if (selfZone.distanceMeters > 350 || otherZone.distanceMeters > 350) return null;

  const selfEta = etaToZoneSeconds(self, selfZone.zone);
  const otherEta = etaToZoneSeconds(other, otherZone.zone);
  if (!Number.isFinite(selfEta) || !Number.isFinite(otherEta)) return null;

  const etaGap = Math.abs(selfEta - otherEta);
  if (etaGap > 10 || Math.min(selfEta, otherEta) > 30) return null;

  const direction = directionLabel(self, other);
  const level = etaGap <= 5 || Math.min(selfEta, otherEta) <= CRITICAL_CONFLICT_SECONDS ? "CRITICAL" : "HIGH";
  return {
    type: "JUNCTION_CONFLICT",
    level,
    score: level === "CRITICAL" ? 94 : 78,
    otherVehicleId: other.vehicleId,
    direction,
    zoneId: selfZone.zone.id,
    zoneName: selfZone.zone.name,
    secondsToConflict: Number(Math.min(selfEta, otherEta).toFixed(1)),
    etaGapSeconds: Number(etaGap.toFixed(1)),
    closingSpeedKmh: Math.round(self.speedKmh + other.speedKmh),
    message:
      level === "CRITICAL"
        ? `CRITICAL: another app user may reach ${selfZone.zone.name} at the same time. Stop and wait.`
        : `HIGH RISK: another app user is approaching ${selfZone.zone.name}. Slow down.`
  };
}

function findAlertsForVehicle(self, vehicles, zones, now = Date.now()) {
  const alerts = [];

  for (const other of vehicles) {
    if (other.vehicleId === self.vehicleId) continue;
    if (now - other.updatedAt > STALE_AFTER_MS) continue;
    if (distanceMetersBetween(self, other) > NEARBY_COMPARE_RADIUS_METERS) continue;

    const pathAlert = closestApproachAlert(self, other);
    const junctionAlert = junctionConflictAlert(self, other, zones);
    if (pathAlert) alerts.push(pathAlert);
    if (junctionAlert) alerts.push(junctionAlert);
  }

  return alerts.sort((a, b) => b.score - a.score);
}

function removeStaleVehicles(vehicleMap, now = Date.now()) {
  for (const [id, vehicle] of vehicleMap.entries()) {
    if (now - vehicle.updatedAt > STALE_AFTER_MS) {
      vehicleMap.delete(id);
    }
  }
}

module.exports = {
  STALE_AFTER_MS,
  normalizeVehicle,
  findAlertsForVehicle,
  removeStaleVehicles,
  closestApproachAlert,
  junctionConflictAlert,
  headingDifferenceDegrees,
  isSameDirection,
  distanceMetersBetween
};
