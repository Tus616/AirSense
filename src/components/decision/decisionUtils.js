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
