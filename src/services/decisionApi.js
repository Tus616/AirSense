import apiClient from "./apiClient";

function cityParams(city = null) {
  if (typeof city === "string") return city ? { cityId: city } : {};
  if (!city || typeof city !== "object") return {};
  const latitude = city.latitude ?? city.lat;
  const longitude = city.longitude ?? city.lng ?? city.lon;
  return {
    cityId: city.cityId || city.id || undefined,
    placeId: city.placeId || undefined,
    cityName: city.cityName || city.name || undefined,
    state: city.state || undefined,
    country: city.country || undefined,
    latitude: Number.isFinite(Number(latitude)) ? Number(latitude) : undefined,
    longitude: Number.isFinite(Number(longitude)) ? Number(longitude) : undefined,
  };
}

export const getDecisionIntelligence = async (city = null, options = {}) => {
  const response = await apiClient.get("/intelligence/decision", {
    params: cityParams(city),
    signal: options.signal,
  });
  return response.data;
};

export const getGeoSpatialIntelligence = async (city = null, options = {}) => {
  const response = await apiClient.get("/intelligence/geospatial", {
    params: cityParams(city),
    signal: options.signal,
  });
  return response.data;
};

export const getExplainability = async (city = null, options = {}) => {
  const response = await apiClient.get("/intelligence/explainability", {
    params: cityParams(city),
    signal: options.signal,
  });
  return response.data;
};

export const getTemporalTimeline = async (city = null, options = {}) => {
  const response = await apiClient.get("/intelligence/timeline", {
    params: cityParams(city),
    signal: options.signal,
  });
  return response.data;
};

export const searchPlaces = async (query, options = {}) => {
  const response = await apiClient.get("/places/search", {
    params: { q: query },
    signal: options.signal,
  });
  return response.data;
};

export const queryDecisionCopilot = async ({
  cityId,
  city,
  question,
  timelineFrame,
  conversationId,
  snapshotId,
  signal,
}) => {
  const params = cityParams(city || cityId);
  const response = await apiClient.post("/intelligence/copilot/query", {
    ...params,
    question,
    timelineFrame,
    conversationId,
    snapshotId,
  }, {
    signal,
  });
  return response.data;
};

export const getHistoricalReplayStations = async (options = {}) => {
  const response = await apiClient.get("/air-quality/forecast/historical-replay/stations", {
    signal: options.signal,
  });
  return response.data;
};

export const runHistoricalReplay = async ({ stationKey, forecastIssueTime }, options = {}) => {
  const response = await apiClient.post("/air-quality/forecast/historical-replay", {
    stationKey,
    forecastIssueTime,
  }, {
    signal: options.signal,
  });
  return response.data;
};
