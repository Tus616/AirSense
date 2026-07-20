from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_DIR = ROOT / "ai-service"


def main() -> None:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(AI_DIR)
    command = [sys.executable, "-m", "forecasting.training.train", "--config", "train_config.json"]
    subprocess.run(command, cwd=AI_DIR, env=env, check=True)


if __name__ == "__main__":
    main()
