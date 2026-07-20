# 08 - Test And Verification Matrix

Last updated: 2026-07-18T17:37:59+05:30

| Area | Verification | Result |
|---|---|---|
| Python forecasting | `.\venv\Scripts\python.exe -m pytest forecasting/tests -v` | 22 passed, 164 warnings |
| Backend tests | `.\mvnw.cmd test` with `MONGODB_URI=mongodb://127.0.0.1:27017/airsense_test_acceptance` and startup index init disabled | 129 passed, 0 failures, 0 errors, 0 skipped; first isolated run hit local MongoDB disk-threshold index creation, rerun passed with index init disabled |
| Backend focused replay tests | `.\mvnw.cmd -Dtest=HistoricalForecastReplayServiceTest test` | 9 passed after the final replay lookup refinement |
| Backend package | `.\mvnw.cmd -DskipTests package` | BUILD SUCCESS |
| Frontend build | `npm.cmd run build` | Passed, chunk-size warning only |
| Snapshot consistency | Regression test in `DecisionIntelligenceServiceTest` | Advisory receives fused snapshot |
| Fallback reasons | `ForecastFallbackReason` public enum mapping | Canonical labels returned through `fallbackReason` |
| Drift audit | `/api/v1/debug/drift-status` | Adds skip counts, tolerance, samples, model/baseline metrics |
| Rollback | `ForecastModelRollbackServiceTest` | Success, checksum failure, scope mismatch, missing previous, persistence failure |
| Retraining contract | `/api/v1/debug/trigger-retrain` | Records/dedupes `PENDING_EXTERNAL_PIPELINE` |
| Historical replay endpoints | `POST /api/v1/air-quality/forecast/historical-replay`, `GET /api/v1/air-quality/forecast/historical-replay/stations` | Added; read-only same-station `INDIA_NAQI` archive replay |
| Historical replay diagnostics | `GET /api/v1/air-quality/forecast/historical-replay/diagnostics` | Active DB `airsense`, collection `aqi_historical_snapshots`, total rows 75,085, archive rows found for three stations |
| Frontend replay integration | `npm.cmd run build` | Passed after replay API wiring |
| Replay station catalogue | `GET /api/v1/air-quality/forecast/historical-replay/stations` | 3 available stations: Gomti 21,544 rows; ITO 20,485 rows; BKC 7,769 rows |
| Replay examples | Historical replay API and browser replay card | Gomti/BKC/ITO full-actual examples returned predictions and comparison actuals; Gomti missing-actual example kept null actuals |
| Protected browser dashboard | Playwright authenticated sweep | Generated `final-authenticated-overview.png`, overview desktop/mobile, Gomti/ITO/BKC replay, missing-actual replay, Leaflet map, degraded map layer, enforcement, health advisory, Copilot open/closed, and API error-state screenshots |
| Browser console | Playwright console/runtime capture | 0 page errors; 0 paid map SDK errors; 0 Leaflet initialization errors; 0 React runtime errors; console errors were restricted-network Google Fonts/OSM tile fetches only |
| Leaflet source check | Frontend code/build and browser DOM | `GisDecisionMap.jsx` renders React-Leaflet/OSM; browser verified `.leaflet-container`; paid map SDK error absent |
| Mongo recovery | Port and data verification | `mongod` listening on `127.0.0.1:27017`; `airsense.aqi_historical_snapshots` accessible with 75,085 total rows |
| MongoDB tools | Portable official tools | `mongodump` 100.16.1, `mongorestore` 100.16.1, `mongosh` 2.9.2 verified |
| Local dump | `mongodump --uri="mongodb://127.0.0.1:27017/airsense" --db=airsense --out="D:\MongoBackup\atlas-migration-20260718-170809"` | Passed; 22 BSON files, 22 metadata files, 76,749,095 bytes |
| Atlas pre-restore inspection | `mongosh` using `MONGODB_ATLAS_URI` | Authenticated successfully; target `airsense` database had 0 collections |
| Atlas restore | `mongorestore` using `MONGODB_ATLAS_URI` and verified dump | 136,474 documents restored successfully; 0 failures; no `--drop` used |
| Atlas collection counts | Local vs Atlas count comparison for all collections | All 22 collection counts matched |
| Atlas exact replay counts | `dataOrigin=HISTORICAL_TRAINING_ARCHIVE`, `aqiStandard=INDIA_NAQI` | Gomti 21,544; ITO 20,485; BKC 7,769; local and Atlas matched |
| Atlas indexes | `getIndexes()` local vs Atlas | Key indexes matched on `aqi_historical_snapshots`, `tracked_air_quality_locations`, and `aqi_forecast_runs`; backend startup verified expected indexes |
| Atlas backend runtime | Packaged jar with `MONGODB_URI` set from Atlas secret and runtime database `airsense` | Login returned 200; replay station catalogue returned data; backend accepted traffic |
| Atlas browser smoke | Playwright authenticated sweep against Atlas backend | Overview, Forecast, Replay, Source Attribution, Leaflet Maps, Enforcement, Health Advisory, Copilot open/closed, API error state, Lucknow/Delhi/Mumbai verified; 0 page errors and 0 flagged Google/Leaflet/React messages |
| Local Mongo independence | Attempted temporary stop of local `mongod` PID 7300 | Blocked by Windows `Access is denied`; backend remained authenticated and connected to Atlas, but port-closed proof requires Administrator shell |

Runtime API and manual browser observations are recorded in `10_FINAL_ACCEPTANCE_REPORT.md`.
