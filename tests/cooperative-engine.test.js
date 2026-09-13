const assert = require("assert");
const {
  normalizeVehicle,
  closestApproachAlert,
  junctionConflictAlert,
  findAlertsForVehicle
} = require("../lib/cooperative-engine");

const now = Date.now();

const bike = normalizeVehicle({
  vehicleId: "bike-1",
  latitude: 10.000000,
  longitude: 76.000000,
  speedKmh: 70,
  headingDeg: 0
}, now);

const car = normalizeVehicle({
  vehicleId: "car-1",
  latitude: 10.001000,
  longitude: 76.000000,
  speedKmh: 100,
  headingDeg: 180
}, now);

const pathAlert = closestApproachAlert(bike, car);
assert(pathAlert, "Expected opposite app user vehicle to trigger path conflict");
assert.strictEqual(pathAlert.level, "CRITICAL");
assert.strictEqual(pathAlert.closingSpeedKmh, 170);
assert(pathAlert.secondsToConflict > 2 && pathAlert.secondsToConflict < 3);

const zone = {
  id: "RZ001",
  name: "Highway Cross Junction A",
  latitude: 10.0005,
  longitude: 76.0005
};

const eastVehicle = normalizeVehicle({
  vehicleId: "east-vehicle",
  latitude: 10.0005,
  longitude: 76.0020,
  speedKmh: 60,
  headingDeg: 270
}, now);

const southVehicle = normalizeVehicle({
  vehicleId: "south-vehicle",
  latitude: 9.9990,
  longitude: 76.0005,
  speedKmh: 60,
  headingDeg: 0
}, now);

const junctionAlert = junctionConflictAlert(eastVehicle, southVehicle, [zone]);
assert(junctionAlert, "Expected two app users approaching same junction to trigger alert");
assert(["HIGH", "CRITICAL"].includes(junctionAlert.level));

const alerts = findAlertsForVehicle(eastVehicle, [eastVehicle, southVehicle], [zone], now);
assert(alerts.length >= 1, "Expected at least one cooperative alert");

console.log("Cooperative safety tests passed");
