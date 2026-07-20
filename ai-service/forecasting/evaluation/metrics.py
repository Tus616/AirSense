from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class MetricResult:
    sample_count: int
    mae: float | None
    rmse: float | None
    mean_bias: float | None
    median_absolute_error: float | None
    category_accuracy: float | None
    interval_coverage: float | None = None


def evaluate_predictions(actual: pd.Series, predicted: pd.Series, lower=None, upper=None) -> MetricResult:
    frame = pd.DataFrame({"actual": actual, "predicted": predicted}).dropna()
    if frame.empty:
        return MetricResult(0, None, None, None, None, None, None)
    error = frame["predicted"] - frame["actual"]
    interval_coverage = None
    if lower is not None and upper is not None:
        bounds = pd.DataFrame({"actual": actual, "lower": lower, "upper": upper}).dropna()
        if not bounds.empty:
            interval_coverage = float(((bounds["actual"] >= bounds["lower"]) & (bounds["actual"] <= bounds["upper"])).mean())
    return MetricResult(
        sample_count=int(len(frame)),
        mae=float(error.abs().mean()),
        rmse=float(math.sqrt((error ** 2).mean())),
        mean_bias=float(error.mean()),
        median_absolute_error=float(error.abs().median()),
        category_accuracy=float((frame["actual"].map(category) == frame["predicted"].map(category)).mean()),
        interval_coverage=interval_coverage,
    )


def persistence_predictions(frame: pd.DataFrame) -> pd.Series:
    """Persistence baseline: predict AQI(t+h) = AQI(t)."""
    return frame["currentAqi"]


def seasonal_persistence_predictions(frame: pd.DataFrame, horizon: int) -> pd.Series:
    """Seasonal persistence: predict AQI(t+h) = AQI(t - (24 - h%24)) i.e. same hour yesterday."""
    if "aqi_prev_day_same_hour" in frame.columns:
        pred = frame["aqi_prev_day_same_hour"].copy()
        pred = pred.fillna(frame["currentAqi"])
        return pred
    return frame["currentAqi"]


def previous_week_persistence_predictions(frame: pd.DataFrame) -> pd.Series:
    """Previous week persistence: predict AQI(t+h) = AQI(t - 168h)."""
    if "aqi_prev_week_same_hour" in frame.columns:
        pred = frame["aqi_prev_week_same_hour"].copy()
        pred = pred.fillna(frame["currentAqi"])
        return pred
    return frame["currentAqi"]


def trend_persistence_predictions(frame: pd.DataFrame) -> pd.Series:
    """Trend persistence: Current AQI + recent 24h change."""
    if "currentAqi_change_24h" in frame.columns:
        trend = frame["currentAqi_change_24h"].fillna(0)
        # Cap trend to avoid exploding predictions
        trend = trend.clip(-100, 100)
        pred = frame["currentAqi"] + trend
        return pred.clip(0, 500)
    return frame["currentAqi"]


def seasonal_rolling_median_predictions(frame: pd.DataFrame) -> pd.Series:
    """Seasonal rolling median: Use 168h median if available."""
    if "aqi_roll_168h_median" in frame.columns:
        pred = frame["aqi_roll_168h_median"].copy()
        pred = pred.fillna(frame["currentAqi"])
        return pred
    return frame["currentAqi"]


def category(value: float) -> str:
    if value <= 50:
        return "GOOD"
    if value <= 100:
        return "SATISFACTORY"
    if value <= 200:
        return "MODERATE"
    if value <= 300:
        return "POOR"
    if value <= 400:
        return "VERY_POOR"
    return "SEVERE"


def promotion_status(model: MetricResult, baseline: MetricResult, min_samples: int, min_rmse_improvement_pct: float, max_abs_bias: float, stable_across_folds: bool = True) -> str:
    if model.sample_count < min_samples or baseline.sample_count < min_samples:
        return "INSUFFICIENT_DATA"
    if model.rmse is None or baseline.rmse is None or baseline.rmse <= 0:
        return "INSUFFICIENT_DATA"
    if not stable_across_folds:
        return "UNSTABLE_ACROSS_FOLDS"
        
    improvement = (baseline.rmse - model.rmse) / baseline.rmse * 100.0
    if abs(model.mean_bias or 0.0) > max_abs_bias:
        return "REJECTED_BIAS"
    if improvement < min_rmse_improvement_pct:
        return "REJECTED_BASELINE_BETTER"
    if model.mae is not None and baseline.mae is not None and model.mae > baseline.mae * 1.05:
        return "REJECTED_BASELINE_BETTER"
    return "PROMOTED"
