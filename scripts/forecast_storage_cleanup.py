"""
Dry-run cleanup helper for legacy AirSense forecast storage.

Default behavior is read-only. Use --apply only after reviewing the printed
collection counts and taking a database backup.
"""

from __future__ import annotations

import argparse
import os
from datetime import datetime, timezone

from pymongo import MongoClient


LEGACY_COLLECTIONS = [
    "predictions",
    "grid_forecasts",
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect or delete legacy forecast collections.")
    parser.add_argument("--mongo-uri", default=os.getenv("MONGODB_URI", "mongodb://localhost:27017/airsense"))
    parser.add_argument("--database", default=os.getenv("MONGODB_DATABASE", "airsense"))
    parser.add_argument("--apply", action="store_true", help="Delete documents from legacy collections.")
    args = parser.parse_args()

    db = MongoClient(args.mongo_uri)[args.database]
    print(f"Forecast cleanup started at {datetime.now(timezone.utc).isoformat()}")
    print(f"Database: {args.database}")
    print(f"Mode: {'APPLY' if args.apply else 'DRY_RUN'}")

    for collection_name in LEGACY_COLLECTIONS:
        collection = db[collection_name]
        count = collection.count_documents({})
        print(f"{collection_name}: {count} documents")
        if args.apply and count:
            result = collection.delete_many({})
            print(f"{collection_name}: deleted {result.deleted_count} documents")

    print("Authoritative forecast records are retained in the forecast run collection used by ForecastOrchestrator.")


if __name__ == "__main__":
    main()
