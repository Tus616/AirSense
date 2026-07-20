"""Generate per-station data quality reports from MongoDB.

Usage:
    python -m forecasting.data.quality_report
"""
from __future__ import annotations

import argparse
import json
from collections import Counter
from datetime import datetime, timezone

import pandas as pd

from forecasting.data.common import mongo_database, write_json
from forecasting.features.dataset import HORIZONS


AQI_BINS = [0, 50, 100, 200, 300, 400, 500]
AQI_LABELS = ["GOOD", "SATISFACTORY", "MODERATE", "POOR", "VERY_POOR", "SEVERE"]


def generate_report(standard: str = "INDIA_NAQI") -> dict:
    db = mongo_database()
    aqi_cursor = db["aqi_historical_snapshots"].find(
        {"aqiStandard": standard}, {"_id": 0}
    )
    aqi_df = pd.DataFrame(list(aqi_cursor))
    if aqi_df.empty:
        return {"stations": {}, "summary": "NO_DATA"}

    weather_cursor = db["historical_weather_observations"].find({}, {"_id": 0})
    weather_df = pd.DataFrame(list(weather_cursor))

    aqi_df["timestamp"] = pd.to_datetime(
        aqi_df.get("providerObservedAt", aqi_df.get("timestamp")),
        utc=True,
        errors="coerce",
    ).dt.floor("h")

    location_groups = aqi_df.groupby("locationKey")
    report: dict = {"stations": {}, "generatedAt": datetime.now(timezone.utc).isoformat()}
    pollutant_cols = ["pm25", "pm10", "no2", "so2", "co", "o3", "nh3"]

    for loc_key, group in location_groups:
        station_name = group["stationName"].dropna().iloc[0] if "stationName" in group.columns and not group["stationName"].dropna().empty else str(loc_key)
        city = group["city"].dropna().iloc[0] if "city" in group.columns and not group["city"].dropna().empty else ""
        sorted_g = group.sort_values("timestamp").drop_duplicates("timestamp", keep="last")
        unique_hours = int(sorted_g["timestamp"].nunique())
        oldest = str(sorted_g["timestamp"].min()) if not sorted_g.empty else None
        latest = str(sorted_g["timestamp"].max()) if not sorted_g.empty else None
        dup_count = int(group.duplicated(["locationKey", "timestamp"]).sum())

        # Missing pollutant percentages
        missing_pct = {}
        for col in pollutant_cols:
            if col in sorted_g.columns:
                missing_pct[col] = round(float(sorted_g[col].isna().mean() * 100), 2)
            else:
                missing_pct[col] = 100.0

        # Gaps
        deltas = sorted_g["timestamp"].diff().dropna().dt.total_seconds() / 3600
        largest_gap = round(float(deltas.max()), 2) if not deltas.empty else None
        gaps_over_1h = int((deltas > 1.5).sum())

        cpcb_measured = int((sorted_g["dataOrigin"] == "OBSERVED_CPCB").sum())
        cpcb_derived = int((sorted_g["dataOrigin"] == "DERIVED_CPCB_NAQI").sum())
        cams_modelled = int((sorted_g["dataOrigin"] == "REANALYSIS").sum())
        weather_rows = int(weather_df[weather_df["locationKey"] == loc_key].shape[0]) if not weather_df.empty and "locationKey" in weather_df.columns else 0

        # Valid targets per horizon
        valid_targets = {}
        for h in HORIZONS:
            shifted = sorted_g.set_index("timestamp")["currentAqi"].shift(-h, freq="h")
            matched = shifted.reindex(sorted_g["timestamp"]).dropna()
            valid_targets[f"{h}h"] = int(len(matched))
        # Only CPCB/observed data can be used as targets!
        # CAMS modelled data (REANALYSIS) should not contribute to validTargets.
        cpcb_mask = sorted_g["dataOrigin"].isin(["OBSERVED_CPCB", "DERIVED_CPCB_NAQI", "OBSERVED_IMPORTED"])
        cpcb_ts_set = set(sorted_g.loc[cpcb_mask, "timestamp"])
        
        for h in HORIZONS:
            valid = sum(1 for ts in sorted_g["timestamp"] if (ts + pd.Timedelta(hours=h)) in cpcb_ts_set)
            valid_targets[f"{h}h"] = int(valid)

        # Weather coverage
        weather_coverage = 0.0
        if not weather_df.empty and "locationKey" in weather_df.columns:
            weather_loc = weather_df[weather_df["locationKey"] == loc_key]
            if not weather_loc.empty and unique_hours > 0:
                weather_loc_ts = pd.to_datetime(
                    weather_loc.get("observedAt", weather_loc.get("timestamp")),
                    utc=True, errors="coerce"
                ).dt.floor("h")
                weather_ts_set = set(weather_loc_ts)
                matched_hours = sum(1 for ts in sorted_g["timestamp"] if ts in weather_ts_set)
                weather_coverage = round(float(matched_hours / unique_hours * 100), 2)

        # AQI category distribution
        aqi_vals = pd.to_numeric(sorted_g.get("currentAqi"), errors="coerce").dropna()
        cats = pd.cut(aqi_vals, bins=AQI_BINS, labels=AQI_LABELS, include_lowest=True)
        cat_dist = {str(k): int(v) for k, v in cats.value_counts(dropna=True).items()}

        report["stations"][str(loc_key)] = {
            "stationName": station_name,
            "city": city,
            "importedRows": int(len(group)),
            "uniqueHourlyObservations": unique_hours,
            "cpcbMeasuredRows": cpcb_measured,
            "cpcbDerivedRows": cpcb_derived,
            "camsModelledRows": cams_modelled,
            "weatherRows": weather_rows,
            "oldestTimestamp": oldest,
            "latestTimestamp": latest,
            "missingPollutantPercent": missing_pct,
            "duplicateCount": dup_count,
            "largestGapHours": largest_gap,
            "gapsOverOneHour": gaps_over_1h,
            "validTargets": valid_targets,
            "weatherCoveragePercent": weather_coverage,
            "aqiCategoryDistribution": cat_dist,
        }

    report["totalStations"] = len(report["stations"])
    report["totalRows"] = int(len(aqi_df))
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate per-station quality report")
    parser.add_argument("--standard", default="INDIA_NAQI")
    parser.add_argument("--report", default="forecasting/data/quality_report.json")
    args = parser.parse_args()

    report = generate_report(args.standard)
    write_json(args.report, report)
    print(json.dumps(report, indent=2, default=str))


if __name__ == "__main__":
    main()
