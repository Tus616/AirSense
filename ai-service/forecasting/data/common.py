from __future__ import annotations

import csv
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional

import pandas as pd


IST = "Asia/Kolkata"


def read_records(path: str | Path) -> list[dict]:
    path = Path(path)
    if path.suffix.lower() in {".json", ".jsonl"}:
        text = path.read_text(encoding="utf-8-sig")
        if path.suffix.lower() == ".jsonl":
            return [json.loads(line) for line in text.splitlines() if line.strip()]
        loaded = json.loads(text)
        return loaded if isinstance(loaded, list) else loaded.get("records", [])
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def parse_timestamp(value: object, source_tz: str = IST) -> Optional[pd.Timestamp]:
    if value is None or str(value).strip() == "":
        return None
    ts = pd.to_datetime(value, errors="coerce", dayfirst=True)
    if pd.isna(ts):
        return None
    if ts.tzinfo is None:
        ts = ts.tz_localize(source_tz)
    return ts.tz_convert("UTC")


def clean_number(value: object, minimum: float | None = None, maximum: float | None = None) -> Optional[float]:
    if value is None or str(value).strip() == "":
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    if minimum is not None and parsed < minimum:
        return None
    if maximum is not None and parsed > maximum:
        return None
    return parsed


def sha256_file(path: str | Path) -> str:
    import hashlib

    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def mongo_database():
    import pymongo

    uri = os.getenv("MONGODB_URI", "mongodb://localhost:27017/airsense")
    database = os.getenv("MONGODB_DATABASE", "airsense")
    return pymongo.MongoClient(uri)[database]


def write_json(path: str | Path, payload: object) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
