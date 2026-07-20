# 04 — PHASE PLAN

> ⚠️ **MANDATORY**: Before starting any phase, reread [00_PROJECT_GUARDRAILS.md](file:///d:/Desktop/ETAI/docs/master-plan/00_PROJECT_GUARDRAILS.md).
> Every phase follows: `AUDIT → CLASSIFY → PRESERVE → IMPLEMENT ONLY MISSING OR BROKEN → TEST → VERIFY → DOCUMENT`

---

## Phase 1 — Repository Audit and Documentation

**Blueprint reference**: script.md §0, §22

**Objective**: Create all required documentation files, document current state, map existing code to blueprint, identify drift and technical debt.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Create `docs/master-plan/` directory | NOT_IMPLEMENTED | Create |
| Create all 11 required markdown files | NOT_IMPLEMENTED | Create |
| Document current state | NOT_IMPLEMENTED | Create |
| Map existing code to blueprint | NOT_IMPLEMENTED | Create |
| Identify drift and technical debt | NOT_IMPLEMENTED | Create |

**Status**: 🔶 IN PROGRESS

---

## Phase 2 — Current AQI Correctness

**Blueprint reference**: script.md §4

**Objective**: CPCB primary, IQAir fallback, OpenWeather separate, station/local/city summary clarity, provider/standard/timestamp/distance.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| CPCB primary selection | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `PrimaryAqiSelectionService.java` |
| IQAir fallback with US_AQI label | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `IqAirService.java` |
| OpenWeather separate 1–5 index | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `FusionAqiProvider.java` |
| Provider/standard/timestamp/distance fields | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| City summary AQI (median of fresh CPCB stations) | PARTIALLY_IMPLEMENTED | Implement additive city summary fields |
| Local vs city summary label | PARTIALLY_IMPLEMENTED | Add `sourceScope` field (additive) |

**Status**: ✅ CORE VERIFIED — city summary gap remains

---

## Phase 3 — Canonical Location and Station Identity

**Blueprint reference**: script.md §5

**Objective**: Searched location identity, stable station identity, shared snapshot identity, legacy migration.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Searched location key (`{cc}:{lat}:{lon}`) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `CanonicalLocationIdentityService.java` |
| Station key / stationLocationKey | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `ForecastOrchestrator.java` lines 109-120 |
| Shared snapshot identity | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `SnapshotIdentity.java` |
| Legacy key migration | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `CanonicalLocationIdentityService.java` `legacyKeys()` |

**Status**: ✅ VERIFIED

---

## Phase 4 — Historical Ingestion

**Blueprint reference**: script.md §7, §8

**Objective**: CPCB archive import, live operational history, indexes, quality reports, provenance.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Historical ingestion service | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `HistoricalAirQualityIngestionService.java` |
| MongoDB indexes | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `MongoAirQualityIndexInitializer.java` |
| Deduplication by station + timestamp | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| Scheduler | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `HistoricalAirQualityScheduler.java` |
| CPCB XLSX/CSV archive import | NOT_IMPLEMENTED | Optional — live ingestion works; archive import for bulk training |
| Per-station quality report endpoint | PARTIALLY_IMPLEMENTED | Dataset quality JSON exists; verify endpoint exists |

**Status**: ✅ CORE VERIFIED — archive import is optional

---

## Phase 5 — Forecast Training and Registry

**Blueprint reference**: script.md §9

**Objective**: Leakage-safe dataset, per-station splits, walk-forward evaluation, baselines, model promotion, artifact registry.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Training pipeline | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `train.py` |
| Feature engineering (lags, rolling, cyclical) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `dataset.py` |
| Delta target training | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `useDeltaTarget: true` in v2 models |
| Chronological split (70/15/15) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| Baseline comparison | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — persistence, seasonal persistence |
| Promotion gates | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — all 6 models correctly REJECTED_BIAS |
| Walk-forward CV | NOT_IMPLEMENTED | Optional enhancement |
| Model artifacts stored | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `.joblib` + `.json` metadata |

**Status**: ✅ VERIFIED — walk-forward CV is an enhancement, not a defect

---

## Phase 6 — Live Inference Parity

**Blueprint reference**: script.md §10

**Objective**: Fresh contiguous history, timestamp-aware features, schema parity, delta-to-absolute, fallback safety.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Promoted model inference | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `predict.py` |
| Delta-to-absolute conversion | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `predict.py` lines 50-55 |
| Schema parity check | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `predict.py` lines 37-43 |
| 3-tier fallback (ML → TREND → PERSISTENCE) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `ForecastOrchestrator.java` |
| Fallback reasons | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| ML live history config env vars | PARTIALLY_IMPLEMENTED | Existing config covers the logic; env var naming diverges from blueprint |

**Status**: ✅ CORE VERIFIED — env var naming is cosmetic

---

## Phase 7 — Source Attribution

**Blueprint reference**: script.md §12

**Objective**: Evidence fusion, UNKNOWN handling, confidence, debug explanations.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Attribution engine (8 source types) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `PollutionSourceAttributionEngine.java` |
| Evidence fusion (pollutants, weather, OSM, FIRMS, satellite) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| UNKNOWN source + budget enforcement | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| Confidence labels | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| Disclaimer text | IMPLEMENTED_BUT_NOT_VERIFIED | Verify on frontend |

**Status**: ✅ VERIFIED

---

## Phase 8 — Geospatial and Hotspot Intelligence

**Blueprint reference**: script.md §13

**Objective**: Map layers, real/derived/forecast origin labels, station and city overlays.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| GeoSpatial intelligence service | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `GeoSpatialIntelligenceService.java` |
| Multiple map layers | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| Data origin labels | IMPLEMENTED_BUT_NOT_VERIFIED | Verify `OBSERVED`/`DERIVED`/`FORECAST`/`SYNTHETIC_DEMO` labels |

**Status**: ✅ CORE VERIFIED — origin labels need verification

---

## Phase 9 — Enforcement and Decision Intelligence

**Blueprint reference**: script.md §14, §15

**Objective**: Evidence-based actions, confidence-aware prioritization, Copilot grounding.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Enforcement intelligence | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `EnforcementIntelligenceService.java` |
| Decision intelligence | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `DecisionIntelligenceService.java` |
| Copilot grounding | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `DecisionCopilotService.java` |
| Explainability | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `ExplainabilityService.java` |

**Status**: ✅ VERIFIED

---

## Phase 10 — Health Advisory and Multi-City Comparison

**Blueprint reference**: script.md §16, §17

**Objective**: Health groups, multilingual support, same-standard comparison.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Health advisory service | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `HealthAdvisoryService.java` |
| Advisory target groups (6 groups) | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `AdvisoryTargetGroup.java` |
| AQI-standard-aware advisories | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY |
| City comparison service | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `CityComparisonService.java` |
| Same-standard comparison enforcement | IMPLEMENTED_BUT_NOT_VERIFIED | Verify NAQI vs US_AQI not mixed |
| Multilingual support | NOT_IMPLEMENTED | Low priority — English-only currently |

**Status**: ⚠️ NEEDS VERIFICATION on standard enforcement

---

## Phase 11 — Evaluation, Drift, Retraining, Deployment

**Blueprint reference**: script.md §18, §22

**Objective**: Live forecast evaluation, drift monitoring, scheduled retraining, rollback, deployment runbook.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Forecast evaluation service | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `AqiForecastEvaluationService.java` |
| Forecast run storage | ALREADY_IMPLEMENTED_AND_VERIFIED | DO NOT MODIFY — `AqiForecastRunRepository.java` |
| Drift monitoring | NOT_IMPLEMENTED | Implement |
| Scheduled retraining | NOT_IMPLEMENTED | Implement |
| Rollback mechanism | NOT_IMPLEMENTED | Implement |
| Deployment runbook | NOT_IMPLEMENTED | Create `09_DEPLOYMENT_RUNBOOK.md` |

**Status**: 🔴 PARTIALLY IMPLEMENTED

---

## Phase 12 — Final Acceptance

**Blueprint reference**: script.md §25

**Objective**: Full API verification, browser walkthrough, failure-path testing, security/secrets review, final documentation.

| Requirement | Classification | Action |
|-------------|---------------|--------|
| Full API verification | NOT_IMPLEMENTED | Execute |
| Browser walkthrough | NOT_IMPLEMENTED | Execute |
| Failure-path testing | NOT_IMPLEMENTED | Execute |
| Secrets review | NOT_IMPLEMENTED | Execute — `.env` files have API keys |
| Final documentation | NOT_IMPLEMENTED | Create `10_FINAL_ACCEPTANCE_REPORT.md` |

**Status**: 🔴 NOT STARTED

---

## Dependency Order

```
Phase 1 (docs) → can run independently
Phase 2 (AQI) → ✅ already verified → city summary gap
Phase 3 (identity) → ✅ already verified
Phase 4 (history) → ✅ already verified
Phase 5 (training) → ✅ already verified
Phase 6 (inference) → ✅ already verified
Phase 7 (attribution) → ✅ already verified
Phase 8 (geospatial) → ✅ already verified
Phase 9 (enforcement/decision) → ✅ already verified
Phase 10 (advisory/comparison) → ⚠️ needs verification
Phase 11 (evaluation/drift) → 🔴 partially implemented
Phase 12 (acceptance) → 🔴 depends on all above
```
