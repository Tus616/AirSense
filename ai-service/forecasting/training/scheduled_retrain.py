from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pandas as pd

from forecasting.data.common import mongo_database, write_json
from forecasting.features.dataset import build_hourly_dataset, station_identity
from forecasting.training.train import GLOBAL_TIERS, train


ARCHIVE_ORIGINS = {
    "OBSERVED",
    "HISTORICAL_TRAINING_ARCHIVE",
    "DERIVED_FROM_REAL_DATA",
    "OBSERVED_CPCB",
    "DERIVED_CPCB_NAQI",
    "OBSERVED_IMPORTED",
}
LIVE_ORIGIN = "LIVE_OPERATIONAL_HISTORY"


def timestamp_column(frame: pd.DataFrame) -> pd.Series:
    return pd.to_datetime(
        frame.get("providerObservedAt", frame.get("timestamp")),
        utc=True,
        errors="coerce",
    ).dt.floor("h")


def valid_aqi_rows(frame: pd.DataFrame, standard: str) -> pd.DataFrame:
    if frame.empty:
        return frame.copy()
    out = frame.copy()
    out["timestamp"] = timestamp_column(out)
    out["currentAqi"] = pd.to_numeric(out.get("currentAqi"), errors="coerce")
    out = out[out["timestamp"].notna()]
    out = out[out["aqiStandard"].fillna(standard).eq(standard)]
    out = out[out["currentAqi"].between(0, 500, inclusive="both")]
    if "dataQualityStatus" in out.columns:
        out = out[out["dataQualityStatus"].fillna("VALID").isin(["VALID", "ACCEPTED", "QUALITY_VALIDATED"])]
    return out


def contiguous_live_blocks(live: pd.DataFrame, max_gap_hours: float, min_contiguous_hours: int) -> tuple[pd.DataFrame, dict]:
    if live.empty:
        return live.copy(), {
            "liveRowsIncluded": 0,
            "liveRowsRejectedQuality": 0,
            "includedBlocks": 0,
            "rejectedBlocks": 0,
            "rejectedStations": {},
        }
    live = live.copy()
    live["stationIdentity"] = station_identity(live)
    included = []
    rejected_rows = 0
    included_blocks = 0
    rejected_blocks = 0
    rejected_stations: dict[str, int] = {}
    for identity, group in live.sort_values("timestamp").groupby("stationIdentity"):
        ordered = group.drop_duplicates("timestamp", keep="last").sort_values("timestamp")
        block_start = 0
        timestamps = ordered["timestamp"].reset_index(drop=True)
        split_points = []
        for idx in range(1, len(timestamps)):
            gap_hours = (timestamps.iloc[idx] - timestamps.iloc[idx - 1]).total_seconds() / 3600.0
            if gap_hours > max_gap_hours:
                split_points.append(idx)
        split_points.append(len(ordered))
        for split_point in split_points:
            block = ordered.iloc[block_start:split_point]
            block_start = split_point
            if len(block) >= min_contiguous_hours:
                included.append(block)
                included_blocks += 1
            else:
                rejected_rows += len(block)
                rejected_blocks += 1
                rejected_stations[str(identity)] = rejected_stations.get(str(identity), 0) + len(block)
    result = pd.concat(included, ignore_index=True) if included else live.iloc[0:0].copy()
    return result, {
        "liveRowsIncluded": int(len(result)),
        "liveRowsRejectedQuality": int(rejected_rows),
        "includedBlocks": included_blocks,
        "rejectedBlocks": rejected_blocks,
        "rejectedStations": rejected_stations,
    }


def verified_training_frames(
    aqi: pd.DataFrame,
    weather: pd.DataFrame,
    standard: str,
    now: datetime,
    max_horizon_hours: int,
    max_live_gap_hours: float,
    min_live_contiguous_hours: int,
) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    valid = valid_aqi_rows(aqi, standard)
    if valid.empty:
        return valid, weather, {"rawRows": int(len(aqi)), "newlyCollectedRowsIncluded": 0, "reason": "NO_VALID_AQI_ROWS"}

    data_origin = valid.get("dataOrigin", pd.Series("", index=valid.index)).fillna("")
    archive = valid[data_origin.isin(ARCHIVE_ORIGINS)].copy()
    live = valid[data_origin.eq(LIVE_ORIGIN)].copy()
    maturity_cutoff = pd.Timestamp(now - timedelta(hours=max_horizon_hours))
    matured_live = live[live["timestamp"] <= maturity_cutoff].copy()
    included_live, live_quality = contiguous_live_blocks(
        matured_live,
        max_gap_hours=max_live_gap_hours,
        min_contiguous_hours=min_live_contiguous_hours,
    )
    training = pd.concat([archive, included_live], ignore_index=True) if not archive.empty or not included_live.empty else valid.iloc[0:0].copy()
    training = training.drop(columns=["stationIdentity"], errors="ignore")
    report = {
        "rawRows": int(len(aqi)),
        "validRows": int(len(valid)),
        "archiveRowsIncluded": int(len(archive)),
        "liveRowsCollected": int(len(live)),
        "liveRowsMatured": int(len(matured_live)),
        "liveRowsRejectedImmature": int(len(live) - len(matured_live)),
        "newlyCollectedRowsIncluded": int(live_quality["liveRowsIncluded"]),
        **live_quality,
        "maturityCutoff": maturity_cutoff.isoformat(),
        "maxLiveGapHours": max_live_gap_hours,
        "minLiveContiguousHours": min_live_contiguous_hours,
    }
    return training, weather, report


def load_frames(standard: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    db = mongo_database()
    aqi = pd.DataFrame(list(db["aqi_historical_snapshots"].find({"aqiStandard": standard}, {"_id": 0})))
    weather = pd.DataFrame(list(db["historical_weather_observations"].find({}, {"_id": 0})))
    return aqi, weather


def summarize_candidates(training_report: dict) -> dict:
    summary = {}
    for scope, scope_payload in (training_report.get("scopes") or {}).items():
        horizons = {}
        for horizon, metadata in (scope_payload.get("horizons") or {}).items():
            horizons[horizon] = {
                "modelId": metadata.get("modelId"),
                "modelFamily": metadata.get("modelFamily"),
                "promotionStatus": metadata.get("promotionStatus"),
                "metrics": metadata.get("metrics"),
                "baselineMetrics": metadata.get("baselineMetrics"),
                "bestBaseline": metadata.get("bestBaseline"),
                "currentPromotedComparison": metadata.get("currentPromotedComparison"),
            }
        summary[scope] = {"horizons": horizons}
    return summary


def run_cycle(args: argparse.Namespace) -> dict:
    now = datetime.now(timezone.utc)
    started_at = now.isoformat()
    db = mongo_database()
    run_doc = {
        "status": "RUNNING",
        "startedAt": now,
        "trigger": args.trigger,
        "qualityOnly": bool(args.quality_only),
        "schedule": args.schedule,
    }
    inserted = None if args.no_db_write else db["forecast_retraining_runs"].insert_one(run_doc)

    try:
        aqi, weather = load_frames(args.standard)
        training_aqi, training_weather, inclusion = verified_training_frames(
            aqi,
            weather,
            args.standard,
            now,
            max(args.horizons),
            args.max_live_gap_hours,
            args.min_live_contiguous_hours or max(args.horizons) + 1,
        )
        bundle = build_hourly_dataset(training_aqi, training_weather)
        quality = bundle.quality_report
        output_dir = Path(args.model_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        write_json(output_dir / "scheduled_retraining_quality.json", {
            "generatedAt": started_at,
            "inclusion": inclusion,
            "quality": quality,
        })

        result = {
            "status": "QUALITY_VALIDATED" if args.quality_only or args.dry_run else "COMPLETED",
            "startedAt": started_at,
            "completedAt": datetime.now(timezone.utc).isoformat(),
            "standard": args.standard,
            "horizons": args.horizons,
            "scopes": args.scopes,
            "newlyCollectedRowsIncluded": inclusion.get("newlyCollectedRowsIncluded", 0),
            "inclusion": inclusion,
            "quality": quality,
            "candidateMetrics": {},
            "promotionResult": "NOT_RUN_QUALITY_ONLY" if args.quality_only or args.dry_run else "NO_CANDIDATES",
        }
        if not args.quality_only and not args.dry_run:
            training_report = train({
                "aqiRecords": training_aqi.to_dict("records"),
                "weatherRecords": training_weather.to_dict("records") if not training_weather.empty else [],
                "modelDir": args.model_dir,
                "aqiStandard": args.standard,
                "writeRegistry": True,
                "scopes": args.scopes,
                "horizons": args.horizons,
                "versionSuffix": args.version_suffix or now.strftime("%Y%m%d%H%M%S"),
                "minSamples": args.min_samples,
                "minRmseImprovementPercent": args.min_rmse_improvement_percent,
                "maxAbsoluteBias": args.max_absolute_bias,
            })
            result["candidateMetrics"] = summarize_candidates(training_report)
            statuses = [
                h.get("promotionStatus")
                for scope in result["candidateMetrics"].values()
                for h in (scope.get("horizons") or {}).values()
            ]
            result["promotionResult"] = "PROMOTED" if "PROMOTED" in statuses else "NO_PROMOTION"
        if inserted:
            db["forecast_retraining_runs"].update_one(
                {"_id": inserted.inserted_id},
                {"$set": {**result, "completedAt": datetime.now(timezone.utc), "status": result["status"]}},
            )
            if not args.quality_only and not args.dry_run:
                db["forecast_retraining_requests"].update_many(
                    {"status": {"$in": ["PENDING_EXTERNAL_PIPELINE", "IN_PROGRESS"]}},
                    {"$set": {"status": "PROCESSED_BY_SCHEDULED_TRAINING", "processedAt": datetime.now(timezone.utc)}},
                )
        return result
    except Exception as exc:
        failed = {
            "status": "FAILED",
            "startedAt": started_at,
            "completedAt": datetime.now(timezone.utc).isoformat(),
            "error": str(exc),
        }
        if inserted:
            db["forecast_retraining_runs"].update_one(
                {"_id": inserted.inserted_id},
                {"$set": {**failed, "completedAt": datetime.now(timezone.utc)}},
            )
        raise


def parse_csv_ints(value: str) -> list[int]:
    return [int(item.strip()) for item in value.split(",") if item.strip()]


def parse_csv_strings(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run quality-gated scheduled AQI model retraining.")
    parser.add_argument("--standard", default="INDIA_NAQI")
    parser.add_argument("--model-dir", default="models")
    parser.add_argument("--scopes", type=parse_csv_strings, default=list(GLOBAL_TIERS))
    parser.add_argument("--horizons", type=parse_csv_ints, default=[24, 48, 72])
    parser.add_argument("--max-live-gap-hours", type=float, default=1.5)
    parser.add_argument("--min-live-contiguous-hours", type=int, default=0)
    parser.add_argument("--min-samples", type=int, default=200)
    parser.add_argument("--min-rmse-improvement-percent", type=float, default=5.0)
    parser.add_argument("--max-absolute-bias", type=float, default=20.0)
    parser.add_argument("--version-suffix", default="")
    parser.add_argument("--schedule", default="weekly")
    parser.add_argument("--trigger", default="scheduled")
    parser.add_argument("--quality-only", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-db-write", action="store_true")
    args = parser.parse_args()
    print(json.dumps(run_cycle(args), indent=2, default=str))


if __name__ == "__main__":
    main()
