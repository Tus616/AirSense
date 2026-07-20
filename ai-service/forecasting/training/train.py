from __future__ import annotations

import argparse
import json
import os
import math
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import (
    ExtraTreesRegressor,
    HistGradientBoostingRegressor,
    RandomForestRegressor,
)
from sklearn.impute import SimpleImputer
from sklearn.linear_model import ElasticNet, HuberRegressor, Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from forecasting.data.common import mongo_database, write_json
from forecasting.evaluation.metrics import (
    MetricResult,
    evaluate_predictions,
    persistence_predictions,
    seasonal_persistence_predictions,
    promotion_status,
)
from forecasting.features.dataset import (
    HORIZONS,
    build_hourly_dataset,
    per_station_chronological_split,
)

MODEL_FAMILIES = {
    "HGB": ("SKLEARN_HIST_GRADIENT_BOOSTING", lambda: HistGradientBoostingRegressor(
        max_iter=300, learning_rate=0.05, max_depth=6, l2_regularization=0.1,
        min_samples_leaf=20, random_state=42)),
    "RIDGE": ("SKLEARN_RIDGE", lambda: Ridge(alpha=25.0)),
    "HUBER": ("SKLEARN_HUBER", lambda: HuberRegressor(epsilon=1.35, alpha=0.01, max_iter=300)),
}

GLOBAL_TIERS = ("GLOBAL_COLD_START", "GLOBAL_SHORT_HISTORY", "GLOBAL_MEDIUM_HISTORY", "GLOBAL_FULL_HISTORY")


def feature_columns_for_tier(feature_cols: list[str], scope: str) -> list[str]:
    if scope == "GLOBAL_FULL_HISTORY" or not scope.startswith("GLOBAL_"):
        return feature_cols
    cold = []
    short = []
    medium = []
    short_tokens = ("_lag_1h", "_lag_3h", "_lag_6h", "_lag_12h", "_lag_24h", "_change_1h", "_change_3h", "_change_6h", "_change_24h",
                    "roll_3h", "roll_6h", "roll_12h", "roll_24h", "missing_3h", "missing_6h", "missing_12h", "missing_24h",
                    "prev_day")
    medium_tokens = short_tokens + ("_lag_48h", "_lag_72h", "roll_48h", "roll_72h")
    history_markers = ("_lag_", "_change_", "roll_", "missing_", "prev_day", "prev_week")
    for col in feature_cols:
        if scope.startswith("GLOBAL_") and col == "stationCode":
            # stationCode is an ordinal value derived from the training set order.
            # It is not stable at runtime for unseen/sparse stations.
            continue
        if not any(marker in col for marker in history_markers):
            cold.append(col)
        if any(token in col for token in short_tokens) or not any(marker in col for marker in history_markers):
            short.append(col)
        if any(token in col for token in medium_tokens) or not any(marker in col for marker in history_markers):
            medium.append(col)
    if scope == "GLOBAL_COLD_START":
        return cold
    if scope == "GLOBAL_SHORT_HISTORY":
        return short
    if scope == "GLOBAL_MEDIUM_HISTORY":
        return medium
    return feature_cols


def pipeline_for(family_key: str, model_factory):
    steps = [("imputer", SimpleImputer(strategy="median"))]
    if family_key in {"RIDGE", "HUBER"}:
        steps.append(("scaler", StandardScaler()))
    steps.append(("model", model_factory()))
    return Pipeline(steps)


def sparse_cold_start_frame(frame: pd.DataFrame, feature_cols: list[str], mask_name: str) -> pd.DataFrame:
    masked = frame.copy()
    weather_cols = [col for col in feature_cols if col.startswith("weather_") or col in {
        "temperatureCelsius", "humidityPercent", "pressureHpa", "windSpeedMps",
        "windDirectionDegrees", "rainfallMm", "openWeatherAqiIndex"
    }]
    pollutant_cols = ["pm25", "pm10", "no2", "so2", "co", "o3", "nh3", "pm25_pm10_ratio"]
    if mask_name in {"one_observation", "four_observations", "missing_weather"}:
        for col in weather_cols:
            if col in masked.columns:
                masked[col] = np.nan
    if mask_name in {"one_observation", "four_observations", "partial_pollutants"}:
        for col in pollutant_cols:
            if col in masked.columns and col not in {"pm25", "pm10"}:
                masked[col] = np.nan
    for col in feature_cols:
        if "_lag_" in col or "_change_" in col or "roll_" in col or "prev_day" in col or "prev_week" in col:
            masked[col] = np.nan
    return masked


def pct_abs_change_over_100(predicted: pd.Series, current: pd.Series) -> float:
    frame = pd.DataFrame({"predicted": predicted, "current": current}).dropna()
    if frame.empty:
        return 0.0
    return float(((frame["predicted"] - frame["current"]).abs() > 100).mean() * 100.0)


def sparse_metrics(actual, predicted, current) -> dict:
    metrics = evaluate_predictions(pd.Series(actual), pd.Series(predicted))
    return {
        **metrics.__dict__,
        "p90_absolute_error": float((pd.Series(predicted).reset_index(drop=True) - pd.Series(actual).reset_index(drop=True)).abs().quantile(0.9)),
        "pct_change_gt_100": pct_abs_change_over_100(pd.Series(predicted), pd.Series(current)),
    }


def feature_stats_from_frame(frame: pd.DataFrame, feature_cols: list[str]) -> dict:
    stats = {}
    for col in feature_cols:
        series = pd.to_numeric(frame[col], errors="coerce") if col in frame.columns else pd.Series(dtype=float)
        non_null = series.dropna()
        stats[col] = {
            "nonNullCount": int(non_null.count()),
            "nullCount": int(series.isna().sum()),
            "median": float(non_null.median()) if not non_null.empty else None,
            "p01": float(non_null.quantile(0.01)) if not non_null.empty else None,
            "p05": float(non_null.quantile(0.05)) if not non_null.empty else None,
            "p95": float(non_null.quantile(0.95)) if not non_null.empty else None,
            "p99": float(non_null.quantile(0.99)) if not non_null.empty else None,
        }
    return stats


def ood_rate_against_stats(frame: pd.DataFrame, feature_cols: list[str], stats: dict) -> float:
    if frame.empty:
        return 0.0
    outside_rows = 0
    checked_rows = 0
    for _, row in frame[feature_cols].iterrows():
        row_checked = False
        row_outside = False
        for feature in feature_cols:
            value = row.get(feature)
            if value is None or pd.isna(value):
                continue
            feature_stats = stats.get(feature) or {}
            p01 = feature_stats.get("p01")
            p99 = feature_stats.get("p99")
            if p01 is None or p99 is None:
                continue
            row_checked = True
            numeric = float(value)
            if numeric < p01 or numeric > p99:
                row_outside = True
        if row_checked:
            checked_rows += 1
            if row_outside:
                outside_rows += 1
    return float(outside_rows / max(1, checked_rows))


def heldout_failures(station_grouped_evaluation: dict, minimum_samples: int, max_bias: float) -> list[dict]:
    failures = []
    important_min_rows = max(1000, minimum_samples)
    for station, evaluation in station_grouped_evaluation.items():
        if int(evaluation.get("testRows") or 0) < important_min_rows:
            continue
        model = evaluation.get("metrics") or {}
        baseline = evaluation.get("persistenceBaseline") or {}
        model_mae = model.get("mae")
        baseline_mae = baseline.get("mae")
        model_rmse = model.get("rmse")
        baseline_rmse = baseline.get("rmse")
        bias = abs(model.get("mean_bias") or 0.0)
        failed = False
        reasons = []
        if model_mae is not None and baseline_mae is not None and model_mae > baseline_mae:
            failed = True
            reasons.append("MAE_BASELINE_BETTER")
        if model_rmse is not None and baseline_rmse is not None and model_rmse > baseline_rmse:
            failed = True
            reasons.append("RMSE_BASELINE_BETTER")
        if bias > max_bias:
            failed = True
            reasons.append("BIAS_TOO_HIGH")
        if failed:
            failures.append({
                "station": station,
                "city": evaluation.get("city"),
                "testRows": evaluation.get("testRows"),
                "reasons": reasons,
                "modelMae": model_mae,
                "persistenceMae": baseline_mae,
                "modelRmse": model_rmse,
                "persistenceRmse": baseline_rmse,
                "bias": model.get("mean_bias"),
            })
    return failures


def metric_result_from_dict(values: dict) -> MetricResult:
    return MetricResult(
        sample_count=int(values.get("sample_count") or 0),
        mae=values.get("mae"),
        rmse=values.get("rmse"),
        mean_bias=values.get("mean_bias"),
        median_absolute_error=values.get("median_absolute_error"),
        category_accuracy=values.get("category_accuracy"),
        interval_coverage=values.get("interval_coverage"),
    )


def runtime_feature_frame(scope: str, frame: pd.DataFrame, feature_cols: list[str], mask_name: str = "one_observation") -> pd.DataFrame:
    if scope == "GLOBAL_COLD_START":
        return sparse_cold_start_frame(frame, feature_cols, mask_name)
    return frame


def scope_metadata(scope: str) -> tuple[str, str, str | None, int]:
    if scope.startswith("GLOBAL_"):
        required = {
            "GLOBAL_COLD_START": 0,
            "GLOBAL_SHORT_HISTORY": 1,
            "GLOBAL_MEDIUM_HISTORY": 72,
            "GLOBAL_FULL_HISTORY": 168,
        }.get(scope, 168)
        return "MULTI_STATION_GLOBAL_ML", "GLOBAL", None, required
    return "STATION_SPECIFIC_ML", "STATION", scope, 168


# Try optional boosting libraries
try:
    from xgboost import XGBRegressor
    MODEL_FAMILIES["XGB"] = ("XGBOOST", lambda: XGBRegressor(
        n_estimators=450, learning_rate=0.03, max_depth=3, min_child_weight=8,
        reg_lambda=8.0, reg_alpha=0.5, subsample=0.75, colsample_bytree=0.75,
        objective="reg:squarederror", random_state=42, verbosity=0))
except ImportError:
    pass

try:
    from lightgbm import LGBMRegressor
    MODEL_FAMILIES["LGBM"] = ("LIGHTGBM", lambda: LGBMRegressor(
        n_estimators=300, learning_rate=0.05, max_depth=6, reg_lambda=1.0,
        subsample=0.8, colsample_bytree=0.8, random_state=42, verbose=-1))
except ImportError:
    pass


def load_from_mongo(standard: str = "INDIA_NAQI") -> tuple[pd.DataFrame, pd.DataFrame]:
    db = mongo_database()
    aqi = pd.DataFrame(list(db["aqi_historical_snapshots"].find({"aqiStandard": standard}, {"_id": 0})))
    weather = pd.DataFrame(list(db["historical_weather_observations"].find({}, {"_id": 0})))
    return aqi, weather


def train(config: dict) -> dict:
    standard = config.get("aqiStandard", "INDIA_NAQI")
    if config.get("aqiRecords") is not None:
        aqi = pd.DataFrame(config.get("aqiRecords") or [])
        weather = pd.DataFrame(config.get("weatherRecords") or [])
    elif config.get("aqiCsv"):
        aqi = pd.read_csv(config["aqiCsv"])
        weather = pd.read_csv(config["weatherCsv"]) if config.get("weatherCsv") else pd.DataFrame()
    else:
        aqi, weather = load_from_mongo(standard)

    bundle = build_hourly_dataset(aqi, weather)
    out_dir = Path(config.get("modelDir", "ai-service/forecasting/models"))
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json(out_dir / "dataset_quality.json", bundle.quality_report)

    minimum_samples = int(config.get("minSamples", os.getenv("ML_MODEL_PROMOTION_MIN_SAMPLES", 200)))
    min_improvement = float(config.get("minRmseImprovementPercent", os.getenv("ML_MODEL_PROMOTION_MIN_RMSE_IMPROVEMENT_PERCENT", 5)))
    max_bias = float(config.get("maxAbsoluteBias", os.getenv("ML_MODEL_MAX_ABSOLUTE_BIAS", 20)))
    use_delta = config.get("useDeltaTarget", True)
    configured_horizons = tuple(int(h) for h in config.get("horizons", HORIZONS))

    results = {"datasetChecksum": bundle.checksum, "quality": bundle.quality_report, "scopes": {}}

    if bundle.frame.empty:
        for tier in GLOBAL_TIERS:
            results["scopes"][tier] = {"promotionStatus": "INSUFFICIENT_DATA", "reason": "NO_ROWS"}
        write_json(out_dir / "training_report.json", results)
        return results

    from forecasting.features.dataset import per_station_walk_forward_splits
    from forecasting.evaluation.metrics import (
        previous_week_persistence_predictions,
        trend_persistence_predictions,
        seasonal_rolling_median_predictions
    )
    
    # We define scopes: pooled tiered GLOBAL models + individual station identities.
    scope_column = "stationIdentity" if "stationIdentity" in bundle.frame.columns else "locationKey"
    configured_scopes = config.get("scopes")
    scopes = configured_scopes if configured_scopes else list(GLOBAL_TIERS) + sorted(bundle.frame[scope_column].dropna().unique().tolist())
    rows_per_station = {str(k): int(v) for k, v in bundle.frame.groupby(scope_column).size().items()}
    results["trainingRowsPerStation"] = rows_per_station
    robust_station_count = sum(1 for value in rows_per_station.values() if value >= 1000)
    training_cities = sorted([str(city) for city in bundle.frame["city"].dropna().unique().tolist()]) if "city" in bundle.frame.columns else []
    results["coverageLabel"] = "LIMITED_STATION_COVERAGE" if robust_station_count < 10 else "MULTI_STATION_COVERAGE"
    results["trainingStationCount"] = int(robust_station_count)
    results["trainingCities"] = training_cities
    results["coverageScope"] = results["coverageLabel"]
    results["generalizationStatus"] = results["coverageLabel"]
    
    for scope in scopes:
        results["scopes"][scope] = {"horizons": {}}
        scope_df = bundle.frame if scope.startswith("GLOBAL_") else bundle.frame[bundle.frame[scope_column] == scope]
        if scope_df.empty:
            continue
            
        print(f"\n=== SCOPE: {scope} ===")
        # 3 splits
        splits = per_station_walk_forward_splits(scope_df, n_splits=3, train_frac=0.7, val_frac=0.15, test_frac=0.15)
        if not splits:
            print(f"  Insufficient data for walk-forward splits in {scope}")
            continue
            
        print("  Splits:")
        for idx, (tr, va, te) in enumerate(splits):
            print(f"    Fold {idx}: Train={len(tr)} Val={len(va)} Test={len(te)}")

        feature_cols = feature_columns_for_tier(bundle.feature_columns, scope)
        results["scopes"][scope]["featureCount"] = len(feature_cols)

        for horizon in configured_horizons:
            print(f"  --- {horizon}h ---")
            abs_target = bundle.target_columns[horizon]
            delta_target = bundle.delta_target_columns[horizon]
            target_col = delta_target if use_delta else abs_target

            # Walk forward model comparison
            best_val_rmse = float("inf")
            best_family = None
            model_comparison = {}
            
            for family_key, (family_name, model_factory) in MODEL_FAMILIES.items():
                fold_val_rmses = []
                fold_test_rmses = []
                failed = False
                for tr, va, te in splits:
                    tr_h = tr.dropna(subset=[target_col])
                    va_h = va.dropna(subset=[target_col])
                    
                    if len(tr_h) < 50 or len(va_h) < 10:
                        failed = True; break
                        
                    try:
                        pipeline = pipeline_for(family_key, model_factory)
                        tr_features = runtime_feature_frame(scope, tr_h, feature_cols)
                        va_features = runtime_feature_frame(scope, va_h, feature_cols)
                        pipeline.fit(tr_features[feature_cols], tr_h[target_col])
                        
                        v_pred_raw = pipeline.predict(va_features[feature_cols])
                        v_pred = (va_h["currentAqi"] + v_pred_raw).clip(0, 500) if use_delta else np.clip(v_pred_raw, 0, 500)
                        
                        v_rmse = float(math.sqrt(((v_pred - va_h[abs_target]) ** 2).mean()))
                        fold_val_rmses.append(v_rmse)
                        
                    except Exception as e:
                        print(f"      Model {family_key} failed on fold {idx}: {e}")
                        failed = True; break
                        
                if not failed and fold_val_rmses:
                    avg_val_rmse = sum(fold_val_rmses) / len(fold_val_rmses)
                    model_comparison[family_key] = {"family": family_name, "avg_val_rmse": avg_val_rmse}
                    print(f"    {family_key}: avg_val_RMSE={avg_val_rmse:.1f}")
                    if avg_val_rmse < best_val_rmse:
                        best_val_rmse = avg_val_rmse
                        best_family = family_key
                        
            if not best_family:
                results["scopes"][scope]["horizons"][str(horizon)] = {"promotionStatus": "ALL_MODELS_FAILED"}
                continue
                
            print(f"    BEST: {best_family} (val_RMSE={best_val_rmse:.1f})")
            
            # Now evaluate best model across test folds to assess stability
            test_metrics_list = []
            base_persist_list, base_seasonal_list, base_week_list, base_trend_list = [], [], [], []
            family_name, model_factory = MODEL_FAMILIES[best_family]
            
            test_rmses = []
            final_model = None
            
            # 1. Walk-forward testing for metrics
            for idx, (tr, va, te) in enumerate(splits):
                tr_full = pd.concat([tr, va]).dropna(subset=[target_col])
                te_h = te.dropna(subset=[target_col])
                if len(te_h) < minimum_samples // 3:
                    continue
                    
                pipeline = pipeline_for(best_family, model_factory)
                tr_features = runtime_feature_frame(scope, tr_full, feature_cols)
                te_features = runtime_feature_frame(scope, te_h, feature_cols)
                pipeline.fit(tr_features[feature_cols], tr_full[target_col])
                
                t_pred_raw = pipeline.predict(te_features[feature_cols])
                t_pred = (te_h["currentAqi"] + t_pred_raw).clip(0, 500) if use_delta else np.clip(t_pred_raw, 0, 500)
                actual = te_h[abs_target]
                
                m = evaluate_predictions(actual, t_pred)
                test_rmses.append(m.rmse)
                test_metrics_list.append(m)
                
                base_persist_list.append(evaluate_predictions(actual, persistence_predictions(te_h)))
                base_seasonal_list.append(evaluate_predictions(actual, seasonal_persistence_predictions(te_h, horizon)))
                base_week_list.append(evaluate_predictions(actual, previous_week_persistence_predictions(te_h)))
                base_trend_list.append(evaluate_predictions(actual, trend_persistence_predictions(te_h)))
                
                if idx == len(splits) - 1:
                    final_model = pipeline # We save the model trained on the most recent train+val split
            
            if not test_metrics_list:
                results["scopes"][scope]["horizons"][str(horizon)] = {"promotionStatus": "INSUFFICIENT_DATA"}
                continue
                
            # Average metrics across folds
            def avg_metrics(m_list):
                valid = [m for m in m_list if m.rmse is not None]
                if not valid: return m_list[0]
                sc = sum(m.sample_count for m in valid)
                return evaluate_predictions(
                    pd.Series(np.concatenate([np.zeros(m.sample_count) for m in valid])),
                    pd.Series(np.concatenate([np.full(m.sample_count, m.rmse) for m in valid]))
                ) # Wait, it's easier to just compute a weighted average. 
                
            # Better way: we concat the predictions and actuals from all test folds!
            all_actuals, all_preds, all_station_labels, all_city_labels = [], [], [], []
            base_p, base_s, base_w, base_t = [], [], [], []
            
            for tr, va, te in splits:
                tr_full = pd.concat([tr, va]).dropna(subset=[target_col])
                te_h = te.dropna(subset=[target_col])
                if len(te_h) < 10: continue
                
                pipeline = pipeline_for(best_family, model_factory)
                tr_features = runtime_feature_frame(scope, tr_full, feature_cols)
                te_features = runtime_feature_frame(scope, te_h, feature_cols)
                pipeline.fit(tr_features[feature_cols], tr_full[target_col])
                
                t_pred_raw = pipeline.predict(te_features[feature_cols])
                t_pred = (te_h["currentAqi"] + t_pred_raw).clip(0, 500) if use_delta else np.clip(t_pred_raw, 0, 500)
                all_actuals.extend(te_h[abs_target].tolist())
                all_preds.extend(t_pred.tolist())
                all_station_labels.extend(te_h[scope_column].astype(str).tolist())
                all_city_labels.extend(te_h["city"].fillna("").astype(str).tolist() if "city" in te_h.columns else [""] * len(te_h))
                
                base_p.extend(persistence_predictions(te_h).tolist())
                base_s.extend(seasonal_persistence_predictions(te_h, horizon).tolist())
                base_w.extend(previous_week_persistence_predictions(te_h).tolist())
                base_t.extend(trend_persistence_predictions(te_h).tolist())
            
            if len(all_actuals) < minimum_samples:
                results["scopes"][scope]["horizons"][str(horizon)] = {"promotionStatus": "INSUFFICIENT_DATA"}
                continue
                
            overall_actuals = pd.Series(all_actuals)
            model_metrics = evaluate_predictions(overall_actuals, pd.Series(all_preds))
            m_p = evaluate_predictions(overall_actuals, pd.Series(base_p))
            m_s = evaluate_predictions(overall_actuals, pd.Series(base_s))
            m_w = evaluate_predictions(overall_actuals, pd.Series(base_w))
            m_t = evaluate_predictions(overall_actuals, pd.Series(base_t))
            
            baseline_map = {"persistence": m_p, "seasonal": m_s, "week": m_w, "trend": m_t}
            best_baseline_key = min([k for k, v in baseline_map.items() if v.rmse is not None], key=lambda k: baseline_map[k].rmse)
            best_baseline_metrics = baseline_map[best_baseline_key]
            sparse_validation = {}
            sparse_status = None
            if scope == "GLOBAL_COLD_START":
                for mask_name in ["one_observation", "four_observations", "partial_pollutants", "missing_weather"]:
                    mask_actuals, mask_preds, mask_current, mask_station_labels = [], [], [], []
                    for tr, va, te in splits:
                        tr_full = pd.concat([tr, va]).dropna(subset=[target_col])
                        te_h = te.dropna(subset=[target_col])
                        if len(tr_full) < 50 or len(te_h) < 10:
                            continue
                        pipeline = pipeline_for(best_family, model_factory)
                        tr_features = sparse_cold_start_frame(tr_full, feature_cols, "one_observation")
                        te_features = sparse_cold_start_frame(te_h, feature_cols, mask_name)
                        pipeline.fit(tr_features[feature_cols], tr_full[target_col])
                        raw = pipeline.predict(te_features[feature_cols])
                        pred = (te_h["currentAqi"] + raw).clip(0, 500) if use_delta else np.clip(raw, 0, 500)
                        mask_actuals.extend(te_h[abs_target].tolist())
                        mask_preds.extend(pred.tolist())
                        mask_current.extend(te_h["currentAqi"].tolist())
                        mask_station_labels.extend(te_h[scope_column].astype(str).tolist())
                    if mask_actuals:
                        model_mask_metrics = sparse_metrics(mask_actuals, mask_preds, mask_current)
                        baseline_mask_metrics = sparse_metrics(mask_actuals, mask_current, mask_current)
                        station_metrics = {}
                        mask_eval = pd.DataFrame({
                            "actual": mask_actuals,
                            "predicted": mask_preds,
                            "current": mask_current,
                            "station": mask_station_labels,
                        })
                        for station_label, group in mask_eval.groupby("station"):
                            station_metrics[str(station_label)] = {
                                "model": sparse_metrics(group["actual"], group["predicted"], group["current"]),
                                "persistence": sparse_metrics(group["actual"], group["current"], group["current"]),
                            }
                        sparse_validation[mask_name] = {
                            "model": model_mask_metrics,
                            "persistence": baseline_mask_metrics,
                            "perStation": station_metrics,
                        }
                primary_sparse = sparse_validation.get("one_observation", {})
                primary_model = primary_sparse.get("model", {})
                primary_baseline = primary_sparse.get("persistence", {})
                if primary_model and primary_baseline:
                    sparse_status = promotion_status(
                        metric_result_from_dict(primary_model),
                        metric_result_from_dict(primary_baseline),
                        minimum_samples,
                        min_improvement,
                        max_bias,
                        True,
                    )
            
            # Stability check: Max RMSE across folds shouldn't be > 1.5 * Min RMSE
            stable = True
            if test_rmses and max(test_rmses) > 1.5 * min(test_rmses):
                stable = False

            heldout_by_station = {}
            heldout_by_city = {}
            station_grouped_evaluation = {}
            if scope.startswith("GLOBAL_"):
                eval_frame = pd.DataFrame({
                    "actual": all_actuals,
                    "predicted": all_preds,
                    "station": all_station_labels,
                    "city": all_city_labels,
                })
                for station_label, group in eval_frame.groupby("station"):
                    metrics = evaluate_predictions(group["actual"], group["predicted"])
                    heldout_by_station[str(station_label)] = metrics.__dict__
                for city_label, group in eval_frame.groupby("city"):
                    metrics = evaluate_predictions(group["actual"], group["predicted"])
                    heldout_by_city[str(city_label or "UNKNOWN")] = metrics.__dict__
                for heldout_station, heldout_rows in scope_df.groupby(scope_column):
                    train_rows = scope_df[scope_df[scope_column] != heldout_station].dropna(subset=[target_col])
                    test_rows = heldout_rows.dropna(subset=[target_col])
                    if len(train_rows) < minimum_samples or len(test_rows) < 10:
                        continue
                    holdout_pipeline = pipeline_for(best_family, model_factory)
                    train_features = runtime_feature_frame(scope, train_rows, feature_cols)
                    test_features = runtime_feature_frame(scope, test_rows, feature_cols)
                    holdout_pipeline.fit(train_features[feature_cols], train_rows[target_col])
                    holdout_raw = holdout_pipeline.predict(test_features[feature_cols])
                    holdout_pred = (test_rows["currentAqi"] + holdout_raw).clip(0, 500) if use_delta else np.clip(holdout_raw, 0, 500)
                    holdout_metrics = sparse_metrics(test_rows[abs_target], pd.Series(holdout_pred, index=test_rows.index), test_rows["currentAqi"])
                    holdout_baseline = sparse_metrics(test_rows[abs_target], persistence_predictions(test_rows), test_rows["currentAqi"])
                    train_stats = feature_stats_from_frame(train_features, feature_cols)
                    station_grouped_evaluation[str(heldout_station)] = {
                        "city": str(test_rows["city"].dropna().iloc[0]) if "city" in test_rows.columns and not test_rows["city"].dropna().empty else "",
                        "trainRows": int(len(train_rows)),
                        "testRows": int(len(test_rows)),
                        "metrics": holdout_metrics,
                        "persistenceBaseline": holdout_baseline,
                        "oodRate": ood_rate_against_stats(test_features, feature_cols, train_stats),
                    }

            heldout_validation_failures = heldout_failures(station_grouped_evaluation, minimum_samples, max_bias) if scope.startswith("GLOBAL_") else []
            status = promotion_status(model_metrics, best_baseline_metrics, minimum_samples, min_improvement, max_bias, stable)
            if sparse_status and sparse_status != "PROMOTED":
                status = sparse_status
            if heldout_validation_failures:
                status = "REJECTED_HELDOUT_BASELINE_BETTER"
            
            # Retrain final model on the entire dataset (all folds)
            full_train = bundle.frame if scope.startswith("GLOBAL_") else bundle.frame[bundle.frame[scope_column] == scope]
            full_train_h = full_train.dropna(subset=[target_col])
            
            final_pipeline = pipeline_for(best_family, model_factory)
            full_train_features = runtime_feature_frame(scope, full_train_h, feature_cols)
            final_pipeline.fit(full_train_features[feature_cols], full_train_h[target_col])
            training_feature_stats = feature_stats_from_frame(full_train_features, feature_cols)
            delta_values = pd.to_numeric(full_train_h[target_col], errors="coerce").dropna()
            training_delta_percentiles = {
                "p01": float(delta_values.quantile(0.01)) if not delta_values.empty else None,
                "p05": float(delta_values.quantile(0.05)) if not delta_values.empty else None,
                "p50": float(delta_values.quantile(0.50)) if not delta_values.empty else None,
                "p95": float(delta_values.quantile(0.95)) if not delta_values.empty else None,
                "p99": float(delta_values.quantile(0.99)) if not delta_values.empty else None,
            }
            residual_values = (pd.Series(all_actuals).reset_index(drop=True) - pd.Series(all_preds).reset_index(drop=True)).dropna()
            residual_quantiles = {
                "p10": float(residual_values.quantile(0.10)) if not residual_values.empty else None,
                "p90": float(residual_values.quantile(0.90)) if not residual_values.empty else None,
            }
            imputer_defaults = {}
            imputer = final_pipeline.named_steps.get("imputer")
            if imputer is not None and hasattr(imputer, "statistics_"):
                imputer_defaults = {
                    feature: (None if pd.isna(value) else float(value))
                    for feature, value in zip(feature_cols, imputer.statistics_)
                }
            ridge_diagnostics = None
            model_step = final_pipeline.named_steps.get("model")
            if family_name == "SKLEARN_RIDGE" and hasattr(model_step, "coef_"):
                ridge_diagnostics = {
                    "intercept": float(model_step.intercept_),
                    "coefficients": {feature: float(coef) for feature, coef in zip(feature_cols, model_step.coef_)},
                }

            base_version = f"aqi-{horizon}h-{best_family.lower()}-{scope.replace(':', '_')}"
            version_suffix = str(config.get("versionSuffix") or "").strip()
            version = f"{base_version}-{version_suffix}" if version_suffix else base_version
            artifact = out_dir / f"{version}.joblib"
            model_scope_type, forecast_scope, station_key, required_history_hours = scope_metadata(scope)
            metadata = {
                "modelId": version,
                "horizonHours": horizon,
                "modelScope": scope,
                "modelScopeType": model_scope_type,
                "forecastScope": forecast_scope,
                "stationKey": station_key,
                "historyTier": scope if scope.startswith("GLOBAL_") else "STATION_FULL_HISTORY",
                "requiredHistoryHours": required_history_hours,
                "aqiStandard": standard,
                "modelFamily": family_name,
                "version": version,
                "artifactPath": str(artifact),
                "featureSchemaVersion": "forecasting-feature-schema-v2",
                "useDeltaTarget": use_delta,
                "features": feature_cols,
                "requiredFeatures": ["currentAqi", "latitude", "longitude"] if scope == "GLOBAL_COLD_START" else ["currentAqi"],
                "featureStats": training_feature_stats,
                "imputerDefaults": imputer_defaults,
                "trainingDeltaPercentiles": training_delta_percentiles,
                "ridgeDiagnostics": ridge_diagnostics,
                "trainingRowCount": int(len(full_train_h)),
                "trainingDateRange": [
                    full_train_h["timestamp"].min().isoformat() if "timestamp" in full_train_h.columns and not full_train_h.empty else None,
                    full_train_h["timestamp"].max().isoformat() if "timestamp" in full_train_h.columns and not full_train_h.empty else None,
                ],
                "testRowCount": int(len(all_actuals)),
                "datasetChecksum": bundle.checksum,
                "metrics": model_metrics.__dict__,
                "bestBaseline": best_baseline_key,
                "baselineMetrics": best_baseline_metrics.__dict__,
                "allBaselines": {k: v.__dict__ for k, v in baseline_map.items()},
                "modelComparison": model_comparison,
                "sparseValidation": sparse_validation,
                "sparsePromotionStatus": sparse_status,
                "walkForwardStable": stable,
                "heldOutStationEvaluation": heldout_by_station,
                "heldOutCityEvaluation": heldout_by_city,
                "stationGroupedEvaluation": station_grouped_evaluation,
                "heldOutValidationFailures": heldout_validation_failures,
                "heldOutValidationStatus": "FAILED_HELDOUT_BASELINE" if heldout_validation_failures else "ROBUST",
                "trainingRowsPerStation": rows_per_station if scope.startswith("GLOBAL_") else {scope: int(len(full_train_h))},
                "trainingStationCount": robust_station_count if scope.startswith("GLOBAL_") else 1,
                "trainingCities": training_cities if scope.startswith("GLOBAL_") else [str(full_train_h["city"].dropna().iloc[0])] if "city" in full_train_h.columns and not full_train_h["city"].dropna().empty else [],
                "heldOutStationCount": len(station_grouped_evaluation),
                "coverageScope": results["coverageScope"] if scope.startswith("GLOBAL_") else "STATION_SPECIFIC",
                "generalizationStatus": results["generalizationStatus"] if scope.startswith("GLOBAL_") else "STATION_SPECIFIC",
                "coverageLabel": results["coverageLabel"] if scope.startswith("GLOBAL_") else "STATION_SPECIFIC",
                "oodPolicy": {
                    "lowMaxScore": 0.05,
                    "mediumMaxScore": 0.15,
                    "mediumRequiresHeldOutRobust": True,
                    "highFallbackReason": "OUT_OF_DISTRIBUTION_FEATURES",
                },
                "promotionStatus": status,
                "promotedAt": datetime.now(timezone.utc).isoformat() if status == "PROMOTED" else None,
                "residualQuantiles": residual_quantiles,
            }
            current_comparison = current_promoted_comparison(metadata, scope, min_improvement) if config.get("writeRegistry", True) else None
            if status == "PROMOTED" and current_comparison and not current_comparison.get("newModelBetter", True):
                status = "REJECTED_CURRENT_MODEL_BETTER"
                metadata["promotionStatus"] = status
            metadata["currentPromotedComparison"] = current_comparison
            joblib.dump({"pipeline": final_pipeline, "metadata": metadata}, artifact)
            write_json(out_dir / f"{version}.json", metadata)
            if status == "PROMOTED" and config.get("writeRegistry", True):
                config["modelScope"] = scope
                register_promoted_model(metadata, config)
                
            results["scopes"][scope]["horizons"][str(horizon)] = metadata
            print(f"    Test RMSE: {model_metrics.rmse:.1f} vs Baseline ({best_baseline_key}): {best_baseline_metrics.rmse:.1f} -> {status}")

    write_json(out_dir / "training_report.json", results)
    return results


def current_promoted_comparison(metadata: dict, scope: str, min_improvement_percent: float) -> dict | None:
    try:
        db = mongo_database()
        current = db["forecast_model_registry"].find_one(
            {
                "aqiStandard": metadata["aqiStandard"],
                "horizonHours": metadata["horizonHours"],
                "modelScope": scope,
                "active": True,
                "promotionStatus": "PROMOTED",
            },
            sort=[("promotedAt", -1)],
        )
    except Exception as exc:
        return {"status": "CURRENT_MODEL_LOOKUP_FAILED", "reason": str(exc)}
    if not current:
        return {"status": "NO_CURRENT_PROMOTED_MODEL", "newModelBetter": True}
    current_metrics = current.get("metrics") or {}
    new_metrics = metadata.get("metrics") or {}
    current_rmse = current_metrics.get("rmse")
    new_rmse = new_metrics.get("rmse")
    if current_rmse is None or new_rmse is None:
        return {
            "status": "CURRENT_MODEL_METRICS_UNAVAILABLE",
            "currentModelId": current.get("modelId"),
            "currentVersion": current.get("version"),
            "newModelBetter": True,
        }
    required_rmse = float(current_rmse) * (1.0 - float(min_improvement_percent) / 100.0)
    better = float(new_rmse) < required_rmse
    return {
        "status": "NEW_MODEL_BETTER" if better else "CURRENT_MODEL_RETAINED",
        "currentModelId": current.get("modelId"),
        "currentVersion": current.get("version"),
        "currentRmse": float(current_rmse),
        "newRmse": float(new_rmse),
        "requiredRmseForPromotion": required_rmse,
        "minImprovementPercent": float(min_improvement_percent),
        "newModelBetter": better,
    }


def register_promoted_model(metadata: dict, config: dict) -> None:
    db = mongo_database()
    training_start, training_end = metadata.get("trainingDateRange") or [None, None]
    now = datetime.now(timezone.utc)
    scope = config.get("modelScope", "GLOBAL")
    current = db["forecast_model_registry"].find_one(
        {
            "aqiStandard": metadata["aqiStandard"],
            "horizonHours": metadata["horizonHours"],
            "modelScope": scope,
            "active": True,
        },
        sort=[("promotedAt", -1)],
    )
    previous_version = current.get("version") if current else None
    if current and current.get("modelId") != metadata["modelId"]:
        db["forecast_model_registry"].update_many(
            {
                "aqiStandard": metadata["aqiStandard"],
                "horizonHours": metadata["horizonHours"],
                "modelScope": scope,
                "active": True,
                "modelId": {"$ne": metadata["modelId"]},
            },
            {
                "$set": {
                    "active": False,
                    "deactivatedAt": now,
                    "rollbackEligible": True,
                    "rollbackReason": f"Superseded by {metadata['modelId']}",
                    "updatedAt": now,
                }
            },
        )
    registry = {
        "modelId": metadata["modelId"],
        "aqiStandard": metadata["aqiStandard"],
        "horizonHours": metadata["horizonHours"],
        "modelScope": scope,
        "forecastScope": metadata.get("forecastScope"),
        "stationKey": metadata.get("stationKey"),
        "modelFamily": metadata["modelFamily"],
        "version": metadata["version"],
        "artifactPath": metadata["artifactPath"],
        "featureSchemaVersion": metadata["featureSchemaVersion"],
        "trainingStart": parse_registry_datetime(training_start),
        "trainingEnd": parse_registry_datetime(training_end),
        "trainingRowCount": metadata["trainingRowCount"],
        "features": metadata["features"],
        "metrics": metadata["metrics"],
        "baselineMetrics": metadata["baselineMetrics"],
        "promotionStatus": metadata["promotionStatus"],
        "previousPromotedVersion": previous_version,
        "promotedAt": parse_registry_datetime(metadata["promotedAt"]),
        "activatedAt": now,
        "deactivatedAt": None,
        "rollbackEligible": False,
        "rollbackReason": None,
        "datasetChecksum": metadata["datasetChecksum"],
        "active": True,
        "updatedAt": now,
    }
    db["forecast_model_registry"].update_one(
        {"modelId": metadata["modelId"]},
        {"$set": registry, "$setOnInsert": {"createdAt": now}},
        upsert=True,
    )


def parse_registry_datetime(value):
    if not value:
        return None
    if isinstance(value, datetime):
        return value
    text = str(value).replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(text).astimezone(timezone.utc)
    except ValueError:
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    print(json.dumps(train(config), indent=2, default=str))


if __name__ == "__main__":
    main()
