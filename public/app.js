const state = {
  journeyActive: false,
  tick: 0,
  zones: [],
  scenario: {
    userSpeedKmh: 35,
    rightSpeedKmh: 80,
    leftSpeedKmh: 45,
    night: true,
    poorVisibility: true
  }
};

const elements = {
  canvas: document.querySelector("#junctionCanvas"),
  riskScore: document.querySelector("#riskScore"),
  closingSpeed: document.querySelector("#closingSpeed"),
  timeToConflict: document.querySelector("#timeToConflict"),
  nextZone: document.querySelector("#nextZone"),
  recommendedAction: document.querySelector("#recommendedAction"),
  actionBox: document.querySelector("#actionBox"),
  factorList: document.querySelector("#factorList"),
  railRiskLevel: document.querySelector("#railRiskLevel"),
  railRiskMeter: document.querySelector("#railRiskMeter"),
  railRiskReason: document.querySelector("#railRiskReason"),
  warningOverlay: document.querySelector("#warningOverlay"),
  warningLevel: document.querySelector("#warningLevel"),
  warningTitle: document.querySelector("#warningTitle"),
  warningText: document.querySelector("#warningText"),
  journeyToggle: document.querySelector("#journeyToggle"),
  userSpeed: document.querySelector("#userSpeed"),
  rightSpeed: document.querySelector("#rightSpeed"),
  leftSpeed: document.querySelector("#leftSpeed"),
  userSpeedOutput: document.querySelector("#userSpeedOutput"),
  rightSpeedOutput: document.querySelector("#rightSpeedOutput"),
  leftSpeedOutput: document.querySelector("#leftSpeedOutput"),
  nightMode: document.querySelector("#nightMode"),
  poorVisibility: document.querySelector("#poorVisibility"),
  zoneList: document.querySelector("#zoneList"),
  zoneForm: document.querySelector("#zoneForm"),
  sosButton: document.querySelector("#sosButton"),
  sosStatus: document.querySelector("#sosStatus")
};

const ctx = elements.canvas.getContext("2d");

function resizeCanvas() {
  const rect = elements.canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  elements.canvas.width = Math.round(rect.width * ratio);
  elements.canvas.height = Math.round(rect.height * ratio);
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
}

function selectedZone() {
  return state.zones[0] || {
    name: "Highway Cross Junction A",
    riskLevel: "HIGH",
    accidentCount: 12,
    roadType: "Four-road cross junction",
    recommendedSpeed: 30
  };
}

function vehicleDistance(base, speed, phaseOffset) {
  if (!state.journeyActive || speed <= 0) return base;
  const cycle = 220;
  const movement = (state.tick * Math.max(speed, 8) * 0.018 + phaseOffset) % cycle;
  return Math.max(18, base - movement);
}

function getRiskInput() {
  const zone = selectedZone();
  const rightDistance = vehicleDistance(150, state.scenario.rightSpeedKmh, 0);
  const leftDistance = vehicleDistance(210, state.scenario.leftSpeedKmh, 80);

  return {
    roadType: zone.roadType,
    zoneRisk: zone.riskLevel,
    visibility: state.scenario.poorVisibility ? "poor" : "moderate",
    timeOfDay: state.scenario.night ? "night" : "day",
    userSpeedKmh: state.journeyActive ? state.scenario.userSpeedKmh : 0,
    accidentCount: zone.accidentCount,
    approachingVehicles: [
      {
        id: "right-car",
        direction: "right",
        speedKmh: state.scenario.rightSpeedKmh,
        distanceMeters: rightDistance
      },
      {
        id: "left-car",
        direction: "left",
        speedKmh: state.scenario.leftSpeedKmh,
        distanceMeters: leftDistance
      }
    ]
  };
}

function levelClass(level) {
  return `level-${level}`;
}

function updateControls() {
  elements.userSpeedOutput.value = `${state.scenario.userSpeedKmh} km/h`;
  elements.rightSpeedOutput.value = `${state.scenario.rightSpeedKmh} km/h`;
  elements.leftSpeedOutput.value = `${state.scenario.leftSpeedKmh} km/h`;
}

function renderRisk(result) {
  const urgent = result.mostUrgent;
  elements.riskScore.textContent = result.score;
  elements.railRiskMeter.value = result.score;
  elements.railRiskLevel.textContent = result.level;
  elements.railRiskLevel.className = levelClass(result.level);
  elements.railRiskReason.textContent = result.factors[0]?.label || "Monitoring";
  elements.closingSpeed.textContent = urgent ? `${Math.round(urgent.closingSpeedKmh)} km/h` : "0 km/h";
  elements.timeToConflict.textContent = urgent ? `${urgent.ttc.toFixed(1)} sec` : "--";
  elements.nextZone.textContent = selectedZone().name;
  elements.recommendedAction.textContent = state.journeyActive ? result.action : "Start journey to monitor risk.";

  elements.factorList.innerHTML = result.factors
    .sort((a, b) => b.points - a.points)
    .map(
      (factor) => `
        <div class="factor">
          <strong><span>${factor.label}</span><span>+${factor.points}</span></strong>
          <small>${factor.evidence}</small>
        </div>
      `
    )
    .join("");

  const shouldWarn = state.journeyActive && (result.level === "HIGH" || result.level === "CRITICAL");
  elements.warningOverlay.classList.toggle("hidden", !shouldWarn);
  elements.warningLevel.textContent = `${result.level} RISK`;
  elements.warningTitle.textContent =
    result.level === "CRITICAL" ? "Unsafe crossing gap detected" : "High-risk junction ahead";
  elements.warningText.textContent = result.action;
}

function drawRoad(width, height, result) {
  const cx = width / 2;
  const cy = height / 2;
  const road = Math.min(width, height) * 0.26;

  ctx.fillStyle = state.scenario.night ? "#06100d" : "#14352c";
  ctx.fillRect(0, 0, width, height);

  ctx.fillStyle = "#202827";
  ctx.fillRect(cx - road / 2, 0, road, height);
  ctx.fillRect(0, cy - road / 2, width, road);

  ctx.strokeStyle = "#f4d35e";
  ctx.lineWidth = 4;
  ctx.setLineDash([22, 20]);
  ctx.beginPath();
  ctx.moveTo(cx, 0);
  ctx.lineTo(cx, height);
  ctx.moveTo(0, cy);
  ctx.lineTo(width, cy);
  ctx.stroke();
  ctx.setLineDash([]);

  ctx.fillStyle = result.level === "CRITICAL" ? "rgba(255,77,77,0.28)" : "rgba(255,204,77,0.22)";
  ctx.beginPath();
  ctx.arc(cx, cy, road * 0.64, 0, Math.PI * 2);
  ctx.fill();

  ctx.strokeStyle = "#e8fff8";
  ctx.lineWidth = 3;
  ctx.strokeRect(cx - road / 2, cy - road / 2, road, road);

  ctx.fillStyle = "#a7c6ba";
  ctx.font = "14px system-ui";
  ctx.fillText("4-road conflict zone", cx - 68, cy - road / 2 - 14);
}

function drawVehicle(x, y, width, height, color, label) {
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.roundRect(x - width / 2, y - height / 2, width, height, 8);
  ctx.fill();
  ctx.fillStyle = "#07130f";
  ctx.font = "bold 13px system-ui";
  ctx.textAlign = "center";
  ctx.fillText(label, x, y + 4);
  ctx.textAlign = "start";
}

function drawScene(result) {
  const rect = elements.canvas.getBoundingClientRect();
  const width = rect.width;
  const height = rect.height;
  const cx = width / 2;
  const cy = height / 2;
  const zone = Math.min(width, height) * 0.26;

  drawRoad(width, height, result);

  const progress = state.journeyActive ? Math.min(1, state.tick / 180) : 0;
  const userY = height - 80 - progress * Math.min(170, height * 0.25);
  drawVehicle(cx - zone * 0.22, userY, 42, 62, "#29c7a4", "YOU");

  const rightDistance = result.vehicleFindings.find((vehicle) => vehicle.direction === "right")?.distanceMeters || 150;
  const leftDistance = result.vehicleFindings.find((vehicle) => vehicle.direction === "left")?.distanceMeters || 210;
  const rightX = cx + zone * 0.85 + rightDistance;
  const leftX = cx - zone * 0.85 - leftDistance;

  drawVehicle(rightX, cy - zone * 0.2, 66, 38, "#ff914d", `${state.scenario.rightSpeedKmh}`);
  drawVehicle(leftX, cy + zone * 0.22, 66, 38, "#ffcc4d", `${state.scenario.leftSpeedKmh}`);

  ctx.fillStyle = "#f2fff9";
  ctx.font = "bold 18px system-ui";
  ctx.fillText(`${result.level} ${result.score}/100`, 22, 34);

  ctx.fillStyle = "#a7c6ba";
  ctx.font = "14px system-ui";
  ctx.fillText(`Local risk engine active: ${state.journeyActive ? "yes" : "standby"}`, 22, 58);
}

function render() {
  state.tick += state.journeyActive ? 1 : 0;
  const result = RiskEngine.calculateRisk(getRiskInput());
  renderRisk(result);
  drawScene(result);
  requestAnimationFrame(render);
}

async function loadZones() {
  try {
    const response = await fetch("/api/risk-zones");
    state.zones = await response.json();
  } catch (error) {
    state.zones = [];
  }
  renderZones();
}

function renderZones() {
  elements.zoneList.innerHTML = state.zones
    .map(
      (zone) => `
        <article class="zone-item">
          <strong>${zone.name} <span class="${levelClass(zone.riskLevel)}">${zone.riskLevel}</span></strong>
          <span>${zone.roadType} | ${zone.visibilityCondition} | ${zone.recommendedSpeed} km/h recommended</span>
          <span>${zone.description}</span>
        </article>
      `
    )
    .join("");
}

function bindEvents() {
  document.querySelectorAll(".tab-button").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll(".tab-button").forEach((item) => item.classList.remove("active"));
      document.querySelectorAll(".view").forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      document.querySelector(`#${button.dataset.view}View`).classList.add("active");
      resizeCanvas();
    });
  });

  elements.journeyToggle.addEventListener("click", () => {
    state.journeyActive = !state.journeyActive;
    state.tick = 0;
    elements.journeyToggle.textContent = state.journeyActive ? "Stop Journey" : "Start Journey";
  });

  [
    ["userSpeed", "userSpeedKmh"],
    ["rightSpeed", "rightSpeedKmh"],
    ["leftSpeed", "leftSpeedKmh"]
  ].forEach(([elementKey, stateKey]) => {
    elements[elementKey].addEventListener("input", () => {
      state.scenario[stateKey] = Number(elements[elementKey].value);
      updateControls();
    });
  });

  elements.nightMode.addEventListener("change", () => {
    state.scenario.night = elements.nightMode.checked;
  });

  elements.poorVisibility.addEventListener("change", () => {
    state.scenario.poorVisibility = elements.poorVisibility.checked;
  });

  elements.sosButton.addEventListener("click", () => {
    elements.sosStatus.textContent = "Emergency alert prepared. Location sharing requires user permission.";
  });

  document.querySelectorAll("[data-sos-action]").forEach((button) => {
    button.addEventListener("click", () => {
      const action = button.dataset.sosAction;
      elements.sosStatus.textContent =
        action === "call"
          ? "Emergency call action selected."
          : action === "location"
            ? "Location sharing action selected. GPS permission will be requested in native app."
            : "Emergency contact alert selected.";
    });
  });

  elements.zoneForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formData = new FormData(elements.zoneForm);
    const payload = Object.fromEntries(formData.entries());

    try {
      const response = await fetch("/api/risk-zones", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      if (!response.ok) throw new Error("Could not save risk zone");
      const zone = await response.json();
      state.zones.unshift(zone);
      renderZones();
      elements.zoneForm.reset();
    } catch (error) {
      alert("Risk zone could not be saved.");
    }
  });

  window.addEventListener("resize", resizeCanvas);
}

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

resizeCanvas();
bindEvents();
updateControls();
loadZones();
requestAnimationFrame(render);
