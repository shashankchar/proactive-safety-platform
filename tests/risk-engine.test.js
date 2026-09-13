const assert = require("assert");
const RiskEngine = require("../public/risk-engine");

const ttc = RiskEngine.timeToConflictSeconds(300, 70, 100);
assert(ttc > 6.2 && ttc < 6.4, `Expected about 6.3s, got ${ttc}`);

const highwayNightRisk = RiskEngine.calculateRisk({
  roadType: "Four-road cross junction",
  zoneRisk: "HIGH",
  visibility: "poor",
  timeOfDay: "night",
  userSpeedKmh: 70,
  accidentCount: 12,
  approachingVehicles: [
    {
      direction: "opposite",
      speedKmh: 100,
      distanceMeters: 300
    }
  ]
});

assert.strictEqual(highwayNightRisk.level, "CRITICAL");
assert(highwayNightRisk.score >= 81, `Expected critical score, got ${highwayNightRisk.score}`);
assert(/STOP|Reduce/.test(highwayNightRisk.action), `Expected clear action, got ${highwayNightRisk.action}`);

const normalRisk = RiskEngine.calculateRisk({
  roadType: "Local road",
  zoneRisk: "LOW",
  visibility: "good",
  timeOfDay: "day",
  userSpeedKmh: 20,
  accidentCount: 0,
  approachingVehicles: []
});

assert(["LOW", "MEDIUM"].includes(normalRisk.level), `Expected low or medium level, got ${normalRisk.level}`);

console.log("Risk engine tests passed");
