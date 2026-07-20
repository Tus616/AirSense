from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "ai-service" / "data" / "processed" / "cpcb"
OUT_FILE = OUT_DIR / "download_report.json"


def main() -> None:
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": "SKIPPED_EXTERNAL_DOWNLOAD",
        "reason": "No verified CPCB historical bulk-download credential/source is configured; using local CPCB XLSX archive only.",
        "localArchiveDirectory": "ai-service/data/raw/cpcb",
    }
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
