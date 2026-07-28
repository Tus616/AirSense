FastAPI entry point: `ai-service/main.py`.

Authoritative live endpoint: `POST /internal/forecast/predict`.

Previous live path depended on promoted local CPCB-trained artifacts through `forecasting/inference/predict.py`, Spring `ForecastOrchestrator`, `MlForecastClient`, and Mongo model registry entries. That made live forecasts unavailable when artifacts, registry entries, local paths, or CPCB history were missing.

Legacy training and artifact code remains present but is no longer the authoritative live forecasting path.
