# AirSense Environmental Intelligence Platform

## Overview
A comprehensive platform for air-quality monitoring, forecasting, attribution, enforcement recommendation, and citizen advisory across multiple cities. The system combines real-time sensor ingestion, AI-driven attribution, hyper-local forecasts, and a rich UI for government officials, citizens, and public displays.

## Tech Stack
- **Backend**: Spring Boot (Java) with MongoDB
- **Frontend**: React (Vite) - Gov Dashboard, Citizen Dashboard, Kiosk display
- **AI**: Gemini integration plus a FastAPI/XGBoost forecasting service
- **Data**: Sensor streams, mobility feeds, industrial source records, construction permits, vulnerability mappings

## Architecture
- [Architecture guide](docs/architecture.md)
- [Mermaid source](docs/architecture-diagram.mmd)
- [Rendered diagram](docs/architecture-diagram.svg)

## Key Modules (Phases)
| Phase | Feature | Core Packages |
|------|----------|---------------|
| 8 | System Health & Metrics | `SystemMetricsService` |
| 9 | Source Attribution | `AttributionEngineService`, `AttributionResult` |
| 10 | Hyper-local Forecasts | `GridForecast`, `ForecastClient` |
| 11 | Enforcement Recommendations | `EnforcementIntelligenceService`, `EnforcementRecommendation` |
| 12 | Multi-City Dashboard | Added `cityId` fields & `Cities` collection |
| 13 | Citizen Health Risk Advisory | `CitizenRiskAdvisory`, `VulnerabilityMapping` |
| 14 | Evaluation Harness | `EvaluationHarnessService`, `EvaluationMetrics` |

## Getting Started
### Prerequisites
- JDK 21
- No global Maven installation required; use `backend/mvnw.cmd` on Windows
- Node.js (>=18) & npm
- MongoDB instance (local or remote)

### Ports
- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8082/api/v1`
- AI Service: `http://localhost:8000`

### Environment Files
- Frontend reads root `.env` with `VITE_API_BASE_URL`. Municipal GIS renders with Leaflet and OpenStreetMap tiles; no browser maps API key is required.
- Backend reads `backend/.env` with MongoDB, Gemini, OpenWeather, Earth Engine, feature flags, and AI service URL.
- AI Service reads `ai-service/.env` with MongoDB and FastAPI settings.
- Copy `.env.example`, `backend/.env.example`, and `ai-service/.env.example` when setting up a new machine.

### Backend
```bash
cd backend
mvnw.cmd -DskipTests compile
mvnw.cmd spring-boot:run
```
The API will be available at `http://localhost:8082/api/v1/`.

### AI Service
```bash
cd ai-service
venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
```

### Frontend
```bash
cd ..
npm install
npm run dev
```
Open `http://localhost:5173` in a browser.

## Development Guides
- **Adding a new city** - insert a document into the `Cities` collection (see `backend/src/main/java/com/airsense/api/entities/City.java`). Existing data will be backfilled automatically.
- **Creating a new enforcement rule** - extend `EnforcementIntelligenceService` and add the rule to the ranking logic.
- **Running the evaluation harness** - POST to `/api/v1/admin/evaluation/run` (requires ADMIN role).

## Contributing
1. Fork the repository
2. Create a feature branch
3. Ensure `backend/mvnw.cmd -DskipTests compile` and `npm run build` pass
4. Submit a Pull Request

## License
MIT - see `LICENSE` file.
