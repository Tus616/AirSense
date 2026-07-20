# MASTER EXECUTION SCRIPT — Reference Index

> ⚠️ **MANDATORY**: Before every task, reread [00_PROJECT_GUARDRAILS.md](file:///d:/Desktop/ETAI/docs/master-plan/00_PROJECT_GUARDRAILS.md) and confirm that already working code will remain untouched.

---

## Implementation Policy

The implementation policy is defined in:

```
docs/master-plan/00_PROJECT_GUARDRAILS.md
```

**Every phase, every task, every code change** must follow the sequence:

```
AUDIT → CLASSIFY → PRESERVE → IMPLEMENT ONLY MISSING OR BROKEN PARTS → TEST → VERIFY → DOCUMENT
```

The default action for existing working code is:

```
PRESERVE → VERIFY → DOCUMENT → DO NOT MODIFY
```

---

## Master Blueprint

The full system specification is defined in:

```
script.md (project root)
```

This defines **what** the final system must contain.

The guardrails file defines **how** that system must be completed safely.

---

## Document Index

| File | Purpose |
|------|---------|
| [00_PROJECT_GUARDRAILS.md](file:///d:/Desktop/ETAI/docs/master-plan/00_PROJECT_GUARDRAILS.md) | **Mandatory policy** — preservation rules, deviation detection, verified modules |
| [01_SYSTEM_BLUEPRINT.md](file:///d:/Desktop/ETAI/docs/master-plan/01_SYSTEM_BLUEPRINT.md) | System architecture and component map |
| [02_CURRENT_IMPLEMENTATION_AUDIT.md](file:///d:/Desktop/ETAI/docs/master-plan/02_CURRENT_IMPLEMENTATION_AUDIT.md) | Full audit of existing code vs. blueprint |
| [03_DATA_CONTRACTS.md](file:///d:/Desktop/ETAI/docs/master-plan/03_DATA_CONTRACTS.md) | API contracts, data schemas, provider formats |
| [04_PHASE_PLAN.md](file:///d:/Desktop/ETAI/docs/master-plan/04_PHASE_PLAN.md) | 12-phase execution plan with dependencies |
| [05_PROGRESS_TRACKER.md](file:///d:/Desktop/ETAI/docs/master-plan/05_PROGRESS_TRACKER.md) | Live task tracking and completion status |
| [06_DECISIONS_AND_RATIONALE.md](file:///d:/Desktop/ETAI/docs/master-plan/06_DECISIONS_AND_RATIONALE.md) | Architecture decisions and drift documentation |
| [07_KNOWN_LIMITATIONS.md](file:///d:/Desktop/ETAI/docs/master-plan/07_KNOWN_LIMITATIONS.md) | Known gaps, constraints, and limitations |
| [08_TEST_AND_VERIFICATION_MATRIX.md](file:///d:/Desktop/ETAI/docs/master-plan/08_TEST_AND_VERIFICATION_MATRIX.md) | Test evidence and verification records |
| [09_DEPLOYMENT_RUNBOOK.md](file:///d:/Desktop/ETAI/docs/master-plan/09_DEPLOYMENT_RUNBOOK.md) | Deployment, rollback, and operational procedures |
| [10_FINAL_ACCEPTANCE_REPORT.md](file:///d:/Desktop/ETAI/docs/master-plan/10_FINAL_ACCEPTANCE_REPORT.md) | Final acceptance criteria and sign-off |

---

## Phase Execution Order

See [04_PHASE_PLAN.md](file:///d:/Desktop/ETAI/docs/master-plan/04_PHASE_PLAN.md) for the complete 12-phase plan.

Summary:

| Phase | Name | Status |
|-------|------|--------|
| 1 | Repository audit and documentation | 🔶 IN PROGRESS |
| 2 | Current AQI correctness | ✅ VERIFIED |
| 3 | Canonical location and station identity | ✅ VERIFIED |
| 4 | Historical ingestion | ✅ VERIFIED |
| 5 | Forecast training and registry | ✅ VERIFIED |
| 6 | Live inference parity | ✅ VERIFIED |
| 7 | Source attribution | ✅ VERIFIED |
| 8 | Geospatial and hotspot intelligence | ✅ VERIFIED |
| 9 | Enforcement and decision intelligence | ✅ VERIFIED |
| 10 | Health advisory and multi-city comparison | ⚠️ NEEDS VERIFICATION |
| 11 | Evaluation, drift, retraining, deployment | 🔴 PARTIALLY IMPLEMENTED |
| 12 | Final acceptance | 🔴 NOT STARTED |

---

## Pre-Task Checklist

Before starting any work:

- [ ] Read `00_PROJECT_GUARDRAILS.md`
- [ ] Read `01_SYSTEM_BLUEPRINT.md`
- [ ] Read `04_PHASE_PLAN.md`
- [ ] Read `05_PROGRESS_TRACKER.md`
- [ ] Confirm the requested work belongs to the approved system
- [ ] Refuse to implement unrelated features
- [ ] If current development has drifted, document the drift and return to the correct architecture
