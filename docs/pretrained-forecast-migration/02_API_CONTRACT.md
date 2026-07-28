Endpoint: `POST /internal/forecast/predict`.

Accepted location fields include `snapshotId`, `locationKey`, `searchedLocationKey`, `stationKey`, `stationLocationKey`, `latitude`, `longitude`, `currentAqi`, `forecastStandard` or `aqiStandard`, `providerObservedAt`, `forecastIssueTime`, `horizons`, and optional `history`/`observations`.

Each prediction returns horizon, AQI/range, engine, model family/version, confidence, fallback reason, target time, scope, location keys, AQI standard, provider, history counts, generated time, and data origin.
