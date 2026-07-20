# 09 - Deployment Runbook

Last updated: 2026-07-18T17:37:59+05:30

## Versions

- Java 21+ for Spring Boot. Verified locally with Java 23.
- Node.js with npm for Vite frontend.
- Python 3.12 with `ai-service\venv`.
- MongoDB Atlas for runtime, or local MongoDB on `localhost:27017` for rollback/development.
- MongoDB Database Tools `mongodump`/`mongorestore` 100.16.1 and `mongosh` 2.9.2 for migration/verification.

## Configuration

- Do not commit secrets. Use `.env`, OS environment variables, or deployment secret stores.
- Backend: `SERVER_PORT`, `MONGODB_URI`, JWT secret settings, provider API keys, `FORECAST_EVALUATION_ENABLED`.
- Migration helper: `MONGODB_ATLAS_URI` may hold the Atlas connection string in local `.env`; do not commit or print it.
- Atlas database name must be `airsense`. Include `/airsense` in the URI or start Spring with `--spring.data.mongodb.database=airsense`.
- AI service: model artifact directory, API host/port, live-history thresholds.
- Frontend: `VITE_API_BASE_URL`. The municipal GIS view uses Leaflet with OpenStreetMap tiles and does not require a browser maps API key.

## Startup Order

1. Confirm Atlas connectivity or start local MongoDB for rollback/development.
2. Start AI inference: from `ai-service`, `.\venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8000`.
3. Start backend: from `backend`, set `MONGODB_URI` from the Atlas secret, then run `.\mvnw.cmd spring-boot:run` or `java -jar target\api-0.0.1-SNAPSHOT.jar --spring.data.mongodb.database=airsense`.
4. Start frontend: from repo root, `npm.cmd run dev -- --host localhost`.

## Build Commands

- Python tests: `.\venv\Scripts\python.exe -m pytest forecasting/tests -v`
- Backend tests: `.\mvnw.cmd test`
- Backend package: `.\mvnw.cmd -DskipTests package`
- Frontend build: `npm.cmd run build`

## MongoDB Indexes

- Ensure application startup runs `MongoAirQualityIndexInitializer`.
- Key collections: `aqi_historical_snapshots`, `aqi_forecast_runs`, `forecast_model_registry`, `forecast_retraining_requests`.

## Atlas Migration

1. Verify tools:
   - `mongodump --version`
   - `mongorestore --version`
   - `mongosh --version`
2. Create a fresh local dump without deleting local data:
   - `mongodump --uri="mongodb://127.0.0.1:27017/airsense" --db=airsense --out="D:\MongoBackup\atlas-migration-<timestamp>"`
3. Inspect Atlas first:
   - Use `mongosh` with `MONGODB_ATLAS_URI` and verify the target `airsense` database is empty or safe to replace.
4. Restore:
   - `mongorestore --uri="$env:MONGODB_ATLAS_URI" --db=airsense "D:\MongoBackup\atlas-migration-<timestamp>\airsense"`
   - Do not use `--drop` unless Atlas has been inspected and replacement is explicitly safe.
5. Verify:
   - Compare every local and Atlas collection count.
   - Verify `aqi_historical_snapshots` total rows, replay station counts, origin/standard fields, indexes, timestamp ranges, station keys, and duplicate groups.

Verified migration artifact:

- Dump path: `D:\MongoBackup\atlas-migration-20260718-170809`.
- Dump size: 76,749,095 bytes.
- Restore result: 136,474 documents restored successfully; 0 failures.
- Atlas collection counts matched local collection counts for all 22 collections.

## Artifacts And Rollback

- Keep model artifacts under the AI service model directory; never delete old promoted artifacts during deploy.
- Keep the local MongoDB `airsense` database untouched as rollback.
- Keep the migration dump at `D:\MongoBackup\atlas-migration-20260718-170809`.
- To rollback backend runtime, set `MONGODB_URI=mongodb://127.0.0.1:27017/airsense` and restart the backend.
- Rollback API: `POST /api/v1/admin/forecast-models/rollback` with ADMIN JWT and `aqiStandard`, `horizonHours`, `modelScope`, optional `targetVersion`.
- Rollback requires matching standard/scope/horizon, `rollbackEligible=true`, and checksum pass.

## Health Checks And Smoke Tests

- Backend auth: `POST /api/v1/auth/login`
- Forecast: `GET /api/v1/air-quality/forecast?cityName=Lucknow&latitude=26.8467&longitude=80.9462`
- Decision: `GET /api/v1/intelligence/decision?cityId=LUCKNOW&cityName=Lucknow&latitude=26.8467&longitude=80.9462`
- Drift: `GET /api/v1/debug/drift-status` with ADMIN JWT
- Retraining requests: `GET /api/v1/debug/retraining-requests` with JWT
- Historical replay stations: `GET /api/v1/air-quality/forecast/historical-replay/stations`
- Historical replay diagnostics: `GET /api/v1/air-quality/forecast/historical-replay/diagnostics`
- Historical replay run: `POST /api/v1/air-quality/forecast/historical-replay` with body `{"stationKey":"ito_delhi_cpcb","forecastIssueTime":"2025-05-26T00:30:00Z"}`. Replay is read-only and does not affect live forecast state.

## Historical Replay Data Contract

- Active local database: `airsense`.
- Archive collection: `aqi_historical_snapshots`.
- Replay rows must have `dataOrigin=HISTORICAL_TRAINING_ARCHIVE` and `aqiStandard=INDIA_NAQI`.
- Live inference lag features must not treat `HISTORICAL_TRAINING_ARCHIVE` as `LIVE_OPERATIONAL_HISTORY`.
- Missing future actuals are returned as null and must not be coerced to zero.

## Port Cleanup

- Inspect: `cmd /c netstat -ano | findstr :8082`
- Stop backend lock: `Stop-Process -Id <PID> -Force`

## Recovery

- If package fails on jar rename, stop the backend process holding the jar and rerun package.
- If AI inference is down, forecasts must show `ML_SERVICE_UNAVAILABLE` or fallback, not fake ML.
- If production ML is not validated for the live station/horizon, continue using the safe fallback response and visible fallback reason. Do not force promotion during deployment.
- Historical replay may return null actual AQI values when matching future observations are unavailable; nulls must remain null and must not be converted to zero.
- If local MongoDB exits with an FTDC diagnostics write failure under `data\diagnostic.data`, use an Administrator PowerShell and recover only the diagnostics folder:
  - Confirm `Get-Service MongoDB` is stopped and `Get-Process mongod` returns no stale process.
  - Rename `C:\Program Files\MongoDB\Server\8.0\data\diagnostic.data` to `diagnostic.data.backup-<timestamp>`.
  - Recreate `diagnostic.data`.
  - Grant `NT AUTHORITY\NetworkService` Modify permissions on `data`, `data\diagnostic.data`, and `log`.
  - Start `MongoDB` service and verify port `27017`.
  - Do not run `mongod --repair` unless normal FTDC-directory recovery fails and a database backup exists.
- Back up MongoDB with `mongodump`; restore with `mongorestore`.
- Logs are local process stdout/stderr files when started via scripts, or service manager logs in production.
