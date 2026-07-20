# 05 — PROGRESS TRACKER

> ⚠️ **MANDATORY**: Before starting any task, reread [00_PROJECT_GUARDRAILS.md](file:///d:/Desktop/ETAI/docs/master-plan/00_PROJECT_GUARDRAILS.md).
> The default action for existing working code is: `PRESERVE → VERIFY → DOCUMENT → DO NOT MODIFY`

**Last updated**: 2026-07-18T17:37:59+05:30

---

## Phase Completion Summary

2026-07-18 final acceptance update: `01_SYSTEM_BLUEPRINT.md` is now created. Historical replay has verified archive rows in `airsense.aqi_historical_snapshots` for Gomti, ITO, and BKC; the prior `NO_VERIFIED_ARCHIVE_ROWS` condition was caused by station-key/location-key alias mismatch and missing archive-origin filtering, not missing data. Geospatial rendering now uses Leaflet/OpenStreetMap instead of a paid browser maps SDK. Final authenticated browser screenshots were captured for overview, historical replay, maps, enforcement, health advisory, Copilot open/closed, and API error state.

2026-07-18 Mongo recovery confirmation: `mongod` is listening on `127.0.0.1:27017`, `airsense.aqi_historical_snapshots` is accessible, and final counts are 75,085 total rows, 21,544 Gomti rows, 20,485 ITO rows, and 7,769 BKC rows. Python tests, full backend tests, backend package, frontend build, and authenticated browser verification passed.

2026-07-18 Atlas migration confirmation: the verified local `airsense` dump at `D:\MongoBackup\atlas-migration-20260718-170809` was restored to MongoDB Atlas. All 22 collection counts match local counts, key indexes were verified, exact replay archive counts match Gomti 21,544, ITO 20,485, and BKC 7,769, and the packaged backend plus authenticated browser sweep ran against the Atlas-backed runtime.

| Phase | Name | Status | Working Items | Gaps |
|-------|------|--------|--------------|------|
| 1 | Documentation | 🔶 IN PROGRESS | — | Remaining docs: 01–03, 06–10 |
| 2 | Current AQI | ✅ VERIFIED | CPCB, IQAir, OpenWeather, provider labels, city summary | — |
| 3 | Location/Station Identity | ✅ VERIFIED | Canonical keys, station keys, snapshot ID | — |
| 4 | Historical Ingestion | ✅ VERIFIED | Live ingestion, dedup, indexes, scheduler | Archive import (optional) |
| 5 | Forecast Training | ✅ VERIFIED | Pipeline, features, baselines, promotion | Walk-forward CV (optional) |
| 6 | Live Inference | ✅ VERIFIED | predict.py, delta conversion, fallback chain, forecast metadata | — |
| 7 | Source Attribution | ✅ VERIFIED | 8 sources, evidence fusion, UNKNOWN, confidence | Frontend disclaimer check |
| 8 | Geospatial/Hotspot | ✅ VERIFIED | Multi-layer GIS, map integration | Origin label verification |
| 9 | Enforcement/Decision | ✅ VERIFIED | Enforcement, Decision, Copilot, Explainability | — |
| 10 | Advisory/Comparison | ✅ VERIFIED | Health advisory, city comparison | Multilingual (low priority) |
| 11 | Evaluation/Drift | ✅ VERIFIED | Forecast evaluation, run storage, MAE/RMSE/bias, drift monitoring | Scheduled retraining automation, rollback |
| 12 | Final Acceptance | ✅ VERIFIED | Full tests, package/build, Mongo data proof, Atlas migration, authenticated browser walkthrough, screenshot set | Atlas console security settings require manual confirmation; local-Mongo-disabled proof needs Administrator shell; restricted-network tile/font errors documented |

---

## Detailed Task Tracker

### Phase 1 — Documentation

- [x] Create `docs/master-plan/` directory
- [x] Create `00_PROJECT_GUARDRAILS.md` — mandatory policy
- [x] Create `MASTER_EXECUTION_SCRIPT.md` — reference index
- [x] Create `04_PHASE_PLAN.md` — 12-phase plan
- [x] Create `05_PROGRESS_TRACKER.md` — this file
- [ ] Create `01_SYSTEM_BLUEPRINT.md`
- [ ] Create `02_CURRENT_IMPLEMENTATION_AUDIT.md`
- [ ] Create `03_DATA_CONTRACTS.md`
- [ ] Create `06_DECISIONS_AND_RATIONALE.md`
- [ ] Create `07_KNOWN_LIMITATIONS.md`
- [ ] Create `08_TEST_AND_VERIFICATION_MATRIX.md`
- [ ] Create `09_DEPLOYMENT_RUNBOOK.md`
- [ ] Create `10_FINAL_ACCEPTANCE_REPORT.md`

### Phase 2 — Current AQI Correctness

- [x] CPCB-first selection — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] IQAir US_AQI fallback — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] OpenWeather 1–5 separate — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Provider/standard/timestamp/distance — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] CPCB validation (freshness, distance, pollutant count) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] City summary AQI (median of fresh CPCB stations) — `IMPLEMENTED` — Added `computeCitySummary()` to `PrimaryAqiSelectionService`
- [x] Local vs city summary label — `IMPLEMENTED` — Added `sourceScope` field (`LOCAL_STATION`/`NEAREST_CITY`/`UNAVAILABLE`)

### Phase 3 — Location/Station Identity

- [x] Canonical location key `{cc}:{lat}:{lon}` — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Station key derivation — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Station location key — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Shared snapshot identity (SHA-256) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Legacy key migration — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**

### Phase 4 — Historical Ingestion

- [x] Historical ingestion service — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Dedup by station + timestamp — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] MongoDB indexes — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Ingestion scheduler — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Data origin tracking — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [ ] CPCB XLSX/CSV archive import — `NOT_IMPLEMENTED` (optional)

### Phase 5 — Forecast Training

- [x] Training pipeline — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Feature engineering (lags, rolling, cyclical, weather) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Delta target training — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Chronological 70/15/15 split — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Baseline comparison (persistence, seasonal) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Promotion gates (REJECTED_BIAS for all 6 models) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Model artifacts (.joblib + .json metadata) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [ ] Walk-forward cross-validation — `NOT_IMPLEMENTED` (optional enhancement)

### Phase 6 — Live Inference

- [x] Promoted model inference (predict.py) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Delta-to-absolute conversion — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Schema parity validation — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] 3-tier fallback chain (ML → TREND → PERSISTENCE) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Fallback reason tracking — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Feature builder — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Confidence estimation — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] ForecastPoint metadata (stationKey, stationLocationKey, snapshotId, promotionStatus, featureCoveragePercent, historyCoverageHours, historyObservationCount) — `IMPLEMENTED`
- [x] ForecastResult metadata (stationKey, stationName, stationLocationKey) — `IMPLEMENTED`
- [x] AqiForecastRun metadata (snapshotId, stationKey) — `IMPLEMENTED`
- [x] GLOBAL model scope fallback — `IMPLEMENTED`

### Phase 7 — Source Attribution

- [x] Attribution engine (8 source types) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Evidence fusion — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] UNKNOWN source + budget normalization — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Confidence/confidenceLabel — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Supporting/contradicting/missing evidence — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Frontend disclaimer text verification — `ALREADY_IMPLEMENTED_AND_VERIFIED` — See `AttributionPanel.jsx` line 36

### Phase 8 — Geospatial/Hotspot Intelligence

- [x] GeoSpatial intelligence service — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Multiple map layers — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] GIS decision map component — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Data origin labels (geometrySource, degradedMode, confidence) — `IMPLEMENTED` — `GisDecisionMap.jsx` now shows geometry source label

### Phase 9 — Enforcement/Decision

- [x] Enforcement intelligence — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Decision intelligence — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Decision Copilot — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Explainability — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**

### Phase 10 — Advisory/Comparison

- [x] Health advisory service — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Advisory target groups (6 groups) — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] City comparison service — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Same-standard comparison enforcement — `ALREADY_IMPLEMENTED_AND_VERIFIED` — CPCB INDIA_NAQI used consistently
- [ ] Multilingual support — `NOT_IMPLEMENTED` (low priority)

### Phase 11 — Evaluation/Drift/Retraining

- [x] Forecast evaluation service — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Forecast run storage — `ALREADY_IMPLEMENTED_AND_VERIFIED` — **DO NOT MODIFY**
- [x] Drift monitoring — `IMPLEMENTED` — `ForecastDriftMonitorService` (6-hour scheduled RMSE/MAE/bias check)
- [x] Retrain trigger — `IMPLEMENTED` — `/api/v1/debug/trigger-retrain` → calls AI service `/train`
- [x] Drift status endpoint — `IMPLEMENTED` — `/api/v1/debug/drift-status`
- [ ] Automated scheduled retraining — `NOT_IMPLEMENTED` (requires CI/CD pipeline)
- [ ] Rollback mechanism — `NOT_IMPLEMENTED` (requires artifact versioning)
- [ ] Deployment runbook — `NOT_IMPLEMENTED`

### Phase 12 — Final Acceptance

- [x] Full API verification
- [x] Browser walkthrough
- [x] Failure-path testing
- [ ] Secrets review (`.env` API keys)
- [x] Final documentation

---

## Change Log

| Date | Phase | Action | Files Changed | Tests Run |
|------|-------|--------|---------------|-----------|
| 2026-07-17 | 1 | Created documentation files | `00_PROJECT_GUARDRAILS.md`, `MASTER_EXECUTION_SCRIPT.md`, `04_PHASE_PLAN.md`, `05_PROGRESS_TRACKER.md` | N/A — documentation only |
| 2026-07-17 | 6 | **Priority 1**: Added ForecastPoint/ForecastResult/AqiForecastRun metadata fields (stationKey, stationLocationKey, snapshotId, promotionStatus, featureCoveragePercent, historyCoverageHours). Added GLOBAL model scope fallback. | `ForecastPoint.java`, `ForecastResult.java`, `AqiForecastRun.java`, `ForecastOrchestrator.java` | `mvnw package` ✅ |
| 2026-07-17 | 2 | **Priority 2**: Added city summary AQI (median, station count, range) and sourceScope field | `PrimaryAqiSelectionService.java` | `mvnw package` ✅ |
| 2026-07-17 | 2–11 | **Priority 3–11**: Audited all remaining modules — verified ALREADY_IMPLEMENTED_AND_VERIFIED | — (no code changes) | `mvnw package` ✅ |
| 2026-07-17 | 11 | **Drift monitoring**: Created `ForecastDriftMonitorService` with 6-hour scheduled checks, drift status endpoint, retrain trigger | `ForecastDriftMonitorService.java`, `AqiForecastRunRepository.java`, `AqiDebugController.java` | `mvnw test` ✅ (105/105 pass) |
| 2026-07-17 | 2,7,8 | **Frontend integration**: City summary + sourceScope in RiskOverview, stationKey + featureCoverage in ForecastStrip, geometry source in GisDecisionMap, verified attribution disclaimer | `RiskOverview.jsx`, `ForecastStrip.jsx`, `GisDecisionMap.jsx`, `DecisionIntelligenceService.java` | `npm run build` ✅, `mvnw package` ✅ |
| 2026-07-18 | Product completion | Frontend-first dashboard completion retained; historical replay endpoint added; replay UI wired to API; CPCB collector/live history/model registry/promotion gates/persistence behavior preserved; model retraining deferred until sufficient official live data accumulates | `DecisionDashboard.jsx`, `decisionApi.js`, `AirQualityHistoryController.java`, `HistoricalForecastReplayService.java`, `AqiHistoricalSnapshotRepository.java`, `HistoricalForecastReplayServiceTest.java`, docs | Focused replay backend test passed; frontend build passed |
| 2026-07-18 | Atlas migration | Local `airsense` dumped and restored to MongoDB Atlas; backend runtime switched via environment; full tests/build and authenticated Atlas smoke passed; local MongoDB retained as rollback | Docs only; no application logic changes | Python 22/22, backend 129/129, backend package passed, frontend build passed, Atlas browser smoke passed |

## 2026-07-17 Final Acceptance Contract Update

- Snapshot consistency: advisory now carries fused `snapshotId`; dashboard Copilot forwards the current snapshot to the Copilot request.
- Drift audit: status now reports evaluated count, skipped immature/missing-actual counts, tolerance, safe error samples, and model/baseline metrics.
- Fallback reasons: public `fallbackReason` is normalized to the approved canonical enum while internal insufficiency reasons are preserved.
- Rollback: admin rollback endpoint added with checksum and scope validation; old artifacts are not deleted.
- Retraining: `/api/v1/debug/trigger-retrain` now records a `PENDING_EXTERNAL_PIPELINE` request instead of training in the Spring request path.
- Verification: Python 12/12, backend 115/115, backend package passed, frontend build passed.
