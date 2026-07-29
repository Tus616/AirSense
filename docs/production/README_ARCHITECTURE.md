# AirSense Production Architecture

AirSense production flow:

Location search -> current AQI and pollutants -> 24h/48h/72h forecast -> source attribution -> geospatial intelligence -> enforcement recommendations -> health advisory -> explainability -> Decision Copilot -> React dashboard.

The Spring Boot backend owns orchestration and persistence. The FastAPI AI service owns forecast inference/provider fallback. MongoDB stores observations, forecasts, and supporting operational data. The React/Vite frontend renders only backend-returned intelligence outputs.
