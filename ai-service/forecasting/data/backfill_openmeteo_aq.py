"""Backfill historical hourly air quality from Open-Meteo API.

Open-Meteo provides free CAMS (Copernicus Atmosphere Monitoring Service)
reanalysis data — real satellite-derived pollutant concentrations, not
fabricated values.  We fetch raw concentrations and compute INDIA_NAQI
using the existing ``calculate_indian_naqi`` function.

Usage:
    python -m forecasting.data.backfill_openmeteo_aq \\
        --lat 28.999 --lon 77.759 \\
        --station "Ganga Nagar, Meerut - UPPCB" \\
        --city Meerut --state "Uttar Pradesh" \\
        --start 2025-01-01 --end 2025-12-31
"""
from __future__ import annotations

import argparse
import json
import os
import urllib.request
from datetime import datetime, timezone
from typing import Optional

import pandas as pd

from forecasting.aqi import calculate_indian_naqi
from forecasting.data.common import mongo_database, write_json


OPEN_METEO_AQ_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

# Open-Meteo returns CO in µg/m³.  CPCB breakpoints use mg/m³.
CO_UG_TO_MG = 1 / 1000.0


def fetch_openmeteo_aq(lat: float, lon: float, start: str, end: str) -> list[dict]:
    """Fetch hourly pollutant concentrations from Open-Meteo Air Quality API."""
    params = (
        f"latitude={lat}&longitude={lon}"
        f"&hourly=pm10,pm2_5,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,ozone"
        f"&start_date={start}&end_date={end}"
        f"&timezone=UTC"
    )
    url = f"{OPEN_METEO_AQ_URL}?{params}"
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def parse_response(
    payload: dict,
    station_name: str,
    city: str,
    state: str,
    lat: float,
    lon: float,
) -> list[dict]:
    """Convert Open-Meteo JSON response to normalised AQI snapshot documents."""
    hourly = payload.get("hourly", {})
    timestamps = hourly.get("time", [])
    pm25_vals = hourly.get("pm2_5", [])
    pm10_vals = hourly.get("pm10", [])
    no2_vals = hourly.get("nitrogen_dioxide", [])
    so2_vals = hourly.get("sulphur_dioxide", [])
    co_vals = hourly.get("carbon_monoxide", [])
    o3_vals = hourly.get("ozone", [])

    location_key = f"in:{round(lat, 3):.3f}:{round(lon, 3):.3f}"
    records: list[dict] = []

    for i, ts_str in enumerate(timestamps):
        ts = pd.Timestamp(ts_str, tz="UTC")

        pollutants = {
            "pm25": _safe_float(pm25_vals, i),
            "pm10": _safe_float(pm10_vals, i),
            "no2": _safe_float(no2_vals, i),
            "so2": _safe_float(so2_vals, i),
            "co": _safe_float(co_vals, i, scale=CO_UG_TO_MG),  # µg/m³ → mg/m³
            "o3": _safe_float(o3_vals, i),
        }

        aqi, primary, indices, warnings = calculate_indian_naqi(pollutants)
        quality = "VALID" if aqi is not None else "INVALID"

        records.append({
            "locationKey": location_key,
            "canonicalLocationKey": location_key,
            "locationKeyVersion": "python-backfill-v1",
            "city": city,
            "state": state,
            "country": "India",
            "latitude": lat,
            "longitude": lon,
            "currentAqi": aqi,
            "aqiStandard": "INDIA_NAQI",
            "primaryPollutant": primary,
            "provider": "OPEN_METEO_AQ",
            "isFallback": False,
            "stationName": station_name,
            "providerReturnedStation": station_name,
            "providerReturnedCity": city,
            "stationLatitude": lat,
            "stationLongitude": lon,
            "pm25": pollutants["pm25"],
            "pm10": pollutants["pm10"],
            "no2": pollutants["no2"],
            "so2": pollutants["so2"],
            "co": pollutants["co"],
            "o3": pollutants["o3"],
            "nh3": None,  # Open-Meteo does not provide NH3
            "providerObservedAt": ts.to_pydatetime(),
            "ingestedAt": datetime.now(timezone.utc),
            "dataOrigin": "REANALYSIS",
            "dataQualityStatus": quality,
            "dataQualityWarnings": warnings,
            "sourceTimestamp": ts_str,
            "subIndexes": [idx.__dict__ for idx in indices],
            "coUnitHandling": "CO converted from Open-Meteo µg/m³ to CPCB mg/m³ breakpoints.",
            "stationId": station_name,
        })

    return records


def _safe_float(
    values: list, index: int, scale: float = 1.0
) -> Optional[float]:
    if index >= len(values) or values[index] is None:
        return None
    try:
        return float(values[index]) * scale
    except (TypeError, ValueError):
        return None


def upsert(records: list[dict], dry_run: bool = False) -> dict:
    collection = None if dry_run else mongo_database()["aqi_historical_snapshots"]
    inserted = updated = skipped = invalid = 0
    for doc in records:
        if doc["dataQualityStatus"] == "INVALID":
            invalid += 1
        key = {
            "locationKey": doc["locationKey"],
            "provider": doc["provider"],
            "aqiStandard": doc["aqiStandard"],
            "providerObservedAt": doc["providerObservedAt"],
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
    return {
        "inserted": inserted,
        "updated": updated,
        "skipped": skipped,
        "invalid": invalid,
        "total": len(records),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill CAMS reanalysis AQ data from Open-Meteo")
    parser.add_argument("--lat", type=float, required=True)
    parser.add_argument("--lon", type=float, required=True)
    parser.add_argument("--station", required=True, help="CPCB station name for labelling")
    parser.add_argument("--city", required=True)
    parser.add_argument("--state", default="")
    parser.add_argument("--start", required=True, help="YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="YYYY-MM-DD")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", default="forecasting/data/backfill_openmeteo_aq_report.json")
    args = parser.parse_args()

    print(f"Fetching Open-Meteo AQ: {args.station} ({args.lat}, {args.lon}) {args.start}–{args.end}")
    payload = fetch_openmeteo_aq(args.lat, args.lon, args.start, args.end)
    records = parse_response(payload, args.station, args.city, args.state, args.lat, args.lon)
    result = upsert(records, dry_run=args.dry_run)
    result.update({
        "source": "OPEN_METEO_AQ",
        "station": args.station,
        "city": args.city,
        "lat": args.lat,
        "lon": args.lon,
        "start": args.start,
        "end": args.end,
    })
    write_json(args.report, result)
    print(json.dumps(result, indent=2, default=str))


if __name__ == "__main__":
    main()
