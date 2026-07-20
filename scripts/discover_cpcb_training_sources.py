from __future__ import annotations

import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "ai-service" / "data" / "raw" / "cpcb"
OUT_DIR = ROOT / "ai-service" / "data" / "processed" / "cpcb"
OUT_FILE = OUT_DIR / "training_sources_manifest.json"
NAME_PATTERN = re.compile(r"(?P<station>.+)_(?P<year>\d{4})_(?P<month>\d{2})\.xlsx(?:\.xlsx)?$", re.IGNORECASE)


def main() -> None:
    sources = []
    grouped = defaultdict(list)
    for path in sorted(RAW_DIR.rglob("*.xlsx*")):
        match = NAME_PATTERN.match(path.name)
        station_key = match.group("station") if match else "UNKNOWN"
        month = f"{match.group('year')}-{match.group('month')}" if match else None
        item = {
            "path": str(path.relative_to(ROOT)),
            "stationArchiveKey": station_key,
            "cityFolder": path.parent.name,
            "month": month,
            "sizeBytes": path.stat().st_size,
            "sourceType": "LOCAL_CPCB_XLSX_ARCHIVE",
        }
        sources.append(item)
        grouped[station_key].append(item)

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "rawDirectory": str(RAW_DIR.relative_to(ROOT)),
        "fileCount": len(sources),
        "stationCount": len(grouped),
        "stations": {
            key: {
                "fileCount": len(items),
                "months": sorted({item["month"] for item in items if item["month"]}),
                "cityFolders": sorted({item["cityFolder"] for item in items}),
            }
            for key, items in sorted(grouped.items())
        },
        "sources": sources,
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
