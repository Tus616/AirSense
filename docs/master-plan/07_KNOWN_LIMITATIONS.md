# 07 - Known Limitations

Last updated: 2026-07-18T17:37:59+05:30

## Production Limitations

- ML forecasts are not forced when fresh contiguous live history is insufficient.
- Historical archive rows are allowed for training/backfill only. They are rejected as live lag features when stale, low coverage, or gap limits fail.
- Automated retraining is not implemented in the Spring request path. The backend records `PENDING_EXTERNAL_PIPELINE` requests for an external CI/CD worker.
- Rollback validates registry metadata and artifact checksums, then switches active versions. It never deletes old artifacts.
- Browser verification depends on the local Vite/Spring/FastAPI runtime being available and the configured MongoDB target being reachable.
- Production ML retraining and promotion are intentionally deferred until enough diverse official live CPCB history accumulates. Current live forecasts must continue to show safe fallback when models are not validated.
- Historical replay currently uses a safe same-station `INDIA_NAQI` historical-persistence replay engine and reports future actual comparison rows when available. It does not retrain, force ML, or change production live-forecast eligibility.
- Atlas runtime requires database `airsense` to be specified either in the Atlas URI path or by runtime property `spring.data.mongodb.database=airsense`. During verification the backend was started with the runtime database property because Spring could not infer a database name from the secret URI as provided.
- Local MongoDB remains the rollback copy and was not deleted or overwritten. The Atlas migration dump is retained at `D:\MongoBackup\atlas-migration-20260718-170809`.
- Local MongoDB independence testing requires an Administrator shell on this machine. The attempt to stop local `mongod` PID 7300 from this shell returned Windows `Access is denied`, so the port-closed proof remains outstanding.

## Accepted Non-Blockers

- Frontend bundle still emits a chunk-size warning.
- Python dataset tests emit pandas fragmentation warnings.
- Walk-forward CV and multilingual advisory support remain optional enhancements.
- Vite/React route screenshots require an authenticated browser session; automated verification uses Chrome DevTools quick-login and city presets.
- OpenStreetMap tile availability depends on outbound network access in the deployed browser environment.
- In the restricted local shell, Google Fonts and OpenStreetMap tile requests produced browser `ERR_NETWORK_ACCESS_DENIED`; Leaflet itself initialized and no paid map SDK error remained.
- FastAPI responded on `127.0.0.1:8000/docs` during the Atlas runtime sweep.
- Backend tests were intentionally run against isolated local database `airsense_test_acceptance`, not Atlas. The first isolated run hit MongoDB's local disk-space threshold during startup index creation; rerun passed with startup index initialization disabled for the test process.
