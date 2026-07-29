from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, model_validator
from pydantic_settings import BaseSettings as PydanticBaseSettings
from pydantic_settings import SettingsConfigDict
from typing import List, Optional
import uvicorn
import pymongo
from datetime import datetime, timedelta, timezone
import pandas as pd
import logging
from pathlib import Path

from data_prep import prepare_data, prepare_inference_features
from model import (
    train_models, load_models, predict_forecast,
    get_feature_importance, get_feature_importance_labeled,
)
from forecasting.inference.predict import discover_registry, predict as promoted_forecast_predict, resolve_model_dir
from forecasting.features.dataset import build_hourly_dataset
from forecasting.inference.chronos_service import ChronosService
from forecasting.inference.history import finite_number, prepare_aqi_history
from forecasting.inference.open_meteo_client import AQI_STANDARD as OPEN_METEO_AQI_STANDARD, OpenMeteoAirQualityClient


logger = logging.getLogger("airsense-ai")


class Settings(PydanticBaseSettings):
    mongodb_uri: str = "mongodb://localhost:27017/airsense"
    mongodb_database: str = "airsense"
    fastapi_host: str = "0.0.0.0"
    fastapi_port: int = 8000
    training_lookback_days: int = 30
    model_dir: str = "models"
    model_registry_path: Optional[str] = None
    ml_model_dir: Optional[str] = None
    live_history_lookback_hours: int = 168
    live_history_min_coverage_percent: float = 85.0
    live_history_max_gap_hours: float = 3.0
    live_history_max_age_minutes: float = 180.0
    live_history_small_gap_interpolation_hours: float = 1.0
    live_history_min_observations: int = 143
    live_history_min_hours: float = 168.0
    chronos_model_id: str = "amazon/chronos-bolt-tiny"
    chronos_device: str = "cpu"
    chronos_min_history_hours: int = 48
    chronos_context_hours: int = 168
    chronos_enabled: bool = True
    open_meteo_forecast_enabled: bool = True

    # CORS — comma-separated list of allowed origins.
    # Do NOT use '*' in production when allow_credentials=True.
    # Default: local dev origins only.
    cors_allowed_origins: str = "http://localhost:5173,http://localhost:5174"

    # Reload — MUST be False in production.
    # Set FASTAPI_RELOAD=true only in a local dev environment.
    fastapi_reload: bool = False

    model_config = SettingsConfigDict(
        env_file=(".env", "ai-service/.env"),
        extra="ignore",
    )


settings = Settings()
chronos_service = ChronosService()
open_meteo_client = OpenMeteoAirQualityClient()
STARTUP_STATE = {
    "applicationInitialized": False,
    "inferenceModuleImported": True,
    "modelRegistryDiagnostics": {},
    "startupWarnings": [],
    "providerClientConfigured": True,
}


def configured_model_dir() -> Path:
    return resolve_model_dir(settings.ml_model_dir or settings.model_dir)


# ---------------------------------------------------------------------------
# Lifespan (replaces deprecated @app.on_event)
# ---------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AI service model directory resolved to %s", configured_model_dir())
    STARTUP_STATE["modelRegistryDiagnostics"] = discover_registry(configured_model_dir(), load_models=False)
    STARTUP_STATE["startupWarnings"] = STARTUP_STATE["modelRegistryDiagnostics"].get("startupWarnings", [])
    try:
        loaded = load_models()
        logger.info("Legacy top-level model loaded on startup: %s", loaded)
    except Exception as exc:
        STARTUP_STATE["startupWarnings"].append("LEGACY_MODEL_LOAD_FAILED")
        logger.warning("Legacy top-level model load failed safely: %s", exc)
    STARTUP_STATE["applicationInitialized"] = True
    yield
    # Shutdown: nothing to clean up


app = FastAPI(
    title="AirSense AI Forecasting Service",
    version="1.0.0",
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# CORS — origins are loaded from the CORS_ALLOWED_ORIGINS environment variable.
# Wildcard '*' is rejected when allow_credentials=True to prevent credential leakage.
# ---------------------------------------------------------------------------
_raw_origins = settings.cors_allowed_origins
_allowed_origins = [o.strip() for o in _raw_origins.split(",") if o.strip()]

if "*" in _allowed_origins:
    # Guard: wildcard + credentials is a browser security violation.
    # Fall back to explicit localhost origins only and log a warning.
    logger.warning(
        "CORS_ALLOWED_ORIGINS contains '*' but allow_credentials=True. "
        "Falling back to http://localhost:5173 to prevent credential leakage. "
        "Set CORS_ALLOWED_ORIGINS to an explicit comma-separated origin list."
    )
    _allowed_origins = ["http://localhost:5173", "http://localhost:5174"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=_allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

# MongoDB Client
client = pymongo.MongoClient(settings.mongodb_uri)
db = client[settings.mongodb_database]
collection = db["sensor_data"]


# ---------------------------------------------------------------------------
# Request / Response Models
# ---------------------------------------------------------------------------
class PredictRequest(BaseModel):
    wardId: str
    sensorId: str
    gridId: Optional[str] = None


class BatchPredictRequest(BaseModel):
    """Payload for the batch predict endpoint used by the Spring Boot scheduler."""
    sensors: List[PredictRequest]


class HourlyPrediction(BaseModel):
    timestamp: str
    predictedAqi: Optional[int] = None
    predictedPm25: float
    category: str


class PredictResponse(BaseModel):
    wardId: str
    sensorId: str
    gridId: Optional[str] = None
    generatedAt: str
    horizonHours: int
    predictions: List[HourlyPrediction]
    modelVersion: str
    featureImportanceSummary: Optional[dict] = None
    seasonalMultiplier: float = 1.0
    fallbackUsed: bool = False
    status: str = "MODEL"
    explanation: Optional[str] = None

class AttributionRequest(BaseModel):
    wardId: str
    sensorId: str

class AttributionSource(BaseModel):
    source: str
    contributionPercentage: float
    confidenceScore: float

class AttributionResponse(BaseModel):
    wardId: str
    sensorId: str
    timestamp: str
    totalConfidence: float
    sources: List[AttributionSource]


class InternalForecastPredictionRequest(BaseModel):
    snapshotId: str
    locationKey: str
    searchedLocationKey: Optional[str] = None
    stationLocationKey: Optional[str] = None
    stationKey: Optional[str] = None
    forecastScope: Optional[str] = None
    candidateModelScopes: List[str] = Field(default_factory=list)
    stationName: Optional[str] = None
    stationLatitude: Optional[float] = None
    stationLongitude: Optional[float] = None
    latitude: Optional[float] = Field(default=None, ge=-90, le=90)
    longitude: Optional[float] = Field(default=None, ge=-180, le=180)
    aqiStandard: Optional[str] = None
    provider: Optional[str] = None
    forecastStandard: Optional[str] = None
    forecastIssueTime: Optional[str] = None
    providerObservedAt: Optional[str] = None
    currentAqi: Optional[int] = None
    horizons: List[int] = Field(default_factory=lambda: [24, 48, 72])
    features: dict = Field(default_factory=dict)
    history: List[dict] = Field(default_factory=list)
    observations: List[dict] = Field(default_factory=list)

    @model_validator(mode="after")
    def normalize_contract(self):
        if not self.forecastStandard:
            self.forecastStandard = self.aqiStandard or OPEN_METEO_AQI_STANDARD
        if self.aqiStandard is None:
            self.aqiStandard = self.forecastStandard
        if self.latitude is None and self.stationLatitude is not None:
            self.latitude = self.stationLatitude
        if self.longitude is None and self.stationLongitude is not None:
            self.longitude = self.stationLongitude
        if self.observations and not self.history:
            self.history = self.observations
        valid_horizons = {24, 48, 72}
        self.horizons = list(dict.fromkeys(self.horizons or [24, 48, 72]))
        invalid = [h for h in self.horizons if h not in valid_horizons]
        if invalid:
            raise ValueError(f"requested horizons must be one or more of 24, 48, 72; invalid={invalid}")
        return self


class InternalForecastPrediction(BaseModel):
    status: str
    horizonHours: int
    predictedAqi: Optional[int] = None
    predictedDelta: Optional[float] = None
    unclampedPredictedAqi: Optional[float] = None
    lowerBound: Optional[int] = None
    upperBound: Optional[int] = None
    engine: Optional[str] = None
    forecastScope: Optional[str] = None
    modelScope: Optional[str] = None
    modelFamily: Optional[str] = None
    modelVersion: Optional[str] = None
    confidence: Optional[float] = None
    confidenceLabel: Optional[str] = None
    baselinePredictedAqi: Optional[int] = None
    validationRmse: Optional[float] = None
    baselineRmse: Optional[float] = None
    fallbackReason: Optional[str] = None
    promotionStatus: Optional[str] = None
    aqiStandard: Optional[str] = None
    stationName: Optional[str] = None
    stationKey: Optional[str] = None
    stationLocationKey: Optional[str] = None
    snapshotId: Optional[str] = None
    historyObservationCount: Optional[int] = None
    historyCoverageHours: Optional[float] = None
    featureCoveragePercent: Optional[float] = None
    targetTime: Optional[str] = None
    dataOrigin: Optional[str] = None
    trainingDeltaPercentiles: Optional[dict] = None
    oodStatus: Optional[str] = None
    oodScore: Optional[float] = None
    oodLevel: Optional[str] = None
    oodFeatures: Optional[List[str]] = None
    warnings: List[str] = Field(default_factory=list)
    featureDiagnostics: Optional[dict] = None
    modelContributions: Optional[dict] = None
    provider: Optional[str] = None
    searchedLocationKey: Optional[str] = None
    locationKey: Optional[str] = None
    modelPromotionStatus: Optional[str] = None


class InternalForecastPredictionResponse(BaseModel):
    snapshotId: str
    locationKey: str
    forecastStandard: str
    generatedAt: str
    predictions: List[InternalForecastPrediction]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _fallback_prediction(horizon: int, reason: str) -> dict:
    status = "SCHEMA_MISMATCH" if reason == "FEATURE_SCHEMA_MISMATCH" else "ARTIFACT_UNAVAILABLE"
    return {
        "status": status,
        "horizonHours": horizon,
        "engine": "UNAVAILABLE",
        "fallbackReason": reason,
        "promotionStatus": "UNAVAILABLE",
        "dataOrigin": "UNAVAILABLE",
    }


def _confidence_label(confidence: float) -> str:
    if confidence >= 0.7:
        return "HIGH"
    if confidence >= 0.45:
        return "MEDIUM"
    return "LOW"


def _finite_int(value) -> Optional[int]:
    numeric = finite_number(value)
    if numeric is None:
        return None
    return int(round(max(0.0, min(500.0, numeric))))


def _forecast_prediction(
    request: InternalForecastPredictionRequest,
    *,
    horizon: int,
    generated_at: pd.Timestamp,
    predicted_aqi,
    lower_bound,
    upper_bound,
    engine: str,
    model_family: str,
    model_version: str,
    confidence: float,
    fallback_reason: Optional[str],
    provider: str,
    history,
    data_origin: str,
    station_or_location_key: str,
) -> InternalForecastPrediction:
    predicted = _finite_int(predicted_aqi)
    lower = _finite_int(lower_bound) if lower_bound is not None else None
    upper = _finite_int(upper_bound) if upper_bound is not None else None
    status = "UNAVAILABLE" if engine == "UNAVAILABLE" else "FORECAST"
    target_time = (generated_at + pd.Timedelta(hours=horizon)).isoformat().replace("+00:00", "Z")
    return InternalForecastPrediction(
        status=status,
        horizonHours=horizon,
        predictedAqi=predicted,
        lowerBound=lower,
        upperBound=upper,
        engine=engine,
        forecastScope=request.forecastScope or "COORDINATE_ZERO_SHOT",
        modelScope=request.forecastScope or "GLOBAL_COORDINATE",
        modelFamily=model_family,
        modelVersion=model_version,
        confidence=round(confidence, 2),
        confidenceLabel=_confidence_label(confidence),
        fallbackReason=fallback_reason,
        promotionStatus="NOT_APPLICABLE",
        modelPromotionStatus="NOT_APPLICABLE",
        aqiStandard=request.forecastStandard,
        stationName=request.stationName,
        stationKey=request.stationKey or station_or_location_key,
        stationLocationKey=request.stationLocationKey,
        snapshotId=request.snapshotId,
        historyObservationCount=history.observation_count,
        historyCoverageHours=history.coverage_hours,
        targetTime=target_time,
        dataOrigin=data_origin,
        provider=provider,
        searchedLocationKey=request.searchedLocationKey or request.locationKey,
        locationKey=request.locationKey,
        warnings=[],
    )


def _nearest_provider_forecast(provider_rows: list[dict], target_time: pd.Timestamp) -> tuple[float | None, str | None]:
    best = None
    best_delta = None
    for row in provider_rows or []:
        timestamp = pd.to_datetime(row.get("timestamp"), utc=True, errors="coerce")
        value = finite_number(row.get("currentAqi"))
        if pd.isna(timestamp) or value is None:
            continue
        delta = abs((timestamp - target_time).total_seconds())
        if best is None or delta < best_delta:
            best = (value, timestamp.isoformat().replace("+00:00", "Z"))
            best_delta = delta
    return best if best is not None else (None, None)


def _parse_issue_time(value: Optional[str]) -> pd.Timestamp:
    if value:
        try:
            return pd.Timestamp(value).tz_convert("UTC") if pd.Timestamp(value).tzinfo else pd.Timestamp(value, tz="UTC")
        except Exception:
            pass
    return pd.Timestamp.now(tz="UTC")


def _validate_live_history(request: InternalForecastPredictionRequest, history_df: pd.DataFrame, issue_time: pd.Timestamp, min_hours: float = 72.0) -> Optional[str]:
    provided_schema = (request.features or {}).get("featureSchemaVersion")
    if provided_schema and provided_schema != "forecasting-feature-schema-v2":
        return "FEATURE_SCHEMA_MISMATCH"
    if history_df.empty or "timestamp" not in history_df.columns or "currentAqi" not in history_df.columns:
        return "INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY"

    history_df["timestamp"] = pd.to_datetime(history_df["timestamp"], utc=True, errors="coerce")
    history_df = history_df.dropna(subset=["timestamp", "currentAqi"]).sort_values("timestamp")
    if request.forecastStandard and "aqiStandard" in history_df.columns:
        history_df = history_df[history_df["aqiStandard"].fillna(request.forecastStandard) == request.forecastStandard]

    model_scope_key = request.stationKey or request.stationLocationKey or request.locationKey
    if "locationKey" in history_df.columns:
        valid_keys = {k for k in [request.stationKey, request.stationLocationKey, request.locationKey, model_scope_key] if k}
        if valid_keys:
            history_df = history_df[history_df["locationKey"].isin(valid_keys)]

    history_df = history_df[history_df["timestamp"] <= issue_time]
    if history_df.empty:
        return "INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY"

    latest_age_minutes = (issue_time - history_df["timestamp"].max()).total_seconds() / 60.0
    if latest_age_minutes > settings.live_history_max_age_minutes:
        return "LIVE_HISTORY_STALE"

    earliest_allowed = issue_time - pd.Timedelta(hours=min_hours)
    recent = history_df[history_df["timestamp"] >= earliest_allowed].drop_duplicates(subset=["timestamp"])
    if len(recent) < 2:
        return "INSUFFICIENT_CONTIGUOUS_LIVE_HISTORY"

    span_hours = (recent["timestamp"].max() - recent["timestamp"].min()).total_seconds() / 3600.0
    coverage_percent = min(100.0, 100.0 * span_hours / max(1.0, min_hours))
    if coverage_percent < settings.live_history_min_coverage_percent:
        return "LIVE_HISTORY_COVERAGE_LOW"

    gaps = recent["timestamp"].diff().dropna().dt.total_seconds() / 3600.0
    if not gaps.empty and gaps.max() > settings.live_history_max_gap_hours:
        return "LIVE_HISTORY_GAP_TOO_LARGE"

    return None


def _scope_history_requirement(scope: str) -> Optional[float]:
    if not scope:
        return None
    if scope == "GLOBAL_COLD_START":
        return 0.0
    if scope == "GLOBAL_SHORT_HISTORY":
        return 1.0
    if scope == "GLOBAL_MEDIUM_HISTORY":
        return 72.0
    if scope == "GLOBAL_FULL_HISTORY" or scope == "GLOBAL":
        return 168.0
    return settings.live_history_min_hours


def _eligible_candidate_scopes(request: InternalForecastPredictionRequest, history_df: pd.DataFrame, issue_time: pd.Timestamp) -> list[str]:
    model_scope_key = request.stationKey or request.stationLocationKey or request.locationKey
    requested = [scope for scope in (request.candidateModelScopes or []) if scope]
    if not requested:
        requested = [model_scope_key, "GLOBAL_FULL_HISTORY", "GLOBAL_MEDIUM_HISTORY", "GLOBAL_SHORT_HISTORY", "GLOBAL_COLD_START"]
    eligible = []
    reasons = {}
    for scope in requested:
        requirement = _scope_history_requirement(scope)
        if requirement == 0.0:
            eligible.append(scope)
            continue
        reason = _validate_live_history(request, history_df.copy(), issue_time, requirement or settings.live_history_min_hours)
        if reason is None:
            eligible.append(scope)
        else:
            reasons[scope] = reason
    if not eligible:
        request.features["mlScopeEligibilityReasons"] = reasons
    return list(dict.fromkeys(eligible))


def get_category(aqi: int) -> str:
    if aqi <= 50:
        return "Good"
    if aqi <= 100:
        return "Satisfactory"
    if aqi <= 200:
        return "Moderate"
    if aqi <= 300:
        return "Poor"
    if aqi <= 400:
        return "Very Poor"
    return "Severe"


def _run_single_prediction(ward_id: str, sensor_id: str, grid_id: Optional[str] = None) -> PredictResponse:
    """
    Core prediction logic shared by both the single and batch endpoints.
    Fetches the last 48h of sensor history, runs inference, returns PredictResponse.
    """
    cutoff = datetime.utcnow() - timedelta(hours=48)
    cursor = collection.find(
        {"sensorId": sensor_id, "timestamp": {"$gte": cutoff}}
    ).sort("timestamp", 1)

    history = list(cursor)
    if not history:
        logger.warning(
            "Forecast unavailable reason=no_history sensor=%s ward=%s grid=%s",
            sensor_id,
            ward_id,
            grid_id,
        )
        return _unavailable_prediction(ward_id, sensor_id, grid_id, "No timestamped CPCB AQI history for this station.")

    if len(history) < 24:
        logger.warning(
            "Forecast unavailable reason=insufficient_history sensor=%s records=%s required=24",
            sensor_id,
            len(history),
        )
        return _unavailable_prediction(
            ward_id,
            sensor_id,
            grid_id,
            f"Only {len(history)} CPCB AQI records available; at least 24 hourly records are required.",
        )

    X_latest = prepare_inference_features(history)
    if X_latest.empty:
        raise HTTPException(status_code=400, detail="Failed to prepare inference features.")

    try:
        preds_aqi, preds_pm25 = predict_forecast(X_latest)
    except Exception as e:
        logger.warning(
            "Forecast unavailable reason=model_unavailable sensor=%s error=%s",
            sensor_id,
            str(e),
        )
        return _unavailable_prediction(ward_id, sensor_id, grid_id, "Trained AQI model is unavailable.")

    # Build prediction list
    last_timestamp = pd.to_datetime(history[-1]["timestamp"])
    predictions = []

    for i in range(72):
        pred_time = last_timestamp + timedelta(hours=i + 1)
        aqi_val = int(max(0, preds_aqi[i]))
        pm25_val = float(max(0, preds_pm25[i]))
        predictions.append(
            HourlyPrediction(
                timestamp=pred_time.isoformat() + "Z",
                predictedAqi=aqi_val,
                predictedPm25=round(pm25_val, 2),
                category=get_category(aqi_val),
            )
        )

    # Feature importance (labeled) for source attribution
    feat_imp = get_feature_importance_labeled()

    # Seasonal Multiplier (Phase 10)
    month = datetime.utcnow().month
    seasonal_mult = 1.0
    if month in [10, 11]:
        seasonal_mult = 1.2 # Stubble burning season
    elif month in [12, 1, 2]:
        seasonal_mult = 1.15 # Winter inversion

    return PredictResponse(
        wardId=ward_id,
        sensorId=sensor_id,
        gridId=grid_id,
        generatedAt=datetime.utcnow().isoformat() + "Z",
        horizonHours=72,
        predictions=predictions,
        modelVersion="xgboost-cpcb-aqi-v1",
        featureImportanceSummary=feat_imp if feat_imp else None,
        seasonalMultiplier=seasonal_mult,
        fallbackUsed=False,
        status="MODEL",
        explanation="XGBoost forecast trained against stored canonical CPCB Indian AQI snapshots.",
    )


def _run_persistence_prediction(ward_id: str, sensor_id: str, grid_id: Optional[str], latest: dict) -> PredictResponse:
    pollutants = latest.get("pollutants") or {}
    current_aqi = int(pollutants.get("aqi") or 0)
    current_pm25 = float(pollutants.get("pm25") or 0.0)
    last_timestamp = pd.to_datetime(latest.get("timestamp") or datetime.utcnow())

    predictions = []
    for i in range(72):
        pred_time = last_timestamp + timedelta(hours=i + 1)
        predictions.append(
            HourlyPrediction(
                timestamp=pred_time.isoformat() + "Z",
                predictedAqi=current_aqi,
                predictedPm25=round(current_pm25, 2),
                category=get_category(current_aqi),
            )
        )

    logger.info("Forecast fallback success sensor=%s horizonHours=72 modelVersion=persistence-fallback", sensor_id)
    return PredictResponse(
        wardId=ward_id,
        sensorId=sensor_id,
        gridId=grid_id,
        generatedAt=datetime.utcnow().isoformat() + "Z",
        horizonHours=72,
        predictions=predictions,
        modelVersion="persistence-fallback",
        featureImportanceSummary=get_feature_importance_labeled() or None,
        seasonalMultiplier=1.0,
        fallbackUsed=True,
        status="BASELINE",
        explanation="Persistence baseline only; not an AI-model prediction.",
    )


def _unavailable_prediction(ward_id: str, sensor_id: str, grid_id: Optional[str], reason: str) -> PredictResponse:
    return PredictResponse(
        wardId=ward_id,
        sensorId=sensor_id,
        gridId=grid_id,
        generatedAt=datetime.utcnow().isoformat() + "Z",
        horizonHours=72,
        predictions=[],
        modelVersion="unavailable-insufficient-cpcb-history",
        featureImportanceSummary=get_feature_importance_labeled() or None,
        seasonalMultiplier=1.0,
        fallbackUsed=False,
        status="UNAVAILABLE",
        explanation=reason,
    )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------
@app.get("/health")
def health_check():
    return {"status": "ok", "service": "ai-forecasting"}


@app.get("/ready")
def ready_check():
    diagnostics = discover_registry(configured_model_dir(), load_models=False)
    ready = STARTUP_STATE["applicationInitialized"] and STARTUP_STATE["inferenceModuleImported"]
    return {
        "status": "ready" if ready else "not_ready",
        "applicationInitialized": STARTUP_STATE["applicationInitialized"],
        "inferenceModuleImported": STARTUP_STATE["inferenceModuleImported"],
        "chronosPackageImported": chronos_service.load() if chronos_service.enabled else False,
        "chronosModelLoaded": chronos_service.loaded,
        "chronosStatus": chronos_service.status(),
        "providerClientConfigured": STARTUP_STATE["providerClientConfigured"],
        "fallbackEnginesAvailable": ["OPEN_METEO_PROVIDER_FORECAST", "PERSISTENCE_FALLBACK", "UNAVAILABLE"],
        "artifactDirectoryAccessible": diagnostics["artifactDirectoryAccessible"],
        "modelRegistryAccessible": diagnostics["artifactDirectoryAccessible"],
        "requiredPackagesImported": True,
        "startupWarnings": sorted(set(STARTUP_STATE.get("startupWarnings", []) + diagnostics.get("startupWarnings", []))),
    }


@app.get("/internal/forecast/status")
def internal_forecast_status():
    diagnostics = discover_registry(configured_model_dir(), load_models=False)
    chronos = chronos_service.status()
    return {
        "serviceStatus": "ready",
        "modelId": chronos["modelId"],
        "modelLoaded": chronos["modelLoaded"],
        "device": chronos["device"],
        "supportedHorizons": [24, 48, 72],
        "historyRequirements": {"minimumHours": chronos["minHistoryHours"], "preferredHours": chronos["contextHours"]},
        "providerFallbackAvailable": open_meteo_client.enabled,
        "lastModelError": chronos["lastModelError"],
        "loadedEngineNames": ["CHRONOS_BOLT_ZERO_SHOT", "OPEN_METEO_PROVIDER_FORECAST", "PERSISTENCE_FALLBACK", "UNAVAILABLE"],
        "availableHorizons": [24, 48, 72],
        "artifactDirectory": diagnostics["artifactDirectory"],
        "registryPath": str(Path(settings.model_registry_path).resolve()) if settings.model_registry_path else diagnostics["artifactDirectory"],
        "registryEntryCount": diagnostics["registryEntries"],
        "promotedModelCount": diagnostics["promotedModelCount"],
        "loadedModelCount": diagnostics["loadedModelCount"],
        "supportedModelFamilies": diagnostics["supportedModelFamilies"],
        "deterministicFallbackAvailable": True,
        "startupWarnings": sorted(set(STARTUP_STATE.get("startupWarnings", []) + diagnostics.get("startupWarnings", []))),
        "rejectedModelCount": diagnostics["rejectedModelCount"],
        "rejections": diagnostics["rejections"][:25],
    }


@app.post("/internal/forecast/predict", response_model=InternalForecastPredictionResponse)
def internal_forecast_predict(request: InternalForecastPredictionRequest):
    """
    Production live forecast path:
    Chronos-Bolt zero-shot -> Open-Meteo provider forecast -> persistence -> unavailable.
    Legacy promoted CPCB artifacts are intentionally not consulted here.
    """
    generated_at = _parse_issue_time(request.forecastIssueTime or request.providerObservedAt)
    station_or_location_key = request.stationKey or request.stationLocationKey or request.locationKey
    provider_rows: list[dict] = []
    provider_name = request.provider or "OPEN_METEO"
    history_rows = list(request.history or [])
    provider_error = None

    if (not history_rows or len(history_rows) < settings.chronos_min_history_hours or request.forecastStandard == OPEN_METEO_AQI_STANDARD) and request.latitude is not None and request.longitude is not None:
        try:
            provider_series = open_meteo_client.fetch(
                request.latitude,
                request.longitude,
                past_hours=settings.chronos_context_hours,
                forecast_hours=max(request.horizons) + 24,
            )
            provider_name = provider_series.provider
            provider_rows = provider_series.hourly_forecast
            if not history_rows:
                history_rows = provider_series.observations
                request.forecastStandard = provider_series.aqi_standard
                request.aqiStandard = provider_series.aqi_standard
        except Exception as exc:
            provider_error = f"OPEN_METEO_FETCH_FAILED: {type(exc).__name__}"

    history = prepare_aqi_history(
        history_rows,
        issue_time=generated_at.to_pydatetime(),
        aqi_standard=request.forecastStandard,
        max_small_gap_hours=settings.live_history_small_gap_interpolation_hours,
    )
    chronos_predictions, chronos_reason = chronos_service.predict(history.values, request.horizons)

    predictions = []
    for horizon in request.horizons:
        target_time = generated_at + pd.Timedelta(hours=horizon)
        chronos = chronos_predictions.get(horizon)
        if chronos is not None:
            predictions.append(_forecast_prediction(
                request,
                horizon=horizon,
                generated_at=generated_at,
                predicted_aqi=chronos.predicted_aqi,
                lower_bound=chronos.lower_bound,
                upper_bound=chronos.upper_bound,
                engine="CHRONOS_BOLT_ZERO_SHOT",
                model_family="CHRONOS_BOLT",
                model_version=chronos_service.model_id,
                confidence=max(0.45, 0.78 - (horizon / 24 - 1) * 0.08),
                fallback_reason=None,
                provider=provider_name,
                history=history,
                data_origin="PRETRAINED_ZERO_SHOT_MODEL",
                station_or_location_key=station_or_location_key,
            ))
            continue

        provider_value, provider_timestamp = _nearest_provider_forecast(provider_rows, target_time)
        if provider_value is not None and request.forecastStandard == OPEN_METEO_AQI_STANDARD:
            predictions.append(_forecast_prediction(
                request,
                horizon=horizon,
                generated_at=generated_at,
                predicted_aqi=provider_value,
                lower_bound=max(0.0, provider_value - 18.0),
                upper_bound=min(500.0, provider_value + 18.0),
                engine="OPEN_METEO_PROVIDER_FORECAST",
                model_family="PROVIDER_NUMERICAL_FORECAST",
                model_version="open-meteo-air-quality",
                confidence=max(0.35, 0.62 - (horizon / 24 - 1) * 0.07),
                fallback_reason=chronos_reason or "CHRONOS_UNAVAILABLE",
                provider=provider_name,
                history=history,
                data_origin=f"OPEN_METEO_PROVIDER_FORECAST:{provider_timestamp}",
                station_or_location_key=station_or_location_key,
            ))
            continue

        current_aqi = _finite_int(request.currentAqi)
        if current_aqi is not None and current_aqi > 0:
            reason_parts = [chronos_reason or "CHRONOS_UNAVAILABLE"]
            if provider_error:
                reason_parts.append(provider_error)
            elif request.forecastStandard != OPEN_METEO_AQI_STANDARD:
                reason_parts.append("PROVIDER_FORECAST_STANDARD_MISMATCH")
            else:
                reason_parts.append("PROVIDER_FORECAST_UNAVAILABLE")
            predictions.append(_forecast_prediction(
                request,
                horizon=horizon,
                generated_at=generated_at,
                predicted_aqi=current_aqi,
                lower_bound=max(0, current_aqi - 25),
                upper_bound=min(500, current_aqi + 25),
                engine="PERSISTENCE_FALLBACK",
                model_family="PERSISTENCE_BASELINE",
                model_version="persistence-v1",
                confidence=0.28,
                fallback_reason=";".join(reason_parts),
                provider=provider_name,
                history=history,
                data_origin="PERSISTENCE_BASELINE",
                station_or_location_key=station_or_location_key,
            ))
            continue

        predictions.append(_forecast_prediction(
            request,
            horizon=horizon,
            generated_at=generated_at,
            predicted_aqi=None,
            lower_bound=None,
            upper_bound=None,
            engine="UNAVAILABLE",
            model_family="UNAVAILABLE",
            model_version="unavailable",
            confidence=0.0,
            fallback_reason="CURRENT_AQI_UNAVAILABLE;" + (provider_error or chronos_reason or "FORECAST_UNAVAILABLE"),
            provider=provider_name,
            history=history,
            data_origin="UNAVAILABLE",
            station_or_location_key=station_or_location_key,
        ))
    return InternalForecastPredictionResponse(
        snapshotId=request.snapshotId,
        locationKey=request.searchedLocationKey or request.locationKey,
        forecastStandard=request.forecastStandard,
        generatedAt=datetime.utcnow().isoformat() + "Z",
        predictions=predictions,
    )


@app.post("/train")
def train_endpoint():
    """
    Pulls historical data, builds features, trains XGBoost models, and saves them.
    """
    cutoff_date = datetime.utcnow() - timedelta(days=settings.training_lookback_days)
    cursor = collection.find({"timestamp": {"$gte": cutoff_date}})
    data = list(cursor)

    if len(data) < 100:
        raise HTTPException(
            status_code=400,
            detail="Insufficient data in MongoDB to train model. Minimum 100 records required.",
        )

    df = pd.DataFrame(data)

    # Drop _id field if present
    if "_id" in df.columns:
        df = df.drop(columns=["_id"])

    X, y_aqi, y_pm25 = prepare_data(df, target_horizon=72)

    if X.empty:
        raise HTTPException(
            status_code=400,
            detail="Insufficient valid sequences after data preparation.",
        )

    metrics = train_models(X, y_aqi, y_pm25)

    return {
        "status": "success",
        "message": "Models trained successfully.",
        "metrics": metrics,
        "modelVersion": "1.0.0",
    }


@app.post("/predict", response_model=PredictResponse)
def predict_endpoint(request: PredictRequest):
    """
    Single-sensor/grid prediction. Fetches the last 48 hours for the requested
    sensor, runs inference, and returns a 72h forecast.
    """
    return _run_single_prediction(request.wardId, request.sensorId, request.gridId)


@app.post("/predict-batch", response_model=List[PredictResponse])
def predict_batch_endpoint(request: BatchPredictRequest):
    """
    Batch prediction endpoint — accepts a list of {wardId, sensorId} pairs.
    The Spring Boot scheduled job calls this once per cycle instead of N
    individual HTTP requests.

    Returns a list of PredictResponse objects. Individual failures are
    captured as error entries rather than failing the entire batch.
    """
    results: List[PredictResponse] = []
    errors = []

    for request_item in request.sensors:
        try:
            result = _run_single_prediction(request_item.wardId, request_item.sensorId, request_item.gridId)
            results.append(result)
        except HTTPException as e:
            errors.append({"sensorId": request_item.sensorId, "gridId": request_item.gridId, "error": e.detail})
            results.append(_unavailable_prediction(
                request_item.wardId,
                request_item.sensorId,
                request_item.gridId,
                str(e.detail),
            ))
        except Exception as e:
            errors.append({"sensorId": request_item.sensorId, "gridId": request_item.gridId, "error": str(e)})
            results.append(_unavailable_prediction(
                request_item.wardId,
                request_item.sensorId,
                request_item.gridId,
                str(e),
            ))

    if errors:
        logger.warning("Batch prediction completed with fallback_count=%s errors=%s", len(errors), errors)

    return results


@app.get("/feature-importance")
def feature_importance_endpoint():
    """Raw feature importance values (keyed by model feature names)."""
    imp = get_feature_importance()
    return {"feature_importance": imp}


@app.get("/feature-importance-labeled")
def feature_importance_labeled_endpoint():
    """Human-readable, grouped feature importance for source attribution."""
    imp = get_feature_importance_labeled()
    return {"feature_importance": imp}


@app.post("/attribution", response_model=AttributionResponse)
def attribution_endpoint(request: AttributionRequest):
    """
    Geospatial Pollution Source Attribution Engine.
    Uses ward context and historical data volume to determine dynamic 
    source attribution and confidence scores.
    """
    cutoff = datetime.utcnow() - timedelta(days=7)
    count = collection.count_documents({
        "sensorId": request.sensorId, 
        "timestamp": {"$gte": cutoff}
    })
    
    # Confidence scales with data density over the last 7 days (max ~168 hours)
    base_confidence = min(0.95, max(0.40, count / 168.0))
    
    # Dynamic weighting based on ward profile
    ward = request.wardId.upper()
    if "IND" in ward:
        weights = {"Industrial": 0.60, "Traffic": 0.25, "Construction": 0.10, "Biomass": 0.05}
    elif "HWY" in ward or "TRAFFIC" in ward:
        weights = {"Traffic": 0.70, "Industrial": 0.15, "Construction": 0.10, "Biomass": 0.05}
    elif "COM" in ward:
        weights = {"Traffic": 0.50, "Construction": 0.25, "Industrial": 0.15, "Biomass": 0.10}
    elif "RES" in ward:
        weights = {"Traffic": 0.40, "Biomass": 0.30, "Construction": 0.20, "Industrial": 0.10}
    else:
        weights = {"Traffic": 0.45, "Industrial": 0.25, "Biomass": 0.20, "Construction": 0.10}
        
    sources = []
    for src, w in weights.items():
        var = 0.0
        adj_w = max(0.01, w + var)
        sources.append({
            "source": src,
            "contributionPercentage": adj_w,
            "confidenceScore": base_confidence * (1 - abs(var))
        })
        
    # Normalize percentages
    total_w = sum(s["contributionPercentage"] for s in sources)
    for s in sources:
        s["contributionPercentage"] = round(s["contributionPercentage"] / total_w, 4)
        s["confidenceScore"] = round(s["confidenceScore"], 4)
        
    return AttributionResponse(
        wardId=request.wardId,
        sensorId=request.sensorId,
        timestamp=datetime.utcnow().isoformat() + "Z",
        totalConfidence=round(base_confidence, 4),
        sources=[AttributionSource(**s) for s in sources]
    )


if __name__ == "__main__":
    # reload is controlled by FASTAPI_RELOAD env var; defaults to False.
    # Never set to True in production or staging environments.
    uvicorn.run(
        "main:app",
        host=settings.fastapi_host,
        port=settings.fastapi_port,
        reload=settings.fastapi_reload,
    )
