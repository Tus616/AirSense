from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Path to model metadata JSON.")
    args = parser.parse_args()
    metadata = json.loads(Path(args.model).read_text(encoding="utf-8"))
    print(json.dumps({
        "modelId": metadata.get("modelId"),
        "horizonHours": metadata.get("horizonHours"),
        "metrics": metadata.get("metrics"),
        "baselineMetrics": metadata.get("baselineMetrics"),
        "promotionStatus": metadata.get("promotionStatus"),
    }, indent=2))


if __name__ == "__main__":
    main()

