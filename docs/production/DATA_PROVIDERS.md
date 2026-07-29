# Data Providers

Current AQI can come from CPCB CAAQMS or IQAir fallback selection. OpenWeather and geospatial providers enrich weather and map evidence where configured.

Open-Meteo supplies provider forecast horizons for low-memory production when Chronos is disabled or unavailable.

All provider secrets must come from environment variables, never committed files.
