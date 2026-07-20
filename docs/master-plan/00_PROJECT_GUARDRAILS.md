# 00 — PROJECT GUARDRAILS (MANDATORY POLICY)

> **This file must be read before every implementation task.**
> **No phase, task, or code change may proceed without confirming compliance.**

---

## 1. IMPLEMENTATION SEQUENCE — EVERY TASK

Every requirement in the master blueprint ([script.md](file:///d:/Desktop/ETAI/script.md)) must follow this sequence:

```
AUDIT → CLASSIFY → PRESERVE → IMPLEMENT ONLY MISSING OR BROKEN PARTS → TEST → VERIFY → DOCUMENT
```

### Step 1: AUDIT

Before modifying code:

1. Inspect the complete repository.
2. Check backend, frontend, Python service, MongoDB schema, tests, configuration, and documentation.
3. Identify the current architecture, working modules, broken modules, mock/synthetic paths, duplicated logic, provider integrations, model artifacts, tests, and frontend flows.

### Step 2: CLASSIFY

Classify every requirement as one of:

| Status | Meaning |
|--------|---------|
| `ALREADY_IMPLEMENTED_AND_VERIFIED` | Working and tested — **DO NOT MODIFY** |
| `IMPLEMENTED_BUT_NOT_VERIFIED` | Code exists but needs testing first |
| `PARTIALLY_IMPLEMENTED` | Working portion preserved; only the missing portion is implemented |
| `IMPLEMENTED_BUT_BROKEN` | Identify root cause; apply smallest safe fix |
| `NOT_IMPLEMENTED` | Implement per master blueprint; integrate with existing architecture |
| `OUT_OF_SCOPE` | Not part of the approved master blueprint |

### Step 3: PRESERVE

The default action for existing working code is:

```
PRESERVE → VERIFY → DOCUMENT → DO NOT MODIFY
```

### Step 4: IMPLEMENT ONLY MISSING OR BROKEN PARTS

- For `ALREADY_IMPLEMENTED_AND_VERIFIED`: mark complete, do not touch.
- For `IMPLEMENTED_BUT_NOT_VERIFIED`: verify first; modify only if verification proves a real defect.
- For `PARTIALLY_IMPLEMENTED`: preserve the working portion; implement only the missing portion.
- For `IMPLEMENTED_BUT_BROKEN`: document the exact root cause; apply the smallest safe fix.
- For `NOT_IMPLEMENTED`: implement per master blueprint; integrate with existing architecture.

### Step 5: TEST

After every change:

- Run relevant existing tests.
- Add regression tests.
- Verify unrelated working features remain unchanged.
- Stop immediately if a regression appears.

### Step 6: VERIFY

Never claim completion unless:

- Backend tests pass (`.\mvnw.cmd test`)
- Python tests pass (`python -m pytest forecasting/tests -v`)
- Frontend builds (`npm run build`)
- Real API checks pass
- Browser verification passes

### Step 7: DOCUMENT

- Update `05_PROGRESS_TRACKER.md`
- Update `06_DECISIONS_AND_RATIONALE.md`
- Update `07_KNOWN_LIMITATIONS.md`
- Update `08_TEST_AND_VERIFICATION_MATRIX.md`
- Record exact files changed, commands run, tests passed, APIs verified, UI verified, and remaining blockers

---

## 2. EXISTING IMPLEMENTATION PRESERVATION — MANDATORY RULES

### 2.1 For `ALREADY_IMPLEMENTED_AND_VERIFIED`

- Do not modify its code.
- Do not rename its classes, fields, endpoints, or database collections.
- Do not rebuild it using another approach.
- Mark it completed in `05_PROGRESS_TRACKER.md`.
- Record evidence and passing tests in `08_TEST_AND_VERIFICATION_MATRIX.md`.

### 2.2 For `IMPLEMENTED_BUT_NOT_VERIFIED`

- First verify using tests, API calls, database inspection, and browser checks.
- Do not modify unless verification proves a real defect.

### 2.3 For `PARTIALLY_IMPLEMENTED`

- Preserve the working portion.
- Implement only the missing portion.
- Avoid replacing the whole module.

### 2.4 For `IMPLEMENTED_BUT_BROKEN`

- Identify and document the exact root cause.
- Apply the smallest safe fix.
- Preserve existing public contracts wherever possible.

### 2.5 For `NOT_IMPLEMENTED`

- Implement according to the master blueprint.
- Integrate with existing architecture instead of creating a parallel duplicate system.

---

## 3. NEVER CREATE DUPLICATES OF

- Provider services
- AQI calculation logic
- Location identity logic
- Station identity logic
- Repositories
- MongoDB collections
- Forecast orchestration
- Model registry
- Feature-generation pipelines
- Source-attribution engines
- Enforcement engines
- Frontend dashboard components
- API endpoints

---

## 4. PRESERVE ALL WORKING

- CPCB-first provider logic
- IQAir fallback
- OpenWeather supporting index
- Canonical location keys
- Station identity
- Shared snapshotId
- Historical ingestion
- Promoted model registry records
- Trained model artifacts
- Forecast fallback behavior
- Attribution logic
- Frontend API contracts
- MongoDB data and indexes
- Environment-variable names
- Tests

---

## 5. ML MODEL PROTECTION

Do not retrain, replace, demote, or delete existing promoted models unless:

1. The master plan explicitly requires retraining, AND
2. Evaluation proves the current model invalid, AND
3. The reason is documented before making the change.

---

## 6. MODIFICATION SAFETY PROTOCOL

Before modifying an existing working file, record:

- Why modification is necessary
- Exact defect or missing requirement
- Expected impact
- Regression risk
- Tests that will prove safety

---

## 7. DATABASE MIGRATION RULES

Migrations must be:

- Non-destructive
- Backward-compatible
- Idempotent
- Explicitly documented

---

## 8. API RESPONSE RULES

- Preserve existing API response fields.
- New fields must be additive unless an existing field is factually incorrect or unsafe.

---

## 9. FORBIDDEN ACTIONS

- Do not perform broad cleanup, architecture redesign, or cosmetic refactoring during functional implementation.
- Do not change a working implementation only to match a preferred coding style.
- Do not delete existing data, collections, indexes, artifacts, or configuration.
- Do not rewrite the complete platform in a new stack.

---

## 10. DEVIATION DETECTION

Before every implementation task, answer internally:

1. Which system requirement does this change satisfy?
2. Which phase does it belong to?
3. Does it duplicate existing logic?
4. Does it introduce synthetic or misleading output?
5. Does it mix AQI standards?
6. Does it confuse station scope with city scope?
7. Does it break snapshot consistency?
8. Does it bypass model promotion or confidence gates?
9. Does it expose secrets?
10. Does it move the project away from the original problem statement?

If the answer indicates drift:

- Stop.
- Document the drift in `06_DECISIONS_AND_RATIONALE.md`.
- Restore alignment before continuing.

---

## 11. CURRENT VERIFIED WORKING MODULES — DO NOT MODIFY

The following modules have been audited and classified as `ALREADY_IMPLEMENTED_AND_VERIFIED`. **They must not be touched unless a real defect is proven with evidence.**

| Module | Key Files | Status |
|--------|-----------|--------|
| CPCB-first AQI selection | `PrimaryAqiSelectionService.java`, `CpcbAqiService.java` | ✅ VERIFIED |
| IQAir fallback | `IqAirService.java` | ✅ VERIFIED |
| OpenWeather separate index | `FusionAqiProvider.java`, `OpenWeatherService.java` | ✅ VERIFIED |
| Canonical location identity | `CanonicalLocationIdentityService.java`, `LocationKey.java` | ✅ VERIFIED |
| Shared snapshot identity | `SnapshotIdentity.java` | ✅ VERIFIED |
| Historical ingestion | `HistoricalAirQualityIngestionService.java` | ✅ VERIFIED |
| ML training pipeline | `forecasting/training/train.py`, `forecasting/features/dataset.py` | ✅ VERIFIED |
| ML inference | `forecasting/inference/predict.py` | ✅ VERIFIED |
| Forecast orchestrator | `ForecastOrchestrator.java` (955 lines, 3-tier fallback) | ✅ VERIFIED |
| Source attribution engine | `PollutionSourceAttributionEngine.java`, `PollutionAttributionService.java` | ✅ VERIFIED |
| Geospatial intelligence | `GeoSpatialIntelligenceService.java` | ✅ VERIFIED |
| Enforcement intelligence | `EnforcementIntelligenceService.java` | ✅ VERIFIED |
| Decision Copilot | `DecisionCopilotService.java` | ✅ VERIFIED |
| Health advisory | `HealthAdvisoryService.java`, `AdvisoryMessageCatalog.java` | ✅ VERIFIED |
| Explainability | `ExplainabilityService.java` | ✅ VERIFIED |
| City comparison | `CityComparisonService.java` | ✅ VERIFIED |
| Forecast evaluation | `AqiForecastEvaluationService.java` | ✅ VERIFIED |
| Debug diagnostics | `AqiDebugController.java`, `CpcbAqiDiagnosticsService.java` | ✅ VERIFIED |
| Temporal intelligence | `TemporalIntelligenceService.java` | ✅ VERIFIED |
| Frontend Decision Dashboard | `DecisionDashboard.jsx` + 15 sub-components | ✅ VERIFIED |
| Auth & Security | `SecurityConfig.java`, `AuthController.java` | ✅ VERIFIED |
| MongoDB indexes | `MongoAirQualityIndexInitializer.java` | ✅ VERIFIED |
| Application configuration | `application.yml` (216 lines) | ✅ VERIFIED |
| Data fusion pipeline | `DataFusionService.java`, `FusionAqiProvider.java` | ✅ VERIFIED |
| Feature builder | `FeatureBuilder.java`, `ForecastFeatures.java` | ✅ VERIFIED |
| Model registry | `ForecastModelRegistryEntry.java`, `ForecastModelRegistryRepository.java` | ✅ VERIFIED |
| DEMO_DATA_ENABLED=false | `application.yml` line 83 | ✅ VERIFIED |

---

## 12. SUMMARY

The master blueprint is a **verification and completion guide**, not an instruction to rebuild the project from scratch.

```
PRESERVE → VERIFY → DOCUMENT → DO NOT MODIFY
```

**This file is referenced by:**
- [MASTER_EXECUTION_SCRIPT.md](file:///d:/Desktop/ETAI/docs/master-plan/MASTER_EXECUTION_SCRIPT.md)
- [04_PHASE_PLAN.md](file:///d:/Desktop/ETAI/docs/master-plan/04_PHASE_PLAN.md)
- [05_PROGRESS_TRACKER.md](file:///d:/Desktop/ETAI/docs/master-plan/05_PROGRESS_TRACKER.md)
