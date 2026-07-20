from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "ai-service" / "models" / "training_report.json"
OUT = ROOT / "ai-service" / "data" / "processed" / "cpcb" / "global_evaluation_report.json"


def main() -> None:
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    global_scope = report.get("scopes", {}).get("GLOBAL", {})
    horizons = global_scope.get("horizons", {})
    payload = {
        "datasetChecksum": report.get("datasetChecksum"),
        "quality": report.get("quality", {}),
        "globalHorizonResults": {
            horizon: {
                "promotionStatus": meta.get("promotionStatus"),
                "modelFamily": meta.get("modelFamily"),
                "metrics": meta.get("metrics"),
                "bestBaseline": meta.get("bestBaseline"),
                "baselineMetrics": meta.get("baselineMetrics"),
                "walkForwardStable": meta.get("walkForwardStable"),
            }
            for horizon, meta in sorted(horizons.items(), key=lambda item: int(item[0]))
        },
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
