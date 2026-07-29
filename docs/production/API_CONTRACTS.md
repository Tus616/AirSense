# API Contracts

## FastAPI Forecast

`POST /internal/forecast/predict`

Required behavior:

- Accepts `currentAqi: null` for provider-only Open-Meteo forecasts.
- Returns `forecastStandard`, `snapshotId`, `locationKey`, and per-horizon predictions.
- Per-horizon prediction includes `engine`, `predictedAqi`, bounds, confidence, provider, `fallbackReason`, and `dataOrigin`.

## Spring Decision

Decision responses include `sharedSnapshot`, `forecast`, `attribution`, `geospatialSummary`, `enforcement`, `advisories`, `explainabilitySummary`, and Copilot-compatible provenance.
