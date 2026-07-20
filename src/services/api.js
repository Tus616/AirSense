import apiClient from './apiClient';

// Auth
export const loginApi = async (email, password) => {
  const response = await apiClient.post('/auth/login', { email, password });
  return response.data;
};

export const logoutApi = async () => {
  const response = await apiClient.post('/auth/logout');
  return response.data;
};

export const getMeApi = async () => {
  const response = await apiClient.get('/auth/me');
  return response.data;
};

// Public/Citizen AQI
export const getCitySummary = async (cityId) => {
  const response = await apiClient.get('/aqi/city-summary', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getNeighborhoods = async (cityId) => {
  const response = await apiClient.get('/aqi/neighborhoods', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getForecast = async (wardId, gridId) => {
  let url = '/aqi/forecast?';
  if (wardId) url += `wardId=${wardId}&`;
  if (gridId) url += `gridId=${gridId}&`;
  const response = await apiClient.get(url);
  return response.data;
};

// New: Get live air quality for a specific lat/lon using the new backend endpoint
export const getAirQuality = async (lat, lon) => {
  const response = await apiClient.get(`/air-quality?lat=${lat}&lon=${lon}`);
  return response.data;
};

export const getAdvisory = async (wardId) => {
  const url = wardId ? `/aqi/advisory?wardId=${wardId}` : '/aqi/advisory';
  const response = await apiClient.get(url);
  return response.data;
};

export const askAssistant = async (question, wardId, isVulnerable) => {
  const response = await apiClient.post('/citizen/assistant/ask', {
    question,
    wardId,
    isVulnerable
  });
  return response.data;
};

// Citizen (System 5)
export const getCitizenProfile = async () => {
  const response = await apiClient.get('/citizen/profile');
  return response.data;
};

export const updateCitizenProfile = async (profile) => {
  const response = await apiClient.put('/citizen/profile', profile);
  return response.data;
};

export const getCitizenRisk = async () => {
  const response = await apiClient.get('/citizen/risk');
  return response.data;
};

export const getCitizenNotifications = async () => {
  const response = await apiClient.get('/citizen/notifications');
  return response.data;
};

// Gov/Admin
export const getStations = async (cityId) => {
  const response = await apiClient.get('/gov/stations', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getGovAdvisories = async (cityId) => {
  const response = await apiClient.get('/gov/advisories', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getTrends = async (cityId) => {
  const response = await apiClient.get('/gov/trends', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getReliability = async (cityId) => {
  const response = await apiClient.get('/gov/reliability', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getSourceAttribution = async (cityId) => {
  const response = await apiClient.get('/gov/source-attribution', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getHeatmap = async (cityId) => {
  const response = await apiClient.get('/gov/map/heatmap', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getPolluters = async (cityId) => {
  const response = await apiClient.get('/gov/map/polluters', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getPlume = async (cityId) => {
  const response = await apiClient.get('/gov/map/plume', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getPolicySimulations = async () => {
  const response = await apiClient.get('/gov/policy-simulations');
  return response.data;
};

export const runPolicySimulation = async (scenario, wardId) => {
  const response = await apiClient.post('/admin/policy-simulations', {
    scenario,
    wardId
  });
  return response.data;
};

export const getSystemMetrics = async () => {
  const response = await apiClient.get('/admin/metrics');
  return response.data;
};

export const getGridForecast = async (cityId) => {
  const response = await apiClient.get('/gov/forecast/grid', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getGridCells = async (cityId) => {
  const response = await apiClient.get('/gov/grid-cells', { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const generateGridCells = async (cityId) => {
  const response = await apiClient.post('/admin/grid/generate', null, { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const runGridForecast = async (cityId) => {
  const response = await apiClient.post('/admin/forecast/grid/run', null, { params: cityId ? { cityId } : undefined });
  return response.data;
};

export const getForecastMetrics = async () => {
  const response = await apiClient.get('/gov/forecast/metrics');
  return response.data;
};

// Enforcement (System 3)
export const getEnforcementRecommendations = async (status, cityId) => {
  const params = new URLSearchParams();
  if (cityId) params.set('cityId', cityId);
  if (status) params.set('status', status);
  const url = `/gov/enforcement/recommendations?${params.toString()}`;
  const response = await apiClient.get(url);
  return response.data;
};

export const updateRecommendationStatus = async (id, status) => {
  const response = await apiClient.post(`/gov/enforcement/recommendations/${id}/status`, { status });
  return response.data;
};

export const getEnforcementMetrics = async (cityId) => {
  const url = cityId ? `/gov/enforcement/metrics?cityId=${cityId}` : '/gov/enforcement/metrics';
  const response = await apiClient.get(url);
  return response.data;
};

export const getAdvisoryCoverage = async () => {
  const response = await apiClient.get('/gov/advisory-coverage');
  return response.data;
};

export const getJudgeReadiness = async () => {
  const response = await apiClient.get('/gov/judge-readiness');
  return response.data;
};

// Attribution (System 7)
export const getAttributionLatest = async (wardId) => {
  const response = await apiClient.get(`/gov/attribution/latest?wardId=${wardId}`);
  return response.data;
};

export const runAttribution = async (wardId, from, to) => {
  const response = await apiClient.post(`/admin/attribution/run?wardId=${wardId}&from=${from}&to=${to}`);
  return response.data;
};

// Cities (System 4)
export const getCities = async () => {
  const response = await apiClient.get('/gov/cities');
  return response.data;
};

export const getComparedCities = async () => {
  const response = await apiClient.get('/gov/cities/compare');
  return response.data;
};

export const getCityMetrics = async (cityId) => {
  const response = await apiClient.get(`/gov/cities/${cityId}/metrics`);
  return response.data;
};

// Public Endpoints (System 5)
export const getWardDisplay = async (wardId, cityId, language) => {
  const url = `/public/ward-display/${wardId}?cityId=${cityId}&language=${language}`;
  const response = await apiClient.get(url);
  return response.data;
};

// Evaluation (System 6)
export const runEvaluation = async () => {
  const response = await apiClient.post('/admin/evaluation/run');
  return response.data;
};

export const getLatestEvaluation = async () => {
  const response = await apiClient.get('/admin/evaluation/latest');
  return response.data;
};
