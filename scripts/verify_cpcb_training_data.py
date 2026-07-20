from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd


ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "ai-service" / "data" / "raw" / "cpcb"
OUT_DIR = ROOT / "ai-service" / "data" / "processed" / "cpcb"
OUT_FILE = OUT_DIR / "verification_report.json"
NAME_PATTERN = re.compile(r"(?P<station>.+)_(?P<year>\d{4})_(?P<month>\d{2})\.xlsx(?:\.xlsx)?$", re.IGNORECASE)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    files = []
    station_summary = defaultdict(lambda: {"files": 0, "readableFiles": 0, "nonEmptyFiles": 0, "rows": 0, "months": set()})
    for path in sorted(RAW_DIR.rglob("*.xlsx*")):
        match = NAME_PATTERN.match(path.name)
        station = match.group("station") if match else "UNKNOWN"
        month = f"{match.group('year')}-{match.group('month')}" if match else None
        status = "VERIFIED"
        rows = 0
        columns = []
        error = None
        try:
            frame = pd.read_excel(path)
            rows = int(len(frame))
            columns = [str(col) for col in frame.columns]
            if frame.empty or "Date" not in frame.columns:
                status = "EMPTY_OR_UNSUPPORTED"
        except Exception as exc:
            status = "UNREADABLE"
            error = str(exc)
        files.append({
            "path": str(path.relative_to(ROOT)),
            "stationArchiveKey": station,
            "month": month,
            "status": status,
            "rows": rows,
            "columns": columns[:30],
            "sha256": sha256(path),
            "error": error,
        })
        summary = station_summary[station]
        summary["files"] += 1
        summary["rows"] += rows
        if status != "UNREADABLE":
            summary["readableFiles"] += 1
        if status == "VERIFIED":
            summary["nonEmptyFiles"] += 1
        if month:
            summary["months"].add(month)

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": "VERIFIED" if files and all(item["status"] != "UNREADABLE" for item in files) else "NEEDS_ATTENTION",
        "fileCount": len(files),
        "stationCount": len(station_summary),
        "stations": {
            key: {
                "files": value["files"],
                "readableFiles": value["readableFiles"],
                "nonEmptyFiles": value["nonEmptyFiles"],
                "rows": value["rows"],
                "months": sorted(value["months"]),
            }
            for key, value in sorted(station_summary.items())
        },
        "files": files,
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
