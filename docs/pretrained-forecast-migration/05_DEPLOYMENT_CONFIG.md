AI service variables:

- `CHRONOS_MODEL_ID=amazon/chronos-bolt-tiny`
- `CHRONOS_DEVICE=cpu`
- `CHRONOS_ENABLED=true`
- `CHRONOS_MIN_HISTORY_HOURS=48`
- `CHRONOS_CONTEXT_HOURS=168`
- `OPEN_METEO_FORECAST_ENABLED=true`

Backend variables:

- `AI_SERVICE_BASE_URL=<deployed-ai-service-url>`
- `ML_FORECAST_SERVICE_URL=<deployed-ai-service-url>`
- `MONGODB_URI=<secret>`

FastAPI start command: `uvicorn main:app --host 0.0.0.0 --port $PORT`.
