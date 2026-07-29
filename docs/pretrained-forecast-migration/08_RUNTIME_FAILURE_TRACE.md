Runtime dashboard path:

Frontend `src/pages/DecisionDashboard.jsx`
-> `src/services/decisionApi.js` calls `GET /api/v1/intelligence/decision`
-> `IntelligenceDecisionController`
-> `DecisionIntelligenceService.decide`
-> `ForecastOrchestrator.forecast`
-> `MlForecastClient.postForEntity(.../internal/forecast/predict)`
-> FastAPI `ai-service/main.py`.

Root failure found:

Spring selected the current AQI standard from the primary station feed. When CPCB won the current-AQI selection, the backend sent `forecastStandard=INDIA_NAQI` to FastAPI. The deployed AI service is configured with `CHRONOS_ENABLED=false` and uses Open-Meteo provider forecasts as `US_AQI`. Because the standards did not match, the AI service could not use `OPEN_METEO_PROVIDER_FORECAST` for that request and the backend displayed persistence.

Second failure:

When mapping AI predictions back into `ForecastPoint`, Spring overwrote the AI-provided forecast standard with the current station standard and did not expose a provider field to the frontend.

Fixed behavior:

For coordinate-level provider forecasting, Spring now sends `forecastStandard=US_AQI`, `aqiStandard=US_AQI`, `provider=OPEN_METEO`, horizons `[24,48,72]`, coordinates, and no incompatible CPCB history/current AQI. FastAPI provider predictions with `fallbackReason=CHRONOS_DISABLED` remain valid `OPEN_METEO_PROVIDER_FORECAST` predictions. The frontend counts fallback horizons only for actual persistence/unavailable engines.
