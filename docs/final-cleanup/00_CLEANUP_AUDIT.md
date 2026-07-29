# Final Cleanup Audit

Date: 2026-07-29

## Required production code

- `backend/src/main/java/com/airsense/api/forecast/ForecastOrchestrator.java`: authoritative Spring forecast orchestration.
- `backend/src/main/java/com/airsense/api/forecast/MlForecastClient.java`: authoritative Spring client for `POST /internal/forecast/predict`.
- `ai-service/main.py`: FastAPI service exposing `/health`, `/ready`, `/internal/forecast/status`, and `/internal/forecast/predict`.
- `ai-service/forecasting/inference/open_meteo_client.py`: Open-Meteo provider forecast integration.
- `src/components/decision/*` and `src/pages/DecisionDashboard.jsx`: production decision dashboard.
- `backend/src/main/java/com/airsense/api/config/CorsConfig.java`: required CORS filter, now environment based.
- `.vercelignore`: required Vercel source filter.

## Required development/test code

- `backend/src/test/**`: Spring regression tests.
- `ai-service/forecasting/tests/**`: FastAPI forecast and training regression tests.
- `package.json`, `vite.config.js`, `backend/Dockerfile`, `ai-service/Dockerfile`.

## Legacy but intentionally retained

- `backend/src/main/java/com/airsense/api/ingestion/ForecastOrchestrator.java`
- `backend/src/main/java/com/airsense/api/services/ForecastClient.java`
- `backend/src/main/java/com/airsense/api/forecast/ForecastModel.java`

These are retained only behind `LEGACY_FORECAST_ENABLED=false` by default. They no longer participate in production startup or scheduled forecasting unless explicitly enabled.

## Duplicate implementation

- Legacy Spring `/predict` and `/predict-batch` forecast path: isolated behind `legacy.forecast.enabled`.
- FastAPI legacy `/predict`, `/predict-batch`, `/train`, and feature-importance routes: retained for backward compatibility/offline development, not used by production forecast orchestration.

## Temporary/debug artifact

- Root manual scripts `test_advisory.py`, `test_forecast.py`, `test_phase7.py`, `test_resilience.py`, `test_lucknow.py`: removed. They contained hardcoded demo credentials and old endpoint assumptions.
- `docs/ml-forecast-fix/`: untracked temporary docs merged into `docs/production` and removed from the working tree.
- Root `.log`, `.chrome-*`, `.tmp-chrome-*`, Python cache, and service log artifacts: removed where Windows file permissions allowed it.
- `.pytest_cache` directories: ignored and untracked; Windows denied deletion during this pass.

## Generated file

- `dist/`, `node_modules/`, `backend/target/`, Python caches, virtual environments, and model artifacts are ignored and not committed.

## Local-only file

- `.env` and `.env.*` are ignored except `.env.example`.
- `ai-service/venv/` is local-only and ignored.

## Obsolete documentation

- `docs/pretrained-forecast-migration/`: removed because it described Chronos-loaded verification as current production behavior.

## Unsafe or unused configuration

- Hardcoded demo admin credentials in root manual scripts were removed.
- Legacy batch forecasting is disabled by default via `LEGACY_FORECAST_ENABLED=false`.
- Chronos is disabled by default via `CHRONOS_ENABLED=false`.
