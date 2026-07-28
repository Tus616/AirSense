Production fallback chain per independent horizon:

1. `CHRONOS_BOLT_ZERO_SHOT`
2. `OPEN_METEO_PROVIDER_FORECAST`
3. `PERSISTENCE_FALLBACK`
4. `UNAVAILABLE`

Runtime history is timestamp-aware, same-standard AQI history. Open-Meteo uses `us_aqi`/`US_AQI` for the generic global provider path and is never mixed with India NAQI or OpenWeather 1-5 indexes.
