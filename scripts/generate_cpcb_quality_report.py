from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_DIR = ROOT / "ai-service"
OUT_DIR = AI_DIR / "data" / "processed" / "cpcb"
OUT_FILE = OUT_DIR / "quality_report.json"
sys.path.insert(0, str(AI_DIR))

from forecasting.data.common import mongo_database  # noqa: E402


def main() -> None:
    db = mongo_database()
    pipeline = [
        {"$match": {"provider": "CPCB_CAAQMS", "aqiStandard": "INDIA_NAQI"}},
        {"$group": {
            "_id": "$stationKey",
            "rows": {"$sum": 1},
            "minObservedAt": {"$min": "$providerObservedAt"},
            "maxObservedAt": {"$max": "$providerObservedAt"},
            "origins": {"$addToSet": "$dataOrigin"},
            "locationKeys": {"$addToSet": "$locationKey"},
        }},
        {"$sort": {"rows": -1}},
    ]
    stations = list(db["aqi_historical_snapshots"].aggregate(pipeline))
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "mongoUriConfigured": bool(os.getenv("MONGODB_URI")),
        "stationCount": len(stations),
        "totalRows": sum(item["rows"] for item in stations),
        "stations": [
            {
                "stationKey": item["_id"] or "UNKNOWN",
                "rows": item["rows"],
                "dateRange": [item.get("minObservedAt"), item.get("maxObservedAt")],
                "dataOrigins": sorted(origin for origin in item.get("origins", []) if origin),
                "locationKeys": sorted(key for key in item.get("locationKeys", []) if key),
            }
            for item in stations
        ],
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    print(json.dumps(payload, indent=2, default=str))


if __name__ == "__main__":
    main()
