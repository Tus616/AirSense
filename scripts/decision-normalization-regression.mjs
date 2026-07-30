import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  buildSituationSummary,
  enumLabel,
  normalizeActionQueue,
  normalizeAreaIntelligence,
  normalizeAttribution,
  normalizeDecisionSnapshot,
  normalizeEnforcement,
  normalizeForecast,
  normalizeOperationalAlerts,
  normalizeOperationalHealth,
  normalizeRiskDecision,
  normalizeStationCatalogue,
} from "../src/services/decisionNormalization.js";

const forecast = {
  engine: "OPEN_METEO_PROVIDER_FORECAST",
  mode: "OPEN_METEO_PROVIDER_FORECAST",
  provider: "OPEN_METEO",
  forecastStandard: "US_AQI",
  fallbackUsed: false,
  forecast: {
    "24h": { predictedAqi: 60, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false, confidence: 0.72 },
    "48h": { predictedAqi: 68, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false, confidence: 0.72 },
    "72h": { predictedAqi: 78, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false, confidence: 0.72 },
  },
};

const decision = {
  currentAQI: 80,
  forecast,
  engineStatus: {
    degradedMode: false,
    providerForecastStatus: "ONLINE",
    forecastHorizonCount: 3,
    locallyPromotedModelHorizonCount: 0,
    persistenceFallbackHorizonCount: 0,
    optionalAiModelStatus: "DISABLED_FOR_CURRENT_DEPLOYMENT",
    failureReasons: {},
  },
  environmentalSignals: {
    aqiAvailable: true,
    stationName: "Gomti Nagar, Lucknow",
    provider: "CPCB_CAAQMS",
    aqiStandard: "INDIA_NAQI",
    currentAqi: 80,
    dominantPollutant: "PM2.5",
    observedAt: "2026-07-30T11:30:00Z",
  },
  geospatial: {
    layers: [{
      layerType: "AQI_HOTSPOTS",
      features: [
        { properties: { areaName: "Industrial Estate", aqi: 156, riskLevel: "HIGH", likelySource: "Industrial activity", recommendedAction: "Inspect emissions", agency: "Pollution Control Board", confidence: 0.6 } },
        { properties: { areaName: "Central Market", aqi: 91, riskLevel: "MODERATE", likelySource: "Traffic", recommendedAction: "Retune signals", agency: "Traffic Police", confidence: 0.5 } },
      ],
    }],
  },
  enforcement: {
    recommendations: [{
      actionType: "MONITORING",
      title: "Continue monitoring",
      actionLabel: "No immediate enforcement required",
      priorityLevel: "LOW",
      confidence: 0.72,
      agencyStatus: "NOT_REQUIRED",
      reason: "Current AQI remains below intervention thresholds, and the atmospheric forecast remains in the moderate range. Continue routine monitoring; no immediate enforcement action is required.",
      recommendedActions: ["Continue routine monitoring", "Review the next forecast update"],
      forecastAvailable: true,
    }],
  },
  attribution: {
    overallConfidence: 0.28,
    sources: [
      { sourceType: "UNKNOWN", displayName: "Unknown", contributionPercent: 50, confidence: 0.2, dataOrigin: "DERIVED_FROM_REAL_DATA", dataAvailability: "PARTIAL" },
      { sourceType: "SECONDARY_AEROSOL_OR_OTHER", displayName: "Other sources", contributionPercent: 34, confidence: 0.24, dataOrigin: "DERIVED_FROM_REAL_DATA", dataAvailability: "PARTIAL" },
      { sourceType: "TRAFFIC", displayName: "Traffic", contributionPercent: 16, confidence: 0.26, dataOrigin: "DERIVED_FROM_REAL_DATA", dataAvailability: "PARTIAL" },
    ],
  },
};

const normalizedForecast = normalizeForecast(forecast);
assert.equal(normalizedForecast.available, true);
assert.equal(normalizedForecast.horizonCount, 3);
assert.equal(normalizedForecast.engineLabel, "Atmospheric Forecast");
assert.equal(normalizedForecast.provider, "OPEN_METEO");
assert.equal(normalizedForecast.persistenceFallbackCount, 0);
assert.equal(normalizedForecast.validPoints.every((point) => point.engine === "OPEN_METEO_PROVIDER_FORECAST"), true);
assert.equal(normalizedForecast.validPoints.every((point) => point.provider === "OPEN_METEO"), true);
assert.equal(normalizedForecast.validPoints.every((point) => point.aqiStandard === "US_AQI"), true);

const mumbaiForecast = {
  engine: "PERSISTENCE_FALLBACK",
  mode: "PERSISTENCE_FALLBACK",
  currentProvider: "CPCB_CAAQMS",
  stationName: "Bandra Kurla Complex, Mumbai - MPCB",
  forecast: {
    "24h": { predictedAqi: 63, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false },
    "48h": { predictedAqi: 60, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false },
    "72h": { predictedAqi: 59, engine: "OPEN_METEO_PROVIDER_FORECAST", provider: "OPEN_METEO", aqiStandard: "US_AQI", fallbackReason: "CHRONOS_DISABLED", fallbackUsed: false },
  },
};
const normalizedMumbaiForecast = normalizeForecast(mumbaiForecast);
assert.equal(normalizedMumbaiForecast.providerForecast, true);
assert.equal(normalizedMumbaiForecast.engineLabel, "Atmospheric Forecast");
assert.equal(normalizedMumbaiForecast.standard, "US_AQI");
assert.equal(normalizedMumbaiForecast.persistenceFallbackCount, 0);
assert.deepEqual(normalizedMumbaiForecast.validPoints.map((point) => point.predictedAqi), [63, 60, 59]);
assert.equal(readFileSync(new URL("../src/pages/DecisionDashboard.jsx", import.meta.url), "utf8").includes("Station forecast only"), false);

const health = normalizeOperationalHealth(decision, { frames: [{}, {}, {}, {}, {}] });
assert.equal(health.degraded, false);
assert.equal(health.status, "Operational");
assert.equal(health.forecastHorizonCount, 3);
assert.equal(health.timelineFrameCount, 5);
assert.equal(health.optionalAiModel, "Disabled for current deployment");

const risk = normalizeRiskDecision(decision);
assert.equal(risk.available, true);
assert.notEqual(risk.riskLevel, "Evidence not available");
assert.equal(risk.peakForecastAqi, 78);

const enforcement = normalizeEnforcement(decision);
assert.equal(enforcement.priority, "Low");
assert.equal(enforcement.confidenceText, "72%");
assert.equal(enforcement.agencyStatus, "Air Quality Command Center");
assert.equal(enforcement.actionLabel, "No immediate enforcement required");
assert.equal(enforcement.forecastAvailable, true);
assert.equal(enforcement.reason.includes("forecast is unavailable"), false);

const attribution = normalizeAttribution(decision.attribution);
assert.equal(attribution.leadingSource.sourceLabel, "Traffic");
assert.equal(attribution.leadingSource.contributionText, "16%");
assert.equal(attribution.confidenceText, "28%");
assert.equal(attribution.totalContribution, 100);
assert.equal(attribution.sources.find((source) => source.sourceType === "UNKNOWN").sourceLabel, "Unknown / insufficiently explained");

const areas = normalizeAreaIntelligence(decision);
assert.equal(areas[0].area, "Industrial Estate");
assert.equal(areas[0].priority, "High");
assert.equal(areas[0].agency, "Pollution Control Board");
assert.equal(areas[1].area, "Central Market");

const actions = normalizeActionQueue(decision);
assert.equal(actions[0].title, "Continue monitoring");
assert.equal(actions[0].agency, "Air Quality Command Center");
assert.equal(actions[0].status, "Suggested");
assert.equal(/NO_ACTION_REQUIRED|NOT_REQUIRED/.test(JSON.stringify(actions)), false);

const alerts = normalizeOperationalAlerts(decision);
assert.ok(alerts.some((item) => item.type === "Current AQI"));
assert.ok(alerts.some((item) => item.type === "Forecast"));
assert.equal(alerts.some((item) => /fallback|chronos|model/i.test(`${item.title} ${item.reason} ${item.recommendedAction}`)), false);
assert.equal(alerts.every((item) => ["Air Quality Command Center", "Pollution Control Board", "Traffic Police"].includes(item.agency)), true);
assert.equal(alerts.every((item) => ["New", "Acknowledged", "Assigned", "In progress", "Resolved", "Expired"].includes(item.status)), true);

const situation = buildSituationSummary(decision);
assert.ok(situation.what.includes("AQI 80"));
assert.equal(situation.where, "Industrial Estate");
assert.ok(situation.forecast.includes("peak forecast AQI 78"));
assert.equal(/OPEN_METEO|CHRONOS|PERSISTENCE|fallback/i.test(Object.values(situation).join(" ")), false);
assert.ok(situation.why.includes("leading explained source"));
assert.ok(situation.why.includes("Overall attribution confidence"));
assert.ok(situation.why.includes("remains unexplained"));

const snapshot = normalizeDecisionSnapshot(decision, { cityName: "Lucknow", latitude: 26.84, longitude: 80.94 }, { frames: [] });
assert.deepEqual(snapshot.forecasts, normalizedForecast.points);
assert.deepEqual(snapshot.forecasts, normalizeForecast(snapshot.forecastResult).points);
assert.equal(snapshot.forecast.providerForecast, true);
assert.equal(snapshot.currentAqi, 80);
assert.equal(snapshot.coordinates.latitude, 26.84);
assert.equal(snapshot.station?.name, "Gomti Nagar, Lucknow");

const stations = normalizeStationCatalogue(decision);
assert.equal(stations.length, 1);
assert.equal(stations[0].name, "Gomti Nagar, Lucknow");
assert.equal(JSON.stringify(stations).includes("Replay"), false);

const dashboardSource = readFileSync(new URL("../src/pages/DecisionDashboard.jsx", import.meta.url), "utf8");
const mapSource = readFileSync(new URL("../src/components/decision/GisDecisionMap.jsx", import.meta.url), "utf8");
const enforcementActionsSource = readFileSync(new URL("../src/components/decision/EnforcementActions.jsx", import.meta.url), "utf8");
assert.equal(dashboardSource.includes("Collector and Model Status"), false);
assert.equal(dashboardSource.includes("Station forecast only"), false);
assert.equal(dashboardSource.includes("Verified replay station"), false);
assert.equal(dashboardSource.includes("Archive dependent"), false);
assert.equal(dashboardSource.includes("Replay catalogue"), false);
assert.equal(dashboardSource.includes("No agency escalation required"), false);
assert.equal(dashboardSource.includes("No timeline frames returned"), false);
assert.equal(dashboardSource.includes("required.Continue"), false);
assert.equal(enforcementActionsSource.includes("No agency escalation required"), false);
assert.ok(dashboardSource.includes("Historical forecast replay is not available because verified archived forecast and actual-observation pairs have not been connected."));
assert.ok(dashboardSource.includes("const points = context.activeSnapshot?.forecasts || []"));
assert.ok(mapSource.includes("decision-feature-panel"));
assert.ok(mapSource.includes("System & Data Diagnostics"));
assert.equal(mapSource.includes("Snapshot: {toDisplayText(snapshot?.snapshotId"), true);

const labels = [
  enumLabel("NO_ACTION_REQUIRED"),
  enumLabel("DERIVED_FROM_REAL_DATA"),
  enumLabel("OPEN_METEO_PROVIDER_FORECAST"),
  enumLabel("CHRONOS_DISABLED"),
  enumLabel("PARTIAL"),
  enumLabel("NOT_REQUIRED"),
];
assert.deepEqual(labels, [
  "No immediate enforcement required",
  "Derived from live evidence",
  "Atmospheric Forecast",
  "Optional AI model not used",
  "Limited evidence",
  "Air Quality Command Center",
]);

console.log("decision normalization regression passed");
