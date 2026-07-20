from __future__ import annotations

import argparse
from datetime import datetime, timezone

from forecasting.data.common import clean_number, mongo_database, parse_timestamp, read_records, write_json


def first(row: dict, *keys: str):
    for key in keys:
        if key in row and row[key] not in (None, ""):
            return row[key]
    return None


def normalize_row(row: dict, source_tz: str = "UTC") -> dict | None:
    observed = parse_timestamp(first(row, "timestamp", "observedAt", "time"), source_tz)
    if observed is None:
        return None
    lat = clean_number(first(row, "latitude", "lat"), -90, 90)
    lon = clean_number(first(row, "longitude", "lon", "lng"), -180, 180)
    location_key = str(first(row, "locationKey") or f"in:{round(lat or 0, 3):.3f}:{round(lon or 0, 3):.3f}")
    return {
        "locationKey": location_key,
        "provider": str(first(row, "provider") or "IMPORTED_HISTORICAL_WEATHER"),
        "observedAt": observed.to_pydatetime(),
        "temperatureCelsius": clean_number(first(row, "temperatureCelsius", "temperature", "temp")),
        "humidityPercent": clean_number(first(row, "humidityPercent", "humidity"), 0, 100),
        "pressureHpa": clean_number(first(row, "pressureHpa", "pressure"), 800, 1200),
        "windSpeedMps": clean_number(first(row, "windSpeedMps", "windSpeed"), 0, 100),
        "windDirectionDegrees": clean_number(first(row, "windDirectionDegrees", "windDirection"), 0, 360),
        "rainfallMm": clean_number(first(row, "rainfallMm", "rainfall", "rain"), 0, 1000),
        "cloudCoverPercent": clean_number(first(row, "cloudCoverPercent", "cloudCover"), 0, 100),
        "ingestedAt": datetime.now(timezone.utc),
        "dataOrigin": "OBSERVED_IMPORTED",
    }


def upsert(records: list[dict], dry_run: bool = False) -> dict:
    collection = None if dry_run else mongo_database()["historical_weather_observations"]
    inserted = updated = skipped = 0
    for row in records:
        doc = normalize_row(row)
        if not doc:
            skipped += 1
            continue
        key = {"locationKey": doc["locationKey"], "provider": doc["provider"], "observedAt": doc["observedAt"]}
        if dry_run:
            inserted += 1
            continue
        result = collection.update_one(key, {"$setOnInsert": doc, "$set": {"ingestedAt": doc["ingestedAt"]}}, upsert=True)
        inserted += 1 if result.upserted_id else 0
        updated += 0 if result.upserted_id else result.modified_count
    return {"inserted": inserted, "updated": updated, "skipped": skipped}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--city")
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--input", required=True, help="CSV/JSON exported from a real historical-weather provider.")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", default="ai-service/forecasting/data/backfill_weather_report.json")
    args = parser.parse_args()
    raw = read_records(args.input)
    result = upsert(raw, dry_run=args.dry_run)
    result.update({"source": "IMPORTED_HISTORICAL_WEATHER", "city": args.city, "start": args.start, "end": args.end, "rawRecordCount": len(raw)})
    write_json(args.report, result)
    print(result)


if __name__ == "__main__":
    main()

