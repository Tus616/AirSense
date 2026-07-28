from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite
from typing import Iterable

import pandas as pd


@dataclass(frozen=True)
class PreparedHistory:
    values: list[float]
    timestamps: list[pd.Timestamp]
    observation_count: int
    coverage_hours: float
    largest_gap_hours: float
    missing_observation_count: int
    latest_timestamp: str | None
    selected_aqi_field: str
    aqi_standard: str


def finite_number(value) -> float | None:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        return None
    return numeric if isfinite(numeric) else None


def parse_timestamp(value) -> pd.Timestamp | None:
    if value is None or str(value).strip() == "":
        return None
    parsed = pd.to_datetime(value, utc=True, errors="coerce")
    if pd.isna(parsed):
        return None
    return parsed


def prepare_aqi_history(
    observations: Iterable[dict],
    *,
    issue_time: datetime,
    aqi_standard: str,
    max_small_gap_hours: float = 2.0,
    selected_aqi_field: str = "currentAqi",
) -> PreparedHistory:
    rows = []
    issue_ts = pd.Timestamp(issue_time.astimezone(timezone.utc))
    for row in observations or []:
        row_standard = row.get("aqiStandard") or row.get("aqi_standard") or aqi_standard
        if str(row_standard).upper() != str(aqi_standard).upper():
            continue
        timestamp = parse_timestamp(row.get("timestamp") or row.get("observedAt") or row.get("providerObservedAt"))
        value = finite_number(row.get(selected_aqi_field) if selected_aqi_field in row else row.get("aqi"))
        if timestamp is None or value is None or timestamp > issue_ts:
            continue
        rows.append({"timestamp": timestamp, "aqi": value})

    if not rows:
        return PreparedHistory([], [], 0, 0.0, 0.0, 0, None, selected_aqi_field, aqi_standard)

    frame = pd.DataFrame(rows).drop_duplicates(subset=["timestamp"], keep="last").sort_values("timestamp")
    frame = frame.set_index("timestamp")
    gaps = frame.index.to_series().diff().dropna().dt.total_seconds() / 3600.0
    largest_gap = float(gaps.max()) if not gaps.empty else 0.0
    hourly = frame.resample("1h").mean()
    missing_before = int(hourly["aqi"].isna().sum())
    hourly["aqi"] = hourly["aqi"].interpolate(method="time", limit=max(1, int(max_small_gap_hours)), limit_area="inside")
    hourly = hourly.dropna(subset=["aqi"])
    if hourly.empty:
        return PreparedHistory([], [], 0, 0.0, largest_gap, missing_before, None, selected_aqi_field, aqi_standard)

    coverage = (hourly.index.max() - hourly.index.min()).total_seconds() / 3600.0 if len(hourly) > 1 else 0.0
    return PreparedHistory(
        values=[float(v) for v in hourly["aqi"].tolist()],
        timestamps=list(hourly.index),
        observation_count=int(len(hourly)),
        coverage_hours=float(round(coverage, 2)),
        largest_gap_hours=float(round(largest_gap, 2)),
        missing_observation_count=missing_before,
        latest_timestamp=hourly.index.max().isoformat().replace("+00:00", "Z"),
        selected_aqi_field=selected_aqi_field,
        aqi_standard=aqi_standard,
    )
