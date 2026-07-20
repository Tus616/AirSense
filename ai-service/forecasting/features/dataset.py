from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd
import re

HORIZONS = (24, 48, 72)
POLLUTANTS = ("currentAqi", "pm25", "pm10", "no2", "so2", "co", "o3", "nh3")
LAGS = (1, 2, 3, 6, 12, 24, 48, 72, 168)
ROLLING = (3, 6, 12, 24, 48, 72, 168)


@dataclass(frozen=True)
class DatasetBundle:
    frame: pd.DataFrame
    feature_columns: list[str]
    target_columns: dict[int, str]
    delta_target_columns: dict[int, str]
    quality_report: dict
    checksum: str


def build_hourly_dataset(aqi_records: pd.DataFrame, weather_records: pd.DataFrame | None = None) -> DatasetBundle:
    if aqi_records.empty:
        empty = pd.DataFrame()
        return DatasetBundle(empty, [], {h: f"target_aqi_{h}h" for h in HORIZONS},
                             {h: f"delta_aqi_{h}h" for h in HORIZONS}, quality(empty), checksum(empty))
    df = normalize_aqi_frame(aqi_records)
    weather = normalize_weather_frame(weather_records) if weather_records is not None and not weather_records.empty else pd.DataFrame()
    if not weather.empty:
        df = df.merge(weather, on=["locationKey", "timestamp"], how="left", suffixes=("", "_weather"))
    df = df.sort_values(["locationKey", "timestamp"]).drop_duplicates(["locationKey", "timestamp"], keep="last")
    df["stationIdentity"] = station_identity(df)

    # Drop completely null pollutant columns (e.g. from CPCB pure AQI files)
    active_pollutants = []
    for p in POLLUTANTS:
        if p in df.columns and not df[p].isna().all():
            active_pollutants.append(p)
        elif p in df.columns:
            df = df.drop(columns=[p])

    # ---- Per-station feature engineering ----
    feature_parts = []
    for loc_key, group in df.groupby("stationIdentity", group_keys=False):
        g = group.sort_values("timestamp").copy()
        g = _add_lag_features(g, active_pollutants)
        g = _add_rolling_features(g)
        g = _add_prev_day_week_features(g)
        g = _add_missingness_features(g)
        feature_parts.append(g)

    df = pd.concat(feature_parts, ignore_index=True)
    df = df.sort_values(["stationIdentity", "timestamp"]).reset_index(drop=True)

    add_time_features(df)

    # ---- Targets ----
    group = df.groupby("stationIdentity", group_keys=False)
    for horizon in HORIZONS:
        df[f"target_aqi_{horizon}h"] = group["currentAqi"].shift(-horizon)
        df[f"delta_aqi_{horizon}h"] = df[f"target_aqi_{horizon}h"] - df["currentAqi"]

    # ---- Station encoding ----
    station_codes = {key: idx for idx, key in enumerate(sorted(df["stationIdentity"].dropna().unique()))}
    df["stationCode"] = df["stationIdentity"].map(station_codes).astype(float)
    df["stationLatitudeFeature"] = pd.to_numeric(df.get("stationLatitude", df.get("latitude")), errors="coerce")
    df["stationLongitudeFeature"] = pd.to_numeric(df.get("stationLongitude", df.get("longitude")), errors="coerce")

    exclude = {"timestamp", "sourceTimestamp", "aqiStandard", "provider", "stationName",
               "city", "state", "country", "dataOrigin", "providerObservedAt", "ingestedAt",
               "locationKey", "stationKey", "stationLocationKey", "stationIdentity"}
    target_cols = {h: f"target_aqi_{h}h" for h in HORIZONS}
    delta_cols = {h: f"delta_aqi_{h}h" for h in HORIZONS}
    all_targets = set(target_cols.values()) | set(delta_cols.values())
    feature_cols = [
        col for col in df.columns
        if col not in exclude and col not in all_targets and pd.api.types.is_numeric_dtype(df[col])
    ]
    report = quality(df)
    cs = checksum(df[["locationKey", "timestamp", "currentAqi"] + list(target_cols.values())].dropna(subset=["locationKey"]))
    return DatasetBundle(df, feature_cols, target_cols, delta_cols, report, cs)


def _add_lag_features(g: pd.DataFrame, active_pollutants: list[str]) -> pd.DataFrame:
    """Lag features per station. Lags are strictly past-looking (shift > 0)."""
    for col in active_pollutants:
        for lag in LAGS:
            g[f"{col}_lag_{lag}h"] = g[col].shift(lag)
        g[f"{col}_change_1h"] = g[col] - g[col].shift(1)
        g[f"{col}_change_3h"] = g[col] - g[col].shift(3)
        g[f"{col}_change_6h"] = g[col] - g[col].shift(6)
        g[f"{col}_change_24h"] = g[col] - g[col].shift(24)
    if "pm25" in active_pollutants and "pm10" in active_pollutants:
        g["pm25_pm10_ratio"] = g["pm25"] / g["pm10"].replace(0, np.nan)
    return g


def _add_rolling_features(g: pd.DataFrame) -> pd.DataFrame:
    """Rolling window features. Use shift(1) to avoid including current observation."""
    shifted = g["currentAqi"].shift(1)
    for window in ROLLING:
        min_p = max(2, window // 3)
        roll = shifted.rolling(window, min_periods=min_p)
        g[f"aqi_roll_{window}h_mean"] = roll.mean()
        g[f"aqi_roll_{window}h_median"] = roll.median()
        g[f"aqi_roll_{window}h_min"] = roll.min()
        g[f"aqi_roll_{window}h_max"] = roll.max()
        g[f"aqi_roll_{window}h_std"] = roll.std()
        # Slope: (last - first) / window
        g[f"aqi_roll_{window}h_slope"] = (shifted - shifted.shift(window)) / window
    return g


def _add_prev_day_week_features(g: pd.DataFrame) -> pd.DataFrame:
    """Previous-day same-hour and previous-week same-hour AQI."""
    g["aqi_prev_day_same_hour"] = g["currentAqi"].shift(24)
    g["aqi_prev_week_same_hour"] = g["currentAqi"].shift(168)
    return g


def _add_missingness_features(g: pd.DataFrame) -> pd.DataFrame:
    """Missingness indicators for the last N hours."""
    for window in [3, 6, 12, 24]:
        g[f"aqi_missing_{window}h_ratio"] = g["currentAqi"].shift(1).rolling(window, min_periods=1).apply(
            lambda x: x.isna().mean(), raw=False
        )
    return g


def normalize_aqi_frame(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    out["timestamp"] = pd.to_datetime(out.get("providerObservedAt", out.get("timestamp")), utc=True, errors="coerce").dt.floor("h")
    out = out[out["aqiStandard"].fillna("INDIA_NAQI").eq("INDIA_NAQI")]
    for col in ["currentAqi", "pm25", "pm10", "no2", "so2", "co", "o3", "nh3", "latitude", "longitude"]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")

    # CRITICAL SOURCE SEPARATION: Separate CPCB targets from CAMS modelled features
    if "dataOrigin" in out.columns:
        cpcb_mask = out["dataOrigin"].isin([
            "OBSERVED", "HISTORICAL_TRAINING_ARCHIVE", "LIVE_OPERATIONAL_HISTORY", "DERIVED_FROM_REAL_DATA",
            "OBSERVED_CPCB", "DERIVED_CPCB_NAQI", "OBSERVED_IMPORTED"
        ])
        cams_mask = out["dataOrigin"] == "REANALYSIS"
    else:
        # Default all to CPCB if missing (for backwards compat with tests)
        cpcb_mask = pd.Series(True, index=out.index)
        cams_mask = pd.Series(False, index=out.index)

    cpcb_df = out[cpcb_mask].copy()
    cams_df = out[cams_mask].copy()

    # Rename CAMS columns to avoid mixing with CPCB
    rename_cams = {col: f"cams_{col}" for col in ["currentAqi", "pm25", "pm10", "no2", "so2", "co", "o3", "nh3"]}
    cams_df = cams_df.rename(columns=rename_cams)

    if not cpcb_df.empty and not cams_df.empty:
        out = pd.merge(cpcb_df, cams_df[["locationKey", "timestamp"] + list(rename_cams.values())],
                       on=["locationKey", "timestamp"], how="left")
    elif not cams_df.empty:
        out = cams_df
        for col in ["currentAqi", "pm25", "pm10", "no2", "so2", "co", "o3", "nh3"]:
            out[col] = np.nan
    else:
        out = cpcb_df

    return out


def station_identity(df: pd.DataFrame) -> pd.Series:
    if "stationKey" in df.columns:
        station_key = df["stationKey"].fillna("").astype(str).str.strip()
    else:
        station_key = pd.Series("", index=df.index)
    if "stationLocationKey" in df.columns:
        station_location_key = df["stationLocationKey"].fillna("").astype(str).str.strip()
    else:
        station_location_key = pd.Series("", index=df.index)
    derived = pd.Series("", index=df.index)
    if "stationName" in df.columns:
        derived = df["stationName"].fillna("").astype(str).map(slug)
    return station_key.where(station_key.ne(""), station_location_key.where(station_location_key.ne(""), derived.where(derived.ne(""), df["locationKey"])))


def slug(value: str) -> str:
    return re.sub(r"^_+|_+$", "", re.sub(r"[^a-z0-9]+", "_", value.lower()))


def normalize_weather_frame(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    out["timestamp"] = pd.to_datetime(out.get("observedAt", out.get("timestamp")), utc=True, errors="coerce").dt.floor("h")
    rename = {
        "temperatureCelsius": "weather_temperatureCelsius",
        "humidityPercent": "weather_humidityPercent",
        "pressureHpa": "weather_pressureHpa",
        "windSpeedMps": "weather_windSpeedMps",
        "windDirectionDegrees": "weather_windDirectionDegrees",
        "rainfallMm": "weather_rainfallMm",
        "cloudCoverPercent": "weather_cloudCoverPercent",
    }
    out = out.rename(columns=rename)
    keep = ["locationKey", "timestamp"] + [col for col in rename.values() if col in out.columns]
    return out[keep]


def add_time_features(df: pd.DataFrame) -> None:
    ts = pd.to_datetime(df["timestamp"], utc=True)
    df["hour"] = ts.dt.hour
    df["dayOfWeek"] = ts.dt.dayofweek
    df["month"] = ts.dt.month
    df["isWeekend"] = (df["dayOfWeek"] >= 5).astype(int)
    # Cyclical season added
    df["season"] = (df["month"] % 12 + 3) // 3
    df["hourSin"] = np.sin(2 * np.pi * df["hour"] / 24)
    df["hourCos"] = np.cos(2 * np.pi * df["hour"] / 24)
    df["dayOfWeekSin"] = np.sin(2 * np.pi * df["dayOfWeek"] / 7)
    df["dayOfWeekCos"] = np.cos(2 * np.pi * df["dayOfWeek"] / 7)
    df["monthSin"] = np.sin(2 * np.pi * df["month"] / 12)
    df["monthCos"] = np.cos(2 * np.pi * df["month"] / 12)


def per_station_walk_forward_splits(df: pd.DataFrame, n_splits: int = 3, train_frac: float = 0.6,
                                     val_frac: float = 0.1, test_frac: float = 0.1) -> list[tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]]:
    """
    Generate multiple sequential train/val/test splits (Walk-Forward validation).
    Each station is split independently, then joined back for each fold.
    Returns a list of (Train, Val, Test) DataFrames.
    """
    splits = []
    
    # We will build fold boundaries. 
    # Example for n_splits=3, test_frac=0.1:
    # Fold 0 ends at 80% (60% train + 10% val + 10% test)
    # Fold 1 ends at 90% (70% train + 10% val + 10% test)
    # Fold 2 ends at 100% (80% train + 10% val + 10% test)
    
    for split_idx in range(n_splits):
        # We walk backwards from the end of the dataset.
        # test_end of the latest split (split_idx = n_splits - 1) is 1.0.
        test_end_frac = 1.0 - (n_splits - 1 - split_idx) * test_frac
        val_end_frac = test_end_frac - test_frac
        train_end_frac = val_end_frac - val_frac
        
        trains, vals, tests = [], [], []
        
        group_column = "stationIdentity" if "stationIdentity" in df.columns else "locationKey"
        for loc_key, group in df.groupby(group_column):
            ordered = group.sort_values("timestamp").reset_index(drop=True)
            n = len(ordered)
            
            t_end = int(n * train_end_frac)
            v_end = int(n * val_end_frac)
            te_end = int(n * test_end_frac)
            
            if t_end > 0 and v_end > t_end and te_end > v_end:
                trains.append(ordered.iloc[:t_end])
                vals.append(ordered.iloc[t_end:v_end])
                tests.append(ordered.iloc[v_end:te_end])
            
        if trains and vals and tests:
            splits.append((
                pd.concat(trains, ignore_index=True),
                pd.concat(vals, ignore_index=True),
                pd.concat(tests, ignore_index=True)
            ))
            
    return splits


# Keep legacy function for backwards compat (redirects to the LAST fold of a 1-split setup)
def per_station_chronological_split(df: pd.DataFrame, train_frac: float = 0.7,
                                     val_frac: float = 0.15) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    # Compute test_frac implicitly
    test_frac = 1.0 - train_frac - val_frac
    splits = per_station_walk_forward_splits(df, n_splits=1, train_frac=train_frac, val_frac=val_frac, test_frac=test_frac)
    return splits[0] if splits else (pd.DataFrame(), pd.DataFrame(), pd.DataFrame())

def chronological_split(df: pd.DataFrame, train_frac: float = 0.7, val_frac: float = 0.15) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    return per_station_chronological_split(df, train_frac, val_frac)


def quality(df: pd.DataFrame) -> dict:
    if df.empty:
        return {"rows": 0, "warnings": ["NO_ROWS"]}
    gaps = []
    station_summary = {}
    weather_cols = [c for c in df.columns if c.startswith("weather_")]

    group_column = "stationIdentity" if "stationIdentity" in df.columns else "locationKey"
    for loc, group in df.sort_values("timestamp").groupby(group_column):
        delta = group["timestamp"].diff().dropna().dt.total_seconds().div(3600)
        loc_gaps = delta[delta > 1.5].round(2).tolist()
        gaps.extend(loc_gaps)

        station_summary[str(loc)] = {
            "rows": len(group),
            "uniqueHourlyObservations": int(group["timestamp"].nunique()),
            "largestGapHours": float(delta.max()) if not delta.empty else None,
            "gapsOverOneHour": len(loc_gaps),
            "dateRange": [str(group["timestamp"].min()), str(group["timestamp"].max())],
        }

    unique_hours = int(df["timestamp"].nunique())
    weather_coverage = 0.0
    if weather_cols and unique_hours > 0:
        has_weather = df[weather_cols].notna().any(axis=1)
        weather_coverage = round(float(has_weather.sum() / len(df) * 100), 2)

    largest_gap = max(gaps) if gaps else None

    return {
        "rows": int(len(df)),
        "uniqueHourlyObservations": unique_hours,
        "rowsPerLocation": {str(k): int(v) for k, v in df.groupby("locationKey").size().items()},
        "stationSummary": station_summary,
        "dateRange": [str(df["timestamp"].min()), str(df["timestamp"].max())],
        "missingPercent": {col: round(float(df[col].isna().mean() * 100), 2) for col in df.columns if col in POLLUTANTS or col in weather_cols},
        "duplicateCount": int(df.duplicated(["locationKey", "timestamp"]).sum()),
        "largestGapHours": largest_gap,
        "gapHoursOverOneHour": gaps[:100],
        "weatherCoveragePercent": weather_coverage,
        "validTargetCount": {f"{h}h": int(df[f"target_aqi_{h}h"].notna().sum()) for h in HORIZONS if f"target_aqi_{h}h" in df.columns},
        "aqiCategoryDistribution": category_distribution(df.get("currentAqi")),
    }


def category_distribution(values: pd.Series | None) -> dict:
    if values is None:
        return {}
    bins = [0, 50, 100, 200, 300, 400, 500]
    labels = ["GOOD", "SATISFACTORY", "MODERATE", "POOR", "VERY_POOR", "SEVERE"]
    cats = pd.cut(values, bins=bins, labels=labels, include_lowest=True)
    return {str(k): int(v) for k, v in cats.value_counts(dropna=True).items()}


def checksum(df: pd.DataFrame) -> str:
    payload = df.sort_index(axis=1).to_json(date_format="iso", orient="split")
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()
