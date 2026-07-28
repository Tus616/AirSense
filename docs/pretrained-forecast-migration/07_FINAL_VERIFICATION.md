Local FastAPI verification completed on 2026-07-28 with real coordinates and Open-Meteo runtime history.

- `/health`: 200.
- `/ready`: 200, `applicationInitialized=true`, Chronos loaded on CPU.
- `/internal/forecast/status`: 200, engines listed: `CHRONOS_BOLT_ZERO_SHOT`, `OPEN_METEO_PROVIDER_FORECAST`, `PERSISTENCE_FALLBACK`, `UNAVAILABLE`.

Real-coordinate predict checks:

- Delhi `28.6139,77.2090`: 24h=133, 48h=106, 72h=89, engine `CHRONOS_BOLT_ZERO_SHOT`, history observations 169.
- Lucknow `26.8467,80.9462`: 24h=114, 48h=98, 72h=81, engine `CHRONOS_BOLT_ZERO_SHOT`, history observations 169.
- Mumbai `19.0760,72.8777`: 24h=59, 48h=60, 72h=60, engine `CHRONOS_BOLT_ZERO_SHOT`, history observations 169.

Deployment verification is not completed in this local session; no deployed Render/Vercel URLs were exercised here.
