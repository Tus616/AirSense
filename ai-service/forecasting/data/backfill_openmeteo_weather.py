"""Backfill historical hourly weather from Open-Meteo Historical Weather API.

Open-Meteo provides free ERA5 reanalysis weather data.  No API key required.

Usage:
    python -m forecasting.data.backfill_openmeteo_weather \\
        --lat 28.999 --lon 77.759 \\
        --station "Ganga Nagar, Meerut - UPPCB" \\
        --city Meerut \\
        --start 2025-01-01 --end 2025-12-31
"""
from __future__ import annotations

import argparse
import json
import urllib.request
from datetime import datetime, timezone
from typing import Optional

import pandas as pd

from forecasting.data.common import mongo_database, write_json


OPEN_METEO_WEATHER_URL = "https://archive-api.open-meteo.com/v1/archive"


def fetch_openmeteo_weather(lat: float, lon: float, start: str, end: str) -> dict:
    params = (
        f"latitude={lat}&longitude={lon}"
        f"&hourly=temperature_2m,relative_humidity_2m,surface_pressure,"
        f"wind_speed_10m,wind_direction_10m,precipitation,cloud_cover"
        f"&start_date={start}&end_date={end}"
        f"&timezone=UTC"
    )
    url = f"{OPEN_METEO_WEATHER_URL}?{params}"
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def parse_response(
    payload: dict, station_name: str, lat: float, lon: float
) -> list[dict]:
    hourly = payload.get("hourly", {})
    timestamps = hourly.get("time", [])
    temp = hourly.get("temperature_2m", [])
    humidity = hourly.get("relative_humidity_2m", [])
    pressure = hourly.get("surface_pressure", [])
    wind_speed = hourly.get("wind_speed_10m", [])
    wind_dir = hourly.get("wind_direction_10m", [])
    precip = hourly.get("precipitation", [])
    cloud = hourly.get("cloud_cover", [])

    location_key = f"in:{round(lat, 3):.3f}:{round(lon, 3):.3f}"
    records: list[dict] = []

    for i, ts_str in enumerate(timestamps):
        ts = pd.Timestamp(ts_str, tz="UTC")
        records.append({
            "locationKey": location_key,
            "provider": "OPEN_METEO_WEATHER",
            "observedAt": ts.to_pydatetime(),
            "temperatureCelsius": _safe(temp, i),
            "humidityPercent": _safe(humidity, i),
            "pressureHpa": _safe(pressure, i),
            "windSpeedMps": _mps(_safe(wind_speed, i)),  # km/h → m/s
            "windDirectionDegrees": _safe(wind_dir, i),
            "rainfallMm": _safe(precip, i),
            "cloudCoverPercent": _safe(cloud, i),
            "ingestedAt": datetime.now(timezone.utc),
            "dataOrigin": "REANALYSIS",
            "stationName": station_name,
        })
    return records


def _safe(values: list, index: int) -> Optional[float]:
    if index >= len(values) or values[index] is None:
        return None
    try:
        return float(values[index])
    except (TypeError, ValueError):
        return None


def _mps(kmh: Optional[float]) -> Optional[float]:
    """Convert km/h to m/s."""
    return round(kmh / 3.6, 2) if kmh is not None else None


def upsert(records: list[dict], dry_run: bool = False) -> dict:
    collection = None if dry_run else mongo_database()["historical_weather_observations"]
    inserted = updated = skipped = 0
    for doc in records:
        key = {
            "locationKey": doc["locationKey"],
            "provider": doc["provider"],
            "observedAt": doc["observedAt"],
        }
        if dry_run:
            inserted += 1
            continue
        
        insert_doc = dict(doc)
        insert_doc.pop("ingestedAt", None)
        
        result = collection.update_one(
            key,
            {"$setOnInsert": insert_doc, "$set": {"ingestedAt": doc["ingestedAt"]}},
            upsert=True,
        )
        inserted += 1 if result.upserted_id else 0
        updated += 0 if result.upserted_id else result.modified_count
    return {"inserted": inserted, "updated": updated, "skipped": skipped, "total": len(records)}


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill ERA5 weather from Open-Meteo")
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--station", required=True)
    parser.add_argument("--city", default="")
    parser.add_argument("--start", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="YYYY-MM-DD")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", default="forecasting/data/backfill_openmeteo_weather_report.json")
    args = parser.parse_args()

    print(f"Fetching Open-Meteo Weather: ({args.lat}, {args.lon}) {args.start}–{args.end}")
    payload = fetch_openmeteo_weather(args.lat, args.lon, args.start, args.end)
    records = parse_response(payload, args.station, args.lat, args.lon)
    result = upsert(records, dry_run=args.dry_run)
    result.update({
        "source": "OPEN_METEO_WEATHER",
        "station": args.station,
        "city": args.city,
        "start": args.start,
        "end": args.end,
    })
    write_json(args.report, result)
    print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    main()
