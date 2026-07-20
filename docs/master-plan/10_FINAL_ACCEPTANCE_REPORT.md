# 10 - Final Acceptance Report

Last updated: 2026-07-18T17:37:59+05:30

## Atlas Migration Result

- MongoDB Atlas authentication succeeded using `MONGODB_ATLAS_URI` from `.env`.
- Atlas `airsense` was inspected before restore and contained 0 collections.
- Restore used the existing dump at `D:\MongoBackup\atlas-migration-20260718-170809\airsense`.
- `mongorestore` completed successfully: 136,474 documents restored; 0 failed.
- `--drop` was not used.
- Local MongoDB was not deleted, overwritten, or stopped.

## Tooling

| Tool | Result |
|---|---|
| `mongodump` | 100.16.1 verified |
| `mongorestore` | 100.16.1 verified |
| `mongosh` | 2.9.2 verified |

## Local Dump

- Path: `D:\MongoBackup\atlas-migration-20260718-170809`.
- Size: 76,749,095 bytes.
- Contents: 22 BSON files and 22 metadata files.
- Important collection present: `aqi_historical_snapshots.bson`.

## Collection Count Comparison

| Collection | Local Count | Atlas Count | Match |
|---|---:|---:|---|
| `Advisories` | 2 | 2 | Yes |
| `aqi_forecast_runs` | 1,158 | 1,158 | Yes |
| `aqi_historical_snapshots` | 75,085 | 75,085 | Yes |
| `attribution_results` | 6 | 6 | Yes |
| `cities` | 3 | 3 | Yes |
| `CitizenRiskAdvisories` | 15,451 | 15,451 | Yes |
| `city_metrics_snapshots` | 135 | 135 | Yes |
| `construction_permits` | 5 | 5 | Yes |
| `enforcement_recommendations` | 10 | 10 | Yes |
| `EvaluationMetrics` | 5 | 5 | Yes |
| `forecast_model_registry` | 18 | 18 | Yes |
| `forecast_retraining_runs` | 1 | 1 | Yes |
| `HealthAssistantLogs` | 2 | 2 | Yes |
| `historical_weather_observations` | 43,800 | 43,800 | Yes |
| `industrial_sources` | 5 | 5 | Yes |
| `Predictions` | 22 | 22 | Yes |
| `sensor_data` | 259 | 259 | Yes |
| `SyntheticPollutionEvents` | 4 | 4 | Yes |
| `thermal_anomalies` | 4 | 4 | Yes |
| `tracked_air_quality_locations` | 494 | 494 | Yes |
| `users` | 2 | 2 | Yes |
| `VulnerabilityMapping` | 3 | 3 | Yes |

## Historical Archive Verification

Exact filter: `dataOrigin=HISTORICAL_TRAINING_ARCHIVE`, `aqiStandard=INDIA_NAQI`.

| Station | Local Count | Atlas Count | Range |
|---|---:|---:|---|
| Gomti Nagar, Lucknow | 21,544 | 21,544 | 2022-12-31T18:30:00Z to 2025-07-17T19:30:00Z |
| ITO, Delhi | 20,485 | 20,485 | 2022-12-31T18:30:00Z to 2025-07-17T19:30:00Z |
| BKC, Mumbai | 7,769 | 7,769 | 2023-12-31T18:30:00Z to 2025-07-17T19:30:00Z |

Additional checks:

- Full `aqi_historical_snapshots` count: 75,085 local and Atlas.
- Exact archive `INDIA_NAQI` total: 49,798 local and Atlas.
- Broader collection includes 25,287 non-archive-origin rows and 9 non-`INDIA_NAQI` rows, matching local data.
- Earliest full historical snapshot: 2022-12-31T18:30:00Z.
- Latest full historical snapshot: 2026-07-18T08:30:00Z.
- Duplicate identity group sample count: 0.
- Sample Atlas identity fields included `stationKey`, `locationKey`, `provider`, `dataOrigin`, `aqiStandard`, and `providerObservedAt`.

## Index Verification

Atlas restored and backend startup verified expected key indexes:

- `aqi_historical_snapshots`: `_id_`, `uq_aqi_snapshot_identity`, `idx_aqi_history_lookup`, `idx_aqi_history_ingested`, `idx_aqi_station_history_lookup`, `locationKey_1_provider_1_aqiStandard_1_timestamp_1`.
- `tracked_air_quality_locations`: `_id_`, `uq_tracked_location_key`, `idx_tracked_location_active`.
- `aqi_forecast_runs`: `_id_`, `idx_forecast_evaluation_lookup`, `idx_forecast_metrics_lookup`, `idx_forecast_identity`.

## Backend Configuration

- Existing backend configuration already uses `spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/airsense}`.
- No source-code configuration change was required.
- Atlas runtime set `MONGODB_URI` from `MONGODB_ATLAS_URI` in process environment.
- Runtime also set `spring.data.mongodb.database=airsense` because Spring could not infer a database name from the secret URI as provided.
- No Atlas URI or password was written to source, docs, or screenshots.

## Test And Build Results

| Area | Result |
|---|---|
| Python forecasting tests | 22 passed, 164 warnings |
| Backend tests | 129 passed, 0 failures, 0 errors, 0 skipped |
| Backend package | BUILD SUCCESS |
| Frontend build | Passed; Vite chunk-size warning only |

Backend tests were run against isolated local database `airsense_test_acceptance`, not Atlas. The first isolated run hit local MongoDB's disk-space threshold during startup index creation; the passing rerun disabled startup index initialization for the test process only.

## Atlas Runtime Smoke

Services:

- FastAPI: responded on `127.0.0.1:8000/docs`.
- Spring Boot backend: packaged jar started with Atlas runtime configuration; `POST /api/v1/auth/login` returned 200.
- Frontend: Vite responded on `http://localhost:5173/login`.
- Post-smoke historical check: local and Atlas `aqi_historical_snapshots` remained 75,085 with duplicate identity group sample count 0.

Authenticated browser verification completed against the Atlas-backed backend:

- Overview.
- Forecast.
- Historical Replay catalogue.
- Gomti replay.
- ITO replay.
- BKC replay.
- Source Attribution.
- Leaflet Maps.
- Enforcement.
- Health Advisory.
- Floating Copilot open and closed.
- Lucknow, Delhi, and Mumbai.
- API error state.

Screenshots refreshed:

- `final-authenticated-overview.png`
- `final-map-leaflet.png`
- `final-map-degraded-layer.png`
- `final-enforcement.png`
- `final-health-advisory.png`
- `final-copilot-open.png`
- `final-copilot-closed.png`
- `final-api-error-state.png`
- Additional replay/overview screenshots remained captured.

Browser diagnostics:

- Page runtime errors: 0.
- Flagged paid map SDK runtime messages: 0.
- Leaflet initialization errors: 0.
- React runtime errors: 0.
- Console errors were restricted-network `ERR_NETWORK_ACCESS_DENIED` for OpenStreetMap tile URLs only after the external font import was removed.

## Security And Secret Handling

- `.env` exists and is ignored by `.gitignore`.
- Source/config/docs scan found no `mongodb+srv://` URI pattern in checked committed files.
- Atlas URI was used only from environment variables and was not printed in this report.
- TLS is implied by the `mongodb+srv` Atlas connection mechanism.
- Atlas IP access and database-user least privilege were not independently inspectable from this shell; they should be confirmed in the Atlas console.

## Rollback

- Local `airsense` remains intact as rollback.
- Backup dump is retained at `D:\MongoBackup\atlas-migration-20260718-170809`.
- To rollback backend runtime, set `MONGODB_URI=mongodb://127.0.0.1:27017/airsense` and restart the backend.

## Remaining Limitations

- The secret Atlas URI should include `/airsense`, or runtime must continue passing `spring.data.mongodb.database=airsense`.
- Atlas console settings for IP access and database-user privilege must be confirmed manually.
- Local disk free space is below MongoDB's 500 MB index-build threshold, which can affect local test DB index creation.
- Browser console still reports blocked OSM tile requests in the restricted environment; the Leaflet fallback displays "Map tiles are temporarily unavailable."
- Local MongoDB independence port-closed test could not be completed from this shell because stopping local `mongod` PID 7300 returned Windows `Access is denied`. The backend was nevertheless launched with Atlas runtime configuration, authenticated successfully, completed the browser smoke, and showed active Atlas connections while local data remained untouched.

## Completion

Completion is approximately 94%. Migration, restore, count/index verification, test/build suite, Atlas runtime startup, and authenticated Atlas smoke verification are complete. Remaining items are Atlas-console security confirmation, optional local disk cleanup, and rerunning the local-Mongo-disabled proof from an Administrator shell.

## 2026-07-19 Map And Copilot Final Sweep

- Removed remaining paid browser-map SDK key/runtime references from source, examples, docs, and root `.env` without printing secrets.
- Municipal map now uses Leaflet/OpenStreetMap only, with Vite-safe marker icons, explicit map height, resize/recenter handling, and visible restricted-network tile fallback.
- Decision Copilot now returns and renders `answer`, `status`, `grounding`, and `limitations`; the submitted browser question produced a visible backend answer.
- Direct Copilot API: `POST /api/v1/intelligence/copilot/query` returned 200 with `status=PARTIAL`, AQI 98, station `Lalbagh, Lucknow - CPCB`, provider `CPCB_CAAQMS`, observedAt `2026-07-19T13:30:00Z`.
- Browser verification: authenticated overview, Copilot closed/open, Leaflet map, tile fallback, Enforcement, Health Advisory, Forecast, Historical Replay, and replay station catalogue for Gomti, ITO, and BKC verified.
- Browser diagnostics: paid browser-map SDK references 0, paid-map runtime messages 0, Leaflet init errors 0, React runtime errors 0, auth errors 0.
- Latest tests/builds: backend `mvnw.cmd test` 134 passed; backend `mvnw.cmd -DskipTests package` BUILD SUCCESS; Python forecasting tests 22 passed; frontend `npm.cmd run build` passed with Vite chunk-size warning only.
- Screenshots/results saved under `artifacts/copilot-map-verification/`.
