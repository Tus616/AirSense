# Removal Plan

| Path | Why it exists | References checked | Test coverage | Decision |
| --- | --- | --- | --- | --- |
| `test_advisory.py` | Manual local API probe | `git ls-files`, direct read | Superseded by backend tests | Delete |
| `test_forecast.py` | Manual legacy forecast probe | `git ls-files`, direct read | Superseded by forecast tests | Delete |
| `test_phase7.py` | Manual Gemini/policy probe | `git ls-files`, direct read | Superseded by backend tests | Delete |
| `test_resilience.py` | Manual metrics probe | `git ls-files`, direct read | Superseded by backend tests | Delete |
| `test_lucknow.py` | Manual forecast probe with hardcoded credentials | `git ls-files`, direct read | Superseded by forecast tests | Delete |
| `docs/pretrained-forecast-migration/` | Old Chronos migration notes | `rg --files docs` and direct read | Documentation replacement only | Delete |
| `docs/ml-forecast-fix/` | Temporary untracked forecast-fix notes | `rg --files docs/ml-forecast-fix` and direct read | Documentation replacement only | Delete after merge |
| `.vercelignore` | Prevents Vercel uploading backend/AI/local artifacts | direct read | Frontend build | Keep and commit |
| `backend/src/main/java/com/airsense/api/config/CorsConfig.java` | CORS production config | direct diff/read | Backend tests/build | Keep and commit |
| Legacy Spring forecast batch path | Old `/predict` and `/predict-batch` pipeline | `rg` references | Backend tests/build | Isolate behind `LEGACY_FORECAST_ENABLED=false` |
| Local logs/cache/browser profiles | Generated verification artifacts | root inventory | Not source-controlled | Keep ignored or remove locally |

No production database data is deleted by this cleanup.
