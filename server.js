const http = require("http");
const fs = require("fs");
const path = require("path");
const { URL } = require("url");
const {
  normalizeVehicle,
  findAlertsForVehicle,
  removeStaleVehicles
} = require("./lib/cooperative-engine");

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || "0.0.0.0";
const PUBLIC_DIR = path.join(__dirname, "public");
const DATA_DIR = path.join(__dirname, "data");
const RISK_ZONES_FILE = path.join(DATA_DIR, "risk-zones.json");
const liveVehicles = new Map();

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
  ".png": "image/png",
  ".ico": "image/x-icon"
};

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store"
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1_000_000) {
        reject(new Error("Request body too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function readRiskZones() {
  const raw = fs.readFileSync(RISK_ZONES_FILE, "utf8");
  return JSON.parse(raw);
}

function writeRiskZones(zones) {
  fs.writeFileSync(RISK_ZONES_FILE, `${JSON.stringify(zones, null, 2)}\n`, "utf8");
}

function serveStatic(req, res, pathname) {
  const safePath = pathname === "/" ? "/index.html" : pathname;
  const filePath = path.normalize(path.join(PUBLIC_DIR, safePath));

  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.readFile(filePath, (error, data) => {
    if (error) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("Not found");
      return;
    }

    const extension = path.extname(filePath);
    res.writeHead(200, {
      "Content-Type": MIME_TYPES[extension] || "application/octet-stream",
      "Cache-Control": extension === ".html" ? "no-store" : "public, max-age=300"
    });
    res.end(data);
  });
}

async function handleApi(req, res, pathname) {
  if (pathname === "/api/health") {
    sendJson(res, 200, { ok: true, service: "proactive-safety-platform" });
    return true;
  }

  if (pathname === "/api/risk-zones" && req.method === "GET") {
    sendJson(res, 200, readRiskZones());
    return true;
  }

  if (pathname === "/api/risk-zones" && req.method === "POST") {
    try {
      const payload = JSON.parse(await readBody(req));
      const required = ["name", "latitude", "longitude", "riskLevel", "roadType"];
      const missing = required.filter((key) => payload[key] === undefined || payload[key] === "");

      if (missing.length) {
        sendJson(res, 400, { error: `Missing fields: ${missing.join(", ")}` });
        return true;
      }

      const zones = readRiskZones();
      const zone = {
        id: `RZ${String(Date.now()).slice(-6)}`,
        name: String(payload.name),
        latitude: Number(payload.latitude),
        longitude: Number(payload.longitude),
        riskLevel: String(payload.riskLevel).toUpperCase(),
        accidentCount: Number(payload.accidentCount || 0),
        junctionType: String(payload.junctionType || "Cross junction"),
        visibilityCondition: String(payload.visibilityCondition || "Unknown"),
        roadType: String(payload.roadType),
        recommendedSpeed: Number(payload.recommendedSpeed || 30),
        description: String(payload.description || ""),
        updatedAt: new Date().toISOString()
      };
      zones.push(zone);
      writeRiskZones(zones);
      sendJson(res, 201, zone);
      return true;
    } catch (error) {
      sendJson(res, 400, { error: "Invalid risk-zone payload" });
      return true;
    }
  }

  if (pathname === "/api/cooperative/location" && req.method === "POST") {
    try {
      const now = Date.now();
      const payload = JSON.parse(await readBody(req));
      const vehicle = normalizeVehicle(payload, now);
      liveVehicles.set(vehicle.vehicleId, vehicle);
      removeStaleVehicles(liveVehicles, now);

      const zones = readRiskZones();
      const vehicles = Array.from(liveVehicles.values());
      const alerts = findAlertsForVehicle(vehicle, vehicles, zones, now);
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

      sendJson(res, 200, {
        ok: true,
        vehicleId: vehicle.vehicleId,
        activeVehicles: vehicles.length,
        nearbyVehicles,
        alerts
      });
      return true;
    } catch (error) {
      sendJson(res, 400, { error: error.message || "Invalid cooperative safety payload" });
      return true;
    }
  }

  if (pathname === "/api/cooperative/vehicles" && req.method === "GET") {
    const now = Date.now();
    removeStaleVehicles(liveVehicles, now);
    sendJson(res, 200, {
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
    return true;
  }

  return false;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname.startsWith("/api/")) {
    const handled = await handleApi(req, res, url.pathname);
    if (!handled) sendJson(res, 404, { error: "API route not found" });
    return;
  }

  serveStatic(req, res, url.pathname);
});

server.listen(PORT, HOST, () => {
  console.log(`Proactive Safety running at http://localhost:${PORT}`);
  console.log(`LAN backend listening on http://0.0.0.0:${PORT}`);
});
