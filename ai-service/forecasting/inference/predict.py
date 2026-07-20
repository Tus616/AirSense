from __future__ import annotations

import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd


def load_promoted(model_dir: str | Path, standard: str, horizon: int, location_key: str = None, candidate_scopes: list[str] | None = None):
    model_dir = Path(model_dir)
    matches = []
    for metadata_path in sorted(model_dir.glob("*.json")):
        if metadata_path.name in {"training_report.json", "dataset_quality.json"}:
            continue
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        
        # Check standard, horizon, and promotion status
        if (
            metadata.get("aqiStandard") == standard
            and int(metadata.get("horizonHours", 0)) == int(horizon)
            and metadata.get("promotionStatus") == "PROMOTED"
        ):
            matches.append(metadata)
    preferred_scopes = [scope for scope in (candidate_scopes or []) if scope]
    if not preferred_scopes:
        preferred_scopes = [scope for scope in [location_key, "GLOBAL_FULL_HISTORY", "GLOBAL_MEDIUM_HISTORY", "GLOBAL_SHORT_HISTORY", "GLOBAL_COLD_START"] if scope]
    if not preferred_scopes:
        preferred_scopes = ["GLOBAL_COLD_START"]
    for scope in preferred_scopes:
        scoped = [metadata for metadata in matches if metadata.get("modelScope") == scope]
        if scoped:
            metadata = sorted(scoped, key=lambda item: item.get("promotedAt") or "", reverse=True)[0]
            artifact = joblib.load(metadata["artifactPath"])
            return artifact["pipeline"], metadata
    return None, None


def predict(model_dir: str | Path, standard: str, horizon: int, features: dict, location_key: str = None, candidate_scopes: list[str] | None = None) -> dict:
    model, metadata = load_promoted(model_dir, standard, horizon, location_key, candidate_scopes)
    if model is None:
        return {"status": "ARTIFACT_UNAVAILABLE", "horizonHours": horizon, "fallbackReason": "MODEL_NOT_PROMOTED"}
        
    expected_schema = metadata.get("featureSchemaVersion")
    provided_schema = features.get("featureSchemaVersion")
    if not provided_schema or provided_schema != expected_schema:
        return {"status": "SCHEMA_MISMATCH", "horizonHours": horizon, "fallbackReason": "FEATURE_SCHEMA_MISMATCH"}
        
    required = metadata.get("requiredFeatures") or ["currentAqi"]
    missing_required = [feature for feature in required if features.get(feature) is None]
    if missing_required:
        return {"status": "SCHEMA_MISMATCH", "horizonHours": horizon, "fallbackReason": "FEATURE_SCHEMA_MISMATCH"}
        
    expected_features = metadata.get("features", [])
    frame = pd.DataFrame([{feature: features.get(feature) for feature in expected_features}])
    feature_diagnostics = feature_schema_diagnostics(metadata, frame, features)
    raw_value = float(model.predict(frame)[0])

    # If model was trained with delta target, the prediction is AQI(t+h) - AQI(t).
    # We must add currentAqi to get the absolute predicted AQI.
    current_aqi = features.get("currentAqi", 0) or 0
    if metadata.get("useDeltaTarget", False):
        value = current_aqi + raw_value
    else:
        value = raw_value
    unclamped_value = value
    value = max(0.0, min(500.0, value))
    residuals = metadata.get("residualQuantiles") or {}
    lower = value + residuals["p10"] if residuals.get("p10") is not None else None
    upper = value + residuals["p90"] if residuals.get("p90") is not None else None
    ood = ood_diagnostics(metadata, frame, raw_value)
    ood_level = ood_policy_level(metadata, ood)
    warnings = list(ood["warnings"])
    if abs(raw_value) > 100:
        warnings.append("EXTREME_FORECAST_CHANGE")
    if metadata.get("coverageLabel") in {"LIMITED_COVERAGE", "LIMITED_STATION_COVERAGE", "MULTI_STATION_COVERAGE"}:
        warnings.append("LOW_GENERALIZATION_CONFIDENCE")
    delta_percentiles = metadata.get("trainingDeltaPercentiles") or {}
    outside_delta = (
        delta_percentiles.get("p01") is not None and raw_value < delta_percentiles.get("p01")
    ) or (
        delta_percentiles.get("p99") is not None and raw_value > delta_percentiles.get("p99")
    )
    if abs(raw_value) > 100 and outside_delta:
        return {
            "status": "ARTIFACT_UNAVAILABLE",
            "horizonHours": horizon,
            "fallbackReason": "EXTREME_FORECAST_CHANGE",
            "predictedDelta": raw_value,
            "trainingDeltaPercentiles": delta_percentiles,
            "oodStatus": ood["status"],
            "oodScore": ood["score"],
            "oodLevel": ood_level,
            "oodFeatures": ood["outsideFeatures"],
            "warnings": sorted(set(warnings)),
            "featureDiagnostics": feature_diagnostics,
        }
    if ood_level == "OOD_HIGH" or (
        ood_level == "OOD_MEDIUM"
        and (metadata.get("heldOutValidationStatus") != "ROBUST" or metadata.get("generalizationStatus") == "LIMITED_STATION_COVERAGE")
    ):
        return {
            "status": "ARTIFACT_UNAVAILABLE",
            "horizonHours": horizon,
            "fallbackReason": "OUT_OF_DISTRIBUTION_FEATURES",
            "predictedDelta": raw_value,
            "trainingDeltaPercentiles": delta_percentiles,
            "oodStatus": ood["status"],
            "oodScore": ood["score"],
            "oodLevel": ood_level,
            "oodFeatures": ood["outsideFeatures"],
            "warnings": sorted(set(warnings + ["OUT_OF_DISTRIBUTION_FEATURES"])),
            "featureDiagnostics": feature_diagnostics,
            "modelContributions": model_contributions(model, metadata, frame),
        }
    scope = metadata.get("modelScope") or "GLOBAL"
    scope_label = "STATION_SPECIFIC_ML" if location_key and scope == location_key else "MULTI_STATION_GLOBAL_ML"
    engine_label = _engine_label(metadata.get("modelFamily", ""), scope_label)
    model_confidence = adjusted_confidence(confidence(metadata, features), ood_level)
    return {
        "status": "PROMOTED",
        "horizonHours": horizon,
        "predictedAqi": round(value),
        "predictedDelta": raw_value if metadata.get("useDeltaTarget", False) else raw_value - current_aqi,
        "unclampedPredictedAqi": unclamped_value,
        "lowerBound": round(max(0, lower)) if lower is not None else None,
        "upperBound": round(min(500, upper)) if upper is not None else None,
        "engine": engine_label,
        "forecastScope": scope_label,
        "modelScope": scope,
        "modelFamily": metadata.get("modelFamily"),
        "modelVersion": metadata["version"],
        "validationRmse": metadata.get("metrics", {}).get("rmse"),
        "baselineRmse": metadata.get("baselineMetrics", {}).get("rmse"),
        "baselinePredictedAqi": features.get("currentAqi"),
        "confidence": model_confidence,
        "confidenceLabel": "HIGH" if model_confidence >= 0.7 else "MEDIUM" if model_confidence >= 0.45 else "LOW",
        "trainingDeltaPercentiles": delta_percentiles,
        "oodStatus": ood["status"],
        "oodScore": ood["score"],
        "oodLevel": ood_level,
        "oodFeatures": ood["outsideFeatures"],
        "warnings": sorted(set(warnings)),
        "featureDiagnostics": feature_diagnostics,
        "modelContributions": model_contributions(model, metadata, frame),
    }


def confidence(metadata: dict, features: dict) -> float:
    metrics = metadata.get("metrics") or {}
    rmse = metrics.get("rmse") or 80.0
    completeness = sum(1 for feature in metadata.get("features", []) if features.get(feature) is not None) / max(1, len(metadata.get("features", [])))
    horizon_penalty = max(0, (int(metadata.get("horizonHours", 24)) / 24 - 1) * 0.06)
    return round(max(0.05, min(0.9, 0.85 - rmse / 300.0 + completeness * 0.12 - horizon_penalty)), 2)


def adjusted_confidence(base_confidence: float, ood_level: str) -> float:
    penalty = {"OOD_LOW": 0.05, "OOD_MEDIUM": 0.15, "OOD_HIGH": 0.35}.get(ood_level, 0.0)
    return round(max(0.05, min(0.9, base_confidence - penalty)), 2)


def _engine_label(model_family: str, scope_label: str = "MULTI_STATION_GLOBAL_ML") -> str:
    """Map model family to a canonical engine label."""
    prefix = "ML_STATION" if scope_label == "STATION_SPECIFIC_ML" else "ML_GLOBAL"
    mapping = {
        "XGBOOST": f"{prefix}_XGBOOST",
        "SKLEARN_HIST_GRADIENT_BOOSTING": f"{prefix}_HIST_GRADIENT_BOOSTING",
        "SKLEARN_RIDGE": f"{prefix}_RIDGE",
    }
    return mapping.get(model_family.upper(), f"{prefix}_{model_family.upper()}" if model_family else f"{prefix}_PROMOTED")


def feature_schema_diagnostics(metadata: dict, frame: pd.DataFrame, provided: dict) -> dict:
    expected = metadata.get("features", [])
    row = frame.iloc[0].to_dict() if not frame.empty else {}
    return {
        "expectedFeatureNames": expected,
        "generatedFeatureNames": list(row.keys()),
        "featureOrderMatches": list(row.keys()) == expected,
        "types": {feature: type(provided.get(feature)).__name__ if provided.get(feature) is not None else "null" for feature in expected},
        "nullValues": [feature for feature in expected if provided.get(feature) is None],
        "imputedDefaults": {feature: (metadata.get("imputerDefaults") or {}).get(feature) for feature in expected if provided.get(feature) is None},
        "measuredFeatureNames": [feature for feature in expected if provided.get(feature) is not None],
        "imputedFeatureNames": [feature for feature in expected if provided.get(feature) is None],
        "imputedFeatureCount": sum(1 for feature in expected if provided.get(feature) is None),
        "stationEncoding": "stationCode excluded for global models; latitude/longitude features retained",
        "latitude": provided.get("latitude"),
        "longitude": provided.get("longitude"),
        "stationLatitude": provided.get("stationLatitude"),
        "stationLongitude": provided.get("stationLongitude"),
        "aqiStandard": metadata.get("aqiStandard"),
    }


def ood_diagnostics(metadata: dict, frame: pd.DataFrame, predicted_delta: float) -> dict:
    stats = metadata.get("featureStats") or {}
    row = frame.iloc[0].to_dict() if not frame.empty else {}
    outside = []
    feature_details = []
    checked = 0
    for feature, value in row.items():
        if value is None or pd.isna(value):
            continue
        feature_stats = stats.get(feature) or {}
        p01 = feature_stats.get("p01")
        p99 = feature_stats.get("p99")
        if p01 is None or p99 is None:
            continue
        checked += 1
        numeric = float(value)
        if numeric < p01 or numeric > p99:
            outside.append(feature)
            distance = (p01 - numeric) / max(1e-9, abs(p99 - p01)) if numeric < p01 else (numeric - p99) / max(1e-9, abs(p99 - p01))
            feature_details.append({
                "feature": feature,
                "group": feature_group(feature),
                "trainingP01": p01,
                "trainingP99": p99,
                "liveValue": numeric,
                "normalizedDistance": float(distance),
            })
    delta = metadata.get("trainingDeltaPercentiles") or {}
    delta_outside = False
    if delta.get("p01") is not None and predicted_delta < delta["p01"]:
        delta_outside = True
    if delta.get("p99") is not None and predicted_delta > delta["p99"]:
        delta_outside = True
    score = len(outside) / max(1, checked)
    warnings = []
    if outside or delta_outside:
        warnings.append("OUT_OF_DISTRIBUTION_FEATURES")
    return {
        "status": "OUT_OF_DISTRIBUTION" if warnings else "IN_DISTRIBUTION",
        "score": round(score, 4),
        "outsideFeatures": outside,
        "featureDetails": feature_details,
        "predictedDeltaOutsideTrainingDistribution": delta_outside,
        "warnings": warnings,
    }


def ood_policy_level(metadata: dict, ood: dict) -> str:
    policy = metadata.get("oodPolicy") or {}
    low_max = float(policy.get("lowMaxScore", 0.05))
    medium_max = float(policy.get("mediumMaxScore", 0.15))
    if ood.get("predictedDeltaOutsideTrainingDistribution"):
        return "OOD_HIGH"
    score = float(ood.get("score") or 0.0)
    if score > medium_max:
        return "OOD_HIGH"
    if score > low_max or ood.get("outsideFeatures"):
        return "OOD_MEDIUM"
    return "OOD_LOW"


def feature_group(feature: str) -> str:
    if feature == "currentAqi":
        return "currentAqi"
    if feature in {"latitude", "longitude", "stationLatitude", "stationLongitude", "stationLatitudeFeature", "stationLongitudeFeature", "stationDistanceKm"}:
        return "latitude_longitude"
    if feature in {"pm25", "pm10", "no2", "so2", "co", "o3", "nh3", "pm25_pm10_ratio"}:
        return "pollutants"
    if feature.startswith("weather_") or feature in {"temperatureCelsius", "humidityPercent", "pressureHpa", "windSpeedMps", "windDirectionDegrees", "rainfallMm", "openWeatherAqiIndex"}:
        return "weather"
    if feature.startswith("cams_"):
        return "cams_defaults"
    if feature in {"hour", "dayOfWeek", "month", "isWeekend", "season", "hourSin", "hourCos", "dayOfWeekSin", "dayOfWeekCos", "monthSin", "monthCos"}:
        return "temporal"
    if feature in {"cityCode", "stateCode", "stationCode"}:
        return "city_state_station_encoding"
    return "other"


def model_contributions(model, metadata: dict, frame: pd.DataFrame) -> dict:
    if metadata.get("modelFamily") == "XGBOOST":
        try:
            import xgboost as xgb

            expected = metadata.get("features", [])
            imputer = model.named_steps["imputer"]
            transformed = imputer.transform(frame[expected])
            feature_names = list(imputer.get_feature_names_out(expected))
            booster = model.named_steps["model"].get_booster()
            contributions = booster.predict(
                xgb.DMatrix(transformed, feature_names=feature_names),
                pred_contribs=True,
            )[0]
            pairs = sorted(zip(feature_names, contributions[:-1]), key=lambda item: abs(item[1]), reverse=True)
            return {
                "bias": float(contributions[-1]),
                "topContributions": [{"feature": feature, "contribution": float(value)} for feature, value in pairs[:12]],
            }
        except Exception:
            return {}
    if metadata.get("modelFamily") != "SKLEARN_RIDGE":
        return {}
    try:
        expected = metadata.get("features", [])
        imputed = model.named_steps["imputer"].transform(frame[expected])
        transformed = model.named_steps["scaler"].transform(imputed)
        ridge = model.named_steps["model"]
        contributions = transformed[0] * ridge.coef_
        pairs = sorted(zip(expected, contributions), key=lambda item: abs(item[1]), reverse=True)
        return {
            "intercept": float(ridge.intercept_),
            "topContributions": [{"feature": feature, "contribution": float(value)} for feature, value in pairs[:12]],
        }
    except Exception:
        return {}
