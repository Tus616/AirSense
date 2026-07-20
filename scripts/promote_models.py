from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_DIR = ROOT / "ai-service"
OUT = AI_DIR / "data" / "processed" / "cpcb" / "promotion_report.json"
sys.path.insert(0, str(AI_DIR))

from forecasting.data.common import mongo_database  # noqa: E402


def main() -> None:
    db = mongo_database()
    promoted = list(db["forecast_model_registry"].find(
        {"aqiStandard": "INDIA_NAQI", "promotionStatus": "PROMOTED"},
        {"_id": 0}
    ).sort([("modelScope", 1), ("horizonHours", 1), ("promotedAt", -1)]))
    winners = {}
    deactivated = []
    for model in promoted:
        key = (model.get("aqiStandard"), model.get("modelScope"), model.get("horizonHours"))
        if key not in winners:
            winners[key] = model
            continue
        db["forecast_model_registry"].update_one(
            {"modelId": model.get("modelId")},
            {"$set": {
                "active": False,
                "rollbackEligible": True,
                "rollbackReason": f"Superseded by {winners[key].get('modelId')}",
                "deactivatedAt": datetime.now(timezone.utc),
                "updatedAt": datetime.now(timezone.utc),
            }},
        )
        deactivated.append(model.get("modelId"))
    active = list(db["forecast_model_registry"].find(
        {"aqiStandard": "INDIA_NAQI", "active": True},
        {"_id": 0}
    ).sort([("modelScope", 1), ("horizonHours", 1)]))
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "status": "REGISTRY_REPORTED_NO_FORCED_PROMOTION",
        "reason": "Training code writes registry entries only when promotion gates pass.",
        "deactivatedSupersededActiveModels": deactivated,
        "activeModelCount": len(active),
        "activeModels": active,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    print(json.dumps(payload, indent=2, default=str))


if __name__ == "__main__":
    main()
