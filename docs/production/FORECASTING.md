# Forecasting

The authoritative forecast endpoint is FastAPI `POST /internal/forecast/predict`, called by Spring `MlForecastClient`.

Engine priority:

1. `CHRONOS_BOLT_ZERO_SHOT` if `CHRONOS_ENABLED=true`, the model loads successfully, and enough same-standard history exists.
2. `OPEN_METEO_PROVIDER_FORECAST`.
3. `PERSISTENCE_FALLBACK`.
4. `UNAVAILABLE`.

Provider forecasts remain valid even when `fallbackReason` contains `CHRONOS_DISABLED` or another Chronos reason. Consumers must use the `engine` field, not `fallbackReason`, to determine forecast type.
