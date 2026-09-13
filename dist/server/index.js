const STALE_AFTER_MS = 60_000;
const liveVehicles = new Map();

const riskZones = [
  {
    id: "RZ001",
    name: "Highway Cross Junction A",
    latitude: 10.0154,
    longitude: 76.3419,
    riskLevel: "HIGH",
    accidentCount: 12,
    junctionType: "Four-road cross junction",
    visibilityCondition: "Poor night visibility",
    roadType: "Highway crossing",
    recommendedSpeed: 30,
    description: "Unsignalized highway junction with poor lighting and fast cross traffic."
  },
  {
    id: "RZ002",
    name: "Market Road Blind Turn",
    latitude: 10.0188,
    longitude: 76.3491,
    riskLevel: "MEDIUM",
    accidentCount: 5,
    junctionType: "Sharp turn",
    visibilityCondition: "Blocked view",
    roadType: "Urban arterial",
    recommendedSpeed: 25,
    description: "Crowded turn with parked vehicles blocking rider visibility."
  }
];

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload, null, 2), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-allow-headers": "content-type"
    }
  });
}

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
  const latMeters = (target.latitude - origin.latitude) * 111_320;
  const lngMeters =
    (target.longitude - origin.longitude) *
    111_320 *
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

function closestApproachAlert(self, other) {
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
  if (tClosest < 0 || tClosest > 20) return null;

  const closest = {
    x: relativePosition.x + relativeVelocity.x * tClosest,
    y: relativePosition.y + relativeVelocity.y * tClosest
  };
  const missDistanceMeters = Math.hypot(closest.x, closest.y);
  const currentDistanceMeters = Math.hypot(relativePosition.x, relativePosition.y);
  if (currentDistanceMeters > 900 || missDistanceMeters > 45) return null;

  const closingSpeedKmh = self.speedKmh + other.speedKmh;
  const score = Math.min(100, Math.round(100 - missDistanceMeters + Math.max(0, 20 - tClosest) * 2));
  const level = tClosest <= 6 || missDistanceMeters <= 22 ? "CRITICAL" : "HIGH";
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

function findAlertsForVehicle(self, vehicles, now = Date.now()) {
  const alerts = [];
  for (const other of vehicles) {
    if (other.vehicleId === self.vehicleId) continue;
    if (now - other.updatedAt > STALE_AFTER_MS) continue;
    const pathAlert = closestApproachAlert(self, other);
    if (pathAlert) alerts.push(pathAlert);
  }
  return alerts.sort((a, b) => b.score - a.score);
}

function removeStaleVehicles(now = Date.now()) {
  for (const [id, vehicle] of liveVehicles.entries()) {
    if (now - vehicle.updatedAt > STALE_AFTER_MS) liveVehicles.delete(id);
  }
}

async function handleRequest(request) {
  const url = new URL(request.url);
  if (request.method === "OPTIONS") return json({ ok: true });

  if (url.pathname === "/" || url.pathname === "/api/health") {
    return json({ ok: true, service: "proactive-safety-platform" });
  }

  if (url.pathname === "/api/risk-zones" && request.method === "GET") {
    return json(riskZones);
  }

  if (url.pathname === "/api/cooperative/location" && request.method === "POST") {
    try {
      const now = Date.now();
      const vehicle = normalizeVehicle(await request.json(), now);
      liveVehicles.set(vehicle.vehicleId, vehicle);
      removeStaleVehicles(now);

      const vehicles = Array.from(liveVehicles.values());
      const nearbyVehicles = vehicles
        .filter((item) => item.vehicleId !== vehicle.vehicleId)
        .map((item) => ({
          vehicleId: item.vehicleId,
          latitude: item.latitude,
          longitude: item.longitude,
          speedKmh: item.speedKmh,
          headingDeg: item.headingDeg,
          ageMs: now - item.updatedAt
        }));

      return json({
        ok: true,
        vehicleId: vehicle.vehicleId,
        activeVehicles: vehicles.length,
        nearbyVehicles,
        alerts: findAlertsForVehicle(vehicle, vehicles, now)
      });
    } catch (error) {
      return json({ error: error.message || "Invalid cooperative safety payload" }, 400);
    }
  }

  if (url.pathname === "/api/cooperative/vehicles" && request.method === "GET") {
    const now = Date.now();
    removeStaleVehicles(now);
    return json({
      activeVehicles: liveVehicles.size,
      vehicles: Array.from(liveVehicles.values()).map((vehicle) => ({
        vehicleId: vehicle.vehicleId,
        latitude: vehicle.latitude,
        longitude: vehicle.longitude,
        speedKmh: vehicle.speedKmh,
        headingDeg: vehicle.headingDeg,
        ageMs: now - vehicle.updatedAt
      }))
    });
  }

  return json({ error: "Not found" }, 404);
}

export default {
  fetch: handleRequest
};
