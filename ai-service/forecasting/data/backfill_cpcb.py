from __future__ import annotations

import argparse
import os
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Iterable

from forecasting.aqi import calculate_indian_naqi
from forecasting.data.common import clean_number, mongo_database, parse_timestamp, read_records, write_json

POLLUTANT_KEYS = {
    "pm25": ["pm25", "pm2.5", "PM2.5", "PM25"],
    "pm10": ["pm10", "PM10"],
    "no2": ["no2", "NO2"],
    "so2": ["so2", "SO2"],
    "co": ["co", "CO"],
    "o3": ["o3", "O3"],
    "nh3": ["nh3", "NH3"],
}

DATA_GOV_POLLUTANT_ALIASES = {
    "pm2.5": "PM2.5",
    "pm25": "PM2.5",
    "pm10": "PM10",
    "no2": "NO2",
    "so2": "SO2",
    "co": "CO",
    "o3": "O3",
    "ozone": "O3",
    "nh3": "NH3",
}


def first(row: dict, *keys: str):
    for key in keys:
        if key in row and row[key] not in (None, ""):
            return row[key]
    return None


def coalesce_pollutant_rows(rows: Iterable[dict]) -> list[dict]:
    grouped: dict[tuple, dict] = {}
    passthrough: list[dict] = []
    for row in rows:
        pollutant = first(row, "pollutant_id", "pollutant")
        avg_value = first(row, "avg_value", "avgValue")
        if pollutant is None or avg_value is None:
            passthrough.append(row)
            continue
        observed = first(row, "timestamp", "last_update", "lastUpdated", "sampling_date", "providerObservedAt")
        station = first(row, "station", "station_name", "stationName", "station_id", "stationId")
        key = (
            str(station or "").strip(),
            str(first(row, "city", "City") or "").strip(),
            str(first(row, "state", "State") or "").strip(),
            str(observed or "").strip(),
            str(first(row, "latitude", "lat", "stationLatitude") or "").strip(),
            str(first(row, "longitude", "lon", "lng", "stationLongitude") or "").strip(),
        )
        target = grouped.setdefault(key, {
            "station": station,
            "station_name": station,
            "city": first(row, "city", "City"),
            "state": first(row, "state", "State"),
            "timestamp": observed,
            "latitude": first(row, "latitude", "lat", "stationLatitude"),
            "longitude": first(row, "longitude", "lon", "lng", "stationLongitude"),
            "sourceRowFormat": "DATA_GOV_POLLUTANT_ROWS",
        })
        canonical = DATA_GOV_POLLUTANT_ALIASES.get(str(pollutant).strip().lower())
        if canonical:
            target[canonical] = avg_value
    return passthrough + list(grouped.values())


def normalize_row(row: dict, source_tz: str = "Asia/Kolkata") -> dict | None:
    observed = parse_timestamp(first(row, "timestamp", "last_update", "lastUpdated", "sampling_date", "providerObservedAt"), source_tz)
    if observed is None:
        return None
    pollutants = {}
    for canonical, keys in POLLUTANT_KEYS.items():
        pollutants[canonical] = clean_number(first(row, *keys), 0, 50000 if canonical == "co" else 5000)
    aqi, primary, indices, warnings = calculate_indian_naqi(pollutants)
    station_id = str(first(row, "station_id", "stationId", "station_code", "stationName", "station") or "").strip()
    station_name = str(first(row, "station_name", "stationName", "station") or station_id).strip()
    city = str(first(row, "city", "City") or "").strip()
    state = str(first(row, "state", "State") or "").strip()
    lat = clean_number(first(row, "latitude", "lat", "stationLatitude"), -90, 90)
    lon = clean_number(first(row, "longitude", "lon", "lng", "stationLongitude"), -180, 180)
    location_key = str(first(row, "locationKey", "canonicalLocationKey") or f"in:{round(lat or 0, 3):.3f}:{round(lon or 0, 3):.3f}")
    quality = "VALID" if aqi is not None else "INVALID"
    return {
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
        "provider": "CPCB_CAAQMS",
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
        "nh3": pollutants["nh3"],
        "providerObservedAt": observed.to_pydatetime(),
        "ingestedAt": datetime.now(timezone.utc),
        "dataOrigin": "OBSERVED",
        "dataQualityStatus": quality,
        "dataQualityWarnings": warnings,
        "sourceTimestamp": str(first(row, "timestamp", "last_update", "lastUpdated", "sampling_date", "providerObservedAt") or ""),
        "subIndexes": [idx.__dict__ for idx in indices],
        "coUnitHandling": "CO is stored and scored in CPCB mg/m3 breakpoints; no OpenWeather ug/m3 conversion is applied.",
        "stationId": station_id,
    }


def fetch_data_gov_records(resource_id: str, api_key: str, city: str | None, start: str | None, end: str | None, limit: int) -> list[dict]:
    base = os.getenv("DATA_GOV_IN_BASE_URL", "https://api.data.gov.in/resource")
    offset = 0
    records: list[dict] = []
    while True:
        params = {
            "api-key": api_key,
            "format": "json",
            "limit": str(limit),
            "offset": str(offset),
        }
        if city:
            params["filters[city]"] = city
        url = f"{base.rstrip('/')}/{resource_id}?{urllib.parse.urlencode(params)}"
        with urllib.request.urlopen(url, timeout=30) as response:
            payload = __import__("json").loads(response.read().decode("utf-8"))
        page = payload.get("records", [])
        records.extend(page)
        if len(page) < limit:
            break
        offset += limit
    return records


def upsert(records: Iterable[dict], dry_run: bool = False) -> dict:
    collection = None if dry_run else mongo_database()["aqi_historical_snapshots"]
    inserted = updated = skipped = invalid = 0
    normalized_records = coalesce_pollutant_rows(records)
    for row in normalized_records:
        doc = normalize_row(row)
        if not doc:
            skipped += 1
            continue
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
        result = collection.update_one(key, {"$setOnInsert": insert_doc, "$set": {"ingestedAt": doc["ingestedAt"]}}, upsert=True)
        inserted += 1 if result.upserted_id else 0
        updated += 0 if result.upserted_id else result.modified_count
    return {"inserted": inserted, "updated": updated, "skipped": skipped, "invalid": invalid, "normalizedRecordCount": len(normalized_records)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--city")
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--input")
    parser.add_argument("--resource-id", default=os.getenv("DATA_GOV_IN_CPCB_RESOURCE_ID", "3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69"))
    parser.add_argument("--api-key", default=os.getenv("DATA_GOV_IN_API_KEY") or os.getenv("CPCB_DATA_GOV_IN_API_KEY"))
    parser.add_argument("--limit", type=int, default=1000)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", default="ai-service/forecasting/data/backfill_cpcb_report.json")
    args = parser.parse_args()
    if args.input:
        raw = read_records(args.input)
    elif args.api_key:
        raw = fetch_data_gov_records(args.resource_id, args.api_key, args.city, args.start, args.end, args.limit)
    else:
        raise SystemExit("No --input file or DATA_GOV_IN_API_KEY/CPCB_DATA_GOV_IN_API_KEY configured.")
    result = upsert(raw, dry_run=args.dry_run)
    result.update({"source": "CPCB_CAAQMS", "city": args.city, "start": args.start, "end": args.end, "rawRecordCount": len(raw)})
    write_json(args.report, result)
    print(result)


if __name__ == "__main__":
    main()
