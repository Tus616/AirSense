import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  enumLabel,
  normalizeAttribution,
  normalizeEnforcement,
  normalizeForecast,
  normalizeOperationalHealth,
  normalizeRiskDecision,
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
  environmentalSignals: { aqiAvailable: true },
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
assert.equal(enforcement.agencyStatus, "No agency escalation required");
assert.equal(enforcement.actionLabel, "No immediate enforcement required");
assert.equal(enforcement.forecastAvailable, true);
assert.equal(enforcement.reason.includes("forecast is unavailable"), false);

const attribution = normalizeAttribution(decision.attribution);
assert.equal(attribution.leadingSource.sourceLabel, "Traffic");
assert.equal(attribution.leadingSource.contributionText, "16%");
assert.equal(attribution.confidenceText, "28%");
assert.equal(attribution.totalContribution, 100);
assert.equal(attribution.sources.find((source) => source.sourceType === "UNKNOWN").sourceLabel, "Unknown / insufficiently explained");

const labels = [
  enumLabel("NO_ACTION_REQUIRED"),
  enumLabel("DERIVED_FROM_REAL_DATA"),
  enumLabel("OPEN_METEO_PROVIDER_FORECAST"),
  enumLabel("CHRONOS_DISABLED"),
  enumLabel("PARTIAL"),
];
assert.deepEqual(labels, [
  "No immediate enforcement required",
  "Derived from live evidence",
  "Atmospheric Forecast",
  "Optional AI model not used",
  "Limited evidence",
]);

console.log("decision normalization regression passed");
