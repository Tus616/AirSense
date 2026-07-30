export const defaultCityMetadata = {
  cityId: "",
  cityName: "",
  displayName: "",
  placeId: "",
  latitude: null,
  longitude: null,
  state: "",
  country: "",
};

export const GIS_LAYERS = [
  { id: "hotspots", label: "AQI hotspots", layerTypes: ["AQI_HOTSPOTS"] },
  { id: "sources", label: "Pollution sources", layerTypes: ["POLLUTION_SOURCE_ZONES"] },
  { id: "traffic", label: "Traffic corridors", layerTypes: ["TRAFFIC_CORRIDORS"] },
  { id: "construction", label: "Construction sites", layerTypes: ["CONSTRUCTION_SITES"] },
  { id: "industrial", label: "Industrial zones", layerTypes: ["INDUSTRIAL_ZONES"] },
  { id: "sensitive", label: "Schools/Hospitals", layerTypes: ["SENSITIVE_ZONES"] },
  { id: "wind", label: "Wind direction", layerTypes: ["WIND_VECTOR_LAYER"] },
  { id: "forecast", label: "Forecast risk grid", layerTypes: ["FORECAST_GRID_24H", "FORECAST_GRID_48H", "FORECAST_GRID_72H"] },
  { id: "green", label: "Green cover", layerTypes: ["GREEN_COVER_LAYER"] },
  { id: "satellite", label: "Satellite evidence", layerTypes: ["SATELLITE_EVIDENCE_LAYER"] },
];

export function asArray(value) {
  return Array.isArray(value) ? value : [];
}

export function asNumber(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function toDisplayText(value, fallback = "Unavailable") {
  if (value === null || value === undefined || value === "") return fallback;
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) {
    const text = value.map((item) => toDisplayText(item, "")).filter(Boolean).join(", ");
    return text || fallback;
  }
  if (typeof value === "object") {
    const primary = value.summary || value.message || value.title || value.reason || value.name || value.status;
    const primaryText = primary ? toDisplayText(primary, "") : "";
    const reasons = Array.isArray(value.reasons) ? value.reasons.map((item) => toDisplayText(item, "")).filter(Boolean) : [];
    if (primaryText && reasons.length > 0) return `${primaryText} Reasons: ${reasons.join(", ")}`;
    if (primaryText) return primaryText;

    const compact = Object.entries(value)
      .slice(0, 4)
      .map(([key, item]) => `${labelize(key)}: ${toDisplayText(item, "")}`)
      .filter((item) => !item.endsWith(": "));
    return compact.length > 0 ? compact.join("; ") : fallback;
  }
  return fallback;
}

export function formatPercent(value) {
  if (value === null || value === undefined || value === "") return "Unavailable";
  return `${Math.round(asNumber(value) * 100)}%`;
}

export function formatScore(value) {
  if (value === null || value === undefined || value === "") return "Unavailable";
  return Math.round(asNumber(value));
}

export function labelize(value, fallback = "Unknown") {
  if (value === null || value === undefined || value === "") return fallback;
  return toDisplayText(value, fallback).replace(/_/g, " ");
}

export function forecastEngineLabel(value, fallback = "Forecast unavailable") {
  const key = String(value || "").trim().toUpperCase();
  const labels = {
    CHRONOS_BOLT_ZERO_SHOT: "Pretrained AI Forecast",
    OPEN_METEO_PROVIDER_FORECAST: "Atmospheric Forecast",
    PERSISTENCE_FALLBACK: "Persistence Fallback",
    PERSISTENCE: "Persistence Fallback",
    TREND_WEATHER_V1: "Trend + Weather",
    UNAVAILABLE: "Forecast Unavailable",
  };
  if (labels[key]) return labels[key];
  if (key.startsWith("ML_")) return "Validated ML Model";
  return labelize(value, fallback);
}

export function fallbackReasonLabel(reason, engine = "") {
  const labels = {
    MODEL_NOT_PROMOTED: "Model not promoted",
    ARTIFACT_UNAVAILABLE: "Model artifact unavailable",
    ML_SERVICE_UNAVAILABLE: "AI service unavailable",
    FEATURE_SCHEMA_MISMATCH: "Feature schema mismatch",
    INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY: "Insufficient contiguous live history",
    LIVE_HISTORY_STALE: "Live history is stale",
    LIVE_HISTORY_COVERAGE_LOW: "Live history coverage is low",
    LIVE_HISTORY_GAP_TOO_LARGE: "Live history gap is too large",
    CHECKSUM_MISMATCH: "Model checksum mismatch",
    FALLBACK_ENGINE_USED: "Fallback engine used",
    CHRONOS_LOAD_FAILED: "Pretrained model unavailable",
    CHRONOS_UNAVAILABLE: "Pretrained model unavailable",
    INSUFFICIENT_HISTORY_FOR_CHRONOS: "Insufficient history for pretrained model",
    PROVIDER_FORECAST_UNAVAILABLE: "Provider forecast unavailable",
    PROVIDER_FORECAST_STANDARD_MISMATCH: "Provider forecast uses a different AQI standard",
    CURRENT_AQI_UNAVAILABLE: "Current AQI unavailable",
  };
  const engineKey = String(engine || "").trim().toUpperCase();
  return String(reason || "")
    .split(";")
    .filter(Boolean)
    .map((part) => {
      const key = part.trim();
      if (key === "CHRONOS_DISABLED" && engineKey === "OPEN_METEO_PROVIDER_FORECAST") {
        return "Optional AI model not used";
      }
      if (key === "CHRONOS_DISABLED") return "Optional AI model not used";
      return labels[key] || labelize(key, "No fallback reason reported");
    })
    .join(" / ");
}

export function isProviderForecast(point, forecastResult = {}) {
  const engine = String(point?.engine || point?.mode || forecastResult?.engine || forecastResult?.mode || "").toUpperCase();
  return engine === "OPEN_METEO_PROVIDER_FORECAST";
}

export function isActualForecastFallback(point, forecastResult = {}) {
  if (isProviderForecast(point, forecastResult)) return false;
  const engine = String(point?.engine || point?.mode || forecastResult?.engine || forecastResult?.mode || "").toUpperCase();
  return engine === "PERSISTENCE" || engine === "PERSISTENCE_FALLBACK" || engine === "UNAVAILABLE" || Boolean(point?.fallbackUsed);
}

export function friendlyEnum(value, fallback = "Unknown") {
  const key = String(value || "").toUpperCase();
  const labels = {
    AVAILABLE: "Available",
    DERIVED: "Derived",
    FALLBACK: "Fallback",
    PARTIAL: "Limited evidence",
    UNAVAILABLE: "No reliable data",
    ERROR: "Error",
    OBSERVED_REAL_DATA: "Observed live data",
    DERIVED_FROM_REAL_DATA: "Derived from live evidence",
    OPEN_METEO_PROVIDER_FORECAST: "Atmospheric forecast",
    PERSISTENCE_FALLBACK: "Persistence fallback",
    RULE_BASED_INFERENCE: "Rule-based guidance",
    USER_CONTEXT: "User-context response",
  };
  return labels[key] || labelize(value, fallback);
}

export function moduleStatusLabel(status, fallback = "Status unavailable") {
  if (!status || typeof status !== "object") return fallback;
  const state = friendlyEnum(status.status, "");
  const origin = friendlyEnum(status.dataOrigin, "");
  if (state && origin) return `${state} - ${origin}`;
  return state || origin || fallback;
}

export function moduleStatusReason(status, fallback = "No status reason returned.") {
  return toDisplayText(status?.reason, fallback);
}

export function getCityOption(cityId) {
  return null;
}

export function makeCityId(cityName, placeId = "") {
  const fromName = String(cityName || "")
    .normalize("NFKD")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .replace(/[\s-]+/g, "_")
    .toUpperCase();
  if (fromName) return fromName;
  return String(placeId || "CITY").replace(/[^\w]/g, "_").toUpperCase();
}

export function centerFromCity(city) {
  const lat = Number(city?.latitude ?? city?.lat ?? city?.center?.lat);
  const lng = Number(city?.longitude ?? city?.lng ?? city?.lon ?? city?.center?.lng);
  if (Number.isFinite(lat) && Number.isFinite(lng)) return { lat, lng };
  return null;
}

export function getRiskTone(level) {
  const normalized = String(level || "").toUpperCase();
  if (["SEVERE", "VERY_HIGH", "HAZARDOUS"].includes(normalized)) return "critical";
  if (["HIGH", "VERY_POOR", "UNHEALTHY"].includes(normalized)) return "high";
  if (["MODERATE", "POOR", "LIMITED"].includes(normalized)) return "medium";
  return "low";
}

export function getAqiTone(aqi) {
  if (aqi === null || aqi === undefined || aqi === "") return "unknown";
  const value = asNumber(aqi);
  if (value > 300) return "critical";
  if (value > 200) return "high";
  if (value > 100) return "medium";
  return "low";
}

export function getForecastPoints(forecastResult) {
  if (!forecastResult) return [];
  if (Array.isArray(forecastResult?.forecasts) && forecastResult.forecasts.length > 0) {
    return forecastResult.forecasts.map((point) => ({
      key: `${point.horizonHours || ""}h`,
      ...point,
    }));
  }
  const forecast = forecastResult?.forecast || {};
  return ["24h", "48h", "72h"].map((key) => ({
    key,
    ...(forecast[key] || {}),
  }));
}
