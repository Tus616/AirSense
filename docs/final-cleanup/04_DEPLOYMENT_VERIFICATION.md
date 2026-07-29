# Deployment Verification

Verified after pushing cleanup commit `e71486834b2ead968e631467b4a2b5cc05030bed` on 2026-07-29:

- GitHub: `origin/main` resolved to `e71486834b2ead968e631467b4a2b5cc05030bed`.
- Vercel: `https://air-sense-lyart.vercel.app/` returned HTTP 200 and served `/assets/index-Cle0cIeI.js`, matching the final local `npm run build` asset.
- Backend Render: `https://airsense-backend-0dht.onrender.com/api/v1` accepted a generated throwaway citizen registration and returned a JWT.
- Backend forecast: authenticated `GET /api/v1/intelligence/forecast` returned 3 forecast horizons with `mode=PERSISTENCE_FALLBACK`, `engine=PERSISTENCE_FALLBACK`, `fallbackUsed=true`, and `forecastStandard=INDIA_NAQI`.
- Backend diagnostics: authenticated `GET /api/v1/diagnostics/forecast/runtime` returned profile `prod`, `mlForecastEnabled=true`, `providerForecastProvider=OPEN_METEO`, `providerForecastStandard=US_AQI`, and `mlForecastServiceUrl=https://airsense-ai-service.onrender.com`.
- AI Render: `https://airsense-ai-service.onrender.com/health` returned `status=ok`; `/ready` returned `status=ready`, `chronosEnabled=false`, and `chronosModelLoaded=false`.
- AI provider forecast: `POST /internal/forecast/predict` with `currentAqi=null`, `forecastStandard=US_AQI`, and Open-Meteo provider settings returned 3 predictions using `OPEN_METEO_PROVIDER_FORECAST`.

Not covered by automated deployment probes:

- Manual browser workflows beyond confirming the deployed frontend asset.
- Chronos runtime inference with `CHRONOS_ENABLED=true`; the deployed low-memory profile intentionally keeps Chronos disabled.
