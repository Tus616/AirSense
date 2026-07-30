# Final Deployed Failure Matrix

Captured against `https://airsense-backend-0dht.onrender.com/api/v1` on 2026-07-30.

## Live Trace Summary

| City | Endpoint | Status | Visible / API result | Backend producer | Frontend consumer | Root cause | Fix status |
| --- | --- | ---: | --- | --- | --- | --- | --- |
| Delhi | `/intelligence/forecast` | 200 | 24h 102, 48h 83, 72h 74; engine `OPEN_METEO_PROVIDER_FORECAST`; provider `OPEN_METEO`; `fallbackReason=CHRONOS_DISABLED`; `fallbackUsed=false` | `ForecastOrchestrator` via `MlForecastClient` and AI Open-Meteo provider | `LiveForecastCard`, `ForecastStrip`, forecast chart | AI Render cold start first returned 502, causing persistence fallback. After `/health` warm-up, provider forecasts returned correctly. Frontend still rendered `CHRONOS_DISABLED` as fallback-like wording. | Frontend fixed locally; deployment pending. |
| Lucknow | `/intelligence/forecast` | 200 | 24h 61, 48h 67, 72h 78; engine `OPEN_METEO_PROVIDER_FORECAST`; `fallbackUsed=false` | same as above | same as above | Same cold-start and label issue. | Frontend fixed locally; deployment pending. |
| Mumbai | `/intelligence/forecast` | 200 | 24h 64, 48h 60, 72h 59; engine `OPEN_METEO_PROVIDER_FORECAST`; `fallbackUsed=false` | same as above | same as above | Same cold-start and label issue. | Frontend fixed locally; deployment pending. |
| Delhi | `/intelligence/attribution` via decision | 200 | 3 sources, total 100, `UNKNOWN=57` | `PollutionAttributionService` | `AttributionPanel`, source cards | No empty attribution in warmed production response. | Verified. |
| Lucknow | `/intelligence/attribution` via decision | 200 | 2 sources, total 100, `UNKNOWN=57` | same | same | No empty attribution in warmed production response. | Verified. |
| Mumbai | `/intelligence/attribution` via decision | 200 | 2 sources, total 100, `UNKNOWN=57` | same | same | No empty attribution in warmed production response. | Verified. |
| Delhi | `/intelligence/geospatial` | 200 | 12 layers, 1 AQI hotspot feature | `GeoSpatialIntelligenceService` | `GisDecisionMap` Leaflet GeoJSON circle rendering | Hotspot circles exist in API. Frontend already uses `L.circle` when `radiusMeters` is present. | Verified by API; local browser verification still needed. |
| Lucknow | `/intelligence/geospatial` | 200 | 12 layers, 1 AQI hotspot feature | same | same | Same. | Verified by API; local browser verification still needed. |
| Mumbai | `/intelligence/geospatial` | 200 | 12 layers, 1 AQI hotspot feature | same | same | Same. | Verified by API; local browser verification still needed. |
| Delhi | `/intelligence/decision` | 200 | 2 enforcement recommendations, 9 advisories, 5 timeline frames, explainability length 523 | `DecisionIntelligenceService` and module services | dashboard overview, enforcement, advisory, timeline, explainability panels | Core modules present. | Verified by API. |
| Lucknow | `/intelligence/decision` | 200 | 1 enforcement recommendation, 9 advisories, 5 timeline frames, explainability length 515 | same | same | Core modules present. | Verified by API. |
| Mumbai | `/intelligence/decision` | 200 | 1 enforcement recommendation, 9 advisories, 5 timeline frames, explainability length 521 | same | same | Core modules present. | Verified by API. |
| Delhi / Lucknow / Mumbai | `/intelligence/copilot/query` | 200 | Before local fix: `status=UNAVAILABLE`, `mode=DETERMINISTIC_FALLBACK`, answer length 59 for "Give the operational briefing..." | `DecisionCopilotService` | Copilot drawer | Intent classifier treated operational briefing as unsupported, and response status required `currentAQI > 0` even when valid forecast evidence existed. | Backend fixed locally with regression test; deployment pending. |

## Endpoint Response Evidence

- AI service cold start: `/health` returned 200 after 131,918 ms; `/ready` returned `ready`, `chronosStatus.enabled=false`, `providerClientConfigured=true`, and fallback engines including `OPEN_METEO_PROVIDER_FORECAST`.
- Forecast runtime diagnostics before warm-up: backend sent `currentAqiSent=null`, `forecastStandardSent=US_AQI`, `providerSent=OPEN_METEO`, `horizons=[24,48,72]`; AI response was HTTP 502 from Render.
- Forecast runtime after warm-up: all tested cities returned `OPEN_METEO_PROVIDER_FORECAST` with independent horizon AQIs and `fallbackUsed=false`.

## Frontend Fix Status

- `OPEN_METEO_PROVIDER_FORECAST` is normalized to "Atmospheric Provider Forecast."
- `CHRONOS_DISABLED` is displayed as "Chronos skipped; provider forecast used" for provider forecasts, not as a failed forecast.
- Provider forecast cards now describe coordinate/provider scope instead of station-only scope.
- Fallback banners now ignore valid provider forecast limitations and only show for actual persistence/unavailable fallback diagnostics.

## Remaining Deployment Gap

The source fixes are local in this workspace. Production Vercel/Render deployment has not been performed in this session, so deployed browser verification of the changed UI is still pending.
