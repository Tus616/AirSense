# Runtime Paths

## Production forecast path

Spring Boot decision/forecast controllers call:

1. `com.airsense.api.forecast.ForecastOrchestrator`
2. `com.airsense.api.forecast.MlForecastClient`
3. FastAPI `POST /internal/forecast/predict`
4. Per-horizon mapping and forecast persistence
5. Downstream attribution, geospatial, enforcement, advisory, explainability, Copilot, and frontend rendering

Engine priority is:

1. `CHRONOS_BOLT_ZERO_SHOT` when enabled and healthy
2. `OPEN_METEO_PROVIDER_FORECAST`
3. `PERSISTENCE_FALLBACK`
4. `UNAVAILABLE`

## Disabled legacy path

The old Spring batch path is retained but disabled by default:

- `com.airsense.api.ingestion.ForecastOrchestrator`
- `com.airsense.api.services.ForecastClient`
- `com.airsense.api.forecast.ForecastModel`

Set `LEGACY_FORECAST_ENABLED=true` only for legacy local maintenance. Production must leave it false.

## AI startup path

FastAPI does not load Chronos at startup. Chronos loads lazily only when `CHRONOS_ENABLED=true` and a forecast request has enough history.

Legacy model registry discovery/startup loading is disabled by default with `LEGACY_MODEL_STARTUP_ENABLED=false`.
