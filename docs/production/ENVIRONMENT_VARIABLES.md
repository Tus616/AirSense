# Environment Variables

Canonical forecast service chain:

- `AI_SERVICE_BASE_URL`: backend base URL for AI service.
- `ML_FORECAST_SERVICE_URL`: optional override for forecast client; defaults to `AI_SERVICE_BASE_URL`.

Important flags:

- `ML_FORECAST_ENABLED=true` in production.
- `CHRONOS_ENABLED=false` on low-memory Render unless enough memory is available.
- `OPEN_METEO_FORECAST_ENABLED=true`.
- `LEGACY_MODEL_STARTUP_ENABLED=false`.
- `LEGACY_FORECAST_ENABLED=false`.
- `CORS_ALLOWED_ORIGINS=https://air-sense-lyart.vercel.app,http://localhost:5173`.
