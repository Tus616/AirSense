# Environmental Twin & Artificial Intelligence (ETAI) Architecture

## System Overview
ETAI is an advanced multi-agent Environmental Twin and command center designed to monitor, attribute, forecast, and manage hyper-local Air Quality Index (AQI) dynamics. The system aggregates cross-domain datasets (IoT sensors, satellite thermal data, mobility/traffic) and leverages an ensemble of specialized AI models to recommend enforcement actions and broadcast public health advisories.

## Runtime Services
- **Frontend:** React/Vite on `http://localhost:5173`, configured by root `.env`.
- **Backend API:** Spring Boot on `http://localhost:8082/api/v1`, configured by `backend/.env`.
- **AI Service:** FastAPI on `http://localhost:8000`, configured by `ai-service/.env`.
- **External APIs:** Leaflet renders OpenStreetMap tiles in the frontend. OpenWeather and Google Earth Engine are consumed by the backend with graceful fallbacks when credentials or upstream services are unavailable.

## The 4 Agents & Citizen System
The architecture is structured around specialized, decoupled intelligence units:

### 1. Attribution Engine (System 1)
- **Role:** Pinpoint the true source of elevated AQI (Traffic, Construction, Industrial, Meteorological).
- **Techniques:** XGBoost feature importance analysis fused with multi-modal anomaly cross-referencing (e.g., active construction permits, thermal hotspots).
- **Scale:** Computes on per-ward or per-grid bases.

### 2. Hyperlocal Forecasting (System 2)
- **Role:** Generate 24-72h predictive AQI and PM2.5 curves at a 1km grid resolution.
- **Techniques:** Inverse Distance Weighting (IDW) interpolation combined with XGBoost temporal forecasting, using seasonality, mobility, and wind vectors.
- **Evaluation:** Evaluates RMSE vs. a Persistence Baseline.

### 3. Enforcement Intelligence (System 3)
- **Role:** Generate and rank actionable municipal interventions (e.g., "Inspect Cement Plant 02", "Divert Heavy Vehicles in Ward 04").
- **Techniques:** Deterministic rule engine powered by Attribution Engine confidence scores and prioritized by population exposure risk. Gemini generates contextual, evidence-backed justifications.
- **Actionability:** Actions are tracked with status workflows (Open -> In Progress -> Resolved).

### 4. Policy Simulation (System 4 / Multi-City)
- **Role:** Evaluate hypothetical scenarios (e.g., Odd-Even traffic rationing) via an AI-driven agent debate. 
- **Techniques:** Multi-agent dialogue using LLMs acting as distinct stakeholders (Health Officer, Traffic Control, Environmental Agency), concluding with risk tradeoffs and impact scores.
- **Multi-City:** Normalizes schemas to compare metrics (AQI burden, compliance rate, forecasting error) across regional deployments (Delhi, Mumbai, Bengaluru).

### 5. Citizen Health Advisory (System 5)
- **Role:** Dispatch personalized, multi-lingual risk advisories to the public based on vulnerable health profiles and exact grid proximity.
- **Techniques:** Risk scoring (0-100) combining base AQI, ward-level hospital/school density, and individual citizen conditions (Asthma, COPD).
- **Channels:** Generates content for In-App, SMS, Email, Push, and public Kiosk displays.

## Data Flow
1. **Ingestion (Cron/Scheduled):** Sensor metrics, traffic density, thermal anomalies, and permits are pulled into MongoDB.
2. **Analysis:** Attribution and Forecast models execute asynchronously to populate `AttributionResults` and `Predictions`.
3. **Action Generation:** The Enforcement Intelligence agent runs post-forecast to rank necessary interventions.
4. **Advisory Generation:** The Citizen Health system periodically maps vulnerable users against the nearest predicted grid spikes.
5. **Evaluation Harness:** A continuous synthetic benchmarking suite validates model precision and system latency.

## Evaluation Metrics (Phase 14)
The integrated harness measures system readiness across 5 dimensions:
- **Attribution Accuracy:** F1 score vs synthetic ground-truth events.
- **Forecast Quality:** Model RMSE % improvement over persistence baselines.
- **Enforcement Quality Score:** 0-100 rubric evaluating action specificity, evidence citation, and target validity.
- **Advisory Relevance:** Hit rate of advisories appropriately addressing high-risk predictions across required regional languages.
- **System Latency:** Average Signal-to-Intervention MS.

## Scalability Notes
- **Geo-Spatial Queries:** Grid and permit lookups leverage MongoDB `2dsphere` indexes.
- **Microservices Path:** Forecasting is decoupled, enabling transition to dedicated Python/FastAPI ML containers.
- **Async Processing:** Scheduled tasks process batches by city, avoiding real-time inference bottlenecks on user requests.
- **Idempotent Upserts:** Data seeders and ingestors prevent duplicates during node scaling.

## Demo Script
1. Admin Dashboard -> Review Map and City Stats.
2. City Compare -> Observe Delhi vs. Mumbai trends.
3. System Health -> Check Gemini latency and XGBoost model health.
4. **Evaluation Harness -> Click "Run Evaluation" and review 5-point quality scores.**
5. Map -> View hyper-local 1km grids.
6. Enforcement -> View AI-ranked actions, review cross-referenced evidence, mark as In Progress.
7. Simulations -> Run an Odd-Even debate scenario.
8. Citizen App -> Toggle health vulnerabilities and observe personalized risk translation.

## Limitations & Prototype Assumptions
- Real-time telemetric streams are simulated via idempotent synthetic seeders.
- True ground truth for attribution is physically impossible to attain perfectly; synthetic events substitute for evaluation.
- SMS/Email/IVR channels use placeholder stdout logs; no actual Twilio/SendGrid telephony is integrated.
- LLM translation is dependent on Gemini API uptime; robust static templates fallback if the service is unreachable.
