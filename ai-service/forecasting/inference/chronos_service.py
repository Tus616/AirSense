from __future__ import annotations

import os
from dataclasses import dataclass
from math import isfinite
from threading import Lock


@dataclass(frozen=True)
class ChronosForecast:
    horizon_hours: int
    predicted_aqi: float
    lower_bound: float
    upper_bound: float


class ChronosService:
    def __init__(self) -> None:
        self.model_id = os.getenv("CHRONOS_MODEL_ID", "amazon/chronos-bolt-tiny")
        self.device = os.getenv("CHRONOS_DEVICE", "cpu")
        self.enabled = os.getenv("CHRONOS_ENABLED", "false").lower() == "true"
        self.min_history_hours = int(os.getenv("CHRONOS_MIN_HISTORY_HOURS", "48"))
        self.context_hours = int(os.getenv("CHRONOS_CONTEXT_HOURS", "168"))
        self._pipeline = None
        self._load_error: str | None = None
        self._lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._pipeline is not None

    @property
    def last_error(self) -> str | None:
        return self._load_error

    def status(self) -> dict:
        return {
            "modelId": self.model_id,
            "modelLoaded": self.loaded,
            "device": self.device,
            "enabled": self.enabled,
            "minHistoryHours": self.min_history_hours,
            "contextHours": self.context_hours,
            "lastModelError": self._load_error,
        }

    def load(self) -> bool:
        if not self.enabled:
            self._load_error = "CHRONOS_DISABLED"
            return False
        if self._pipeline is not None:
            return True
        with self._lock:
            if self._pipeline is not None:
                return True
            try:
                from chronos import ChronosBoltPipeline, ChronosPipeline

                kwargs = {"device_map": self.device}
                pipeline_cls = ChronosBoltPipeline if "chronos-bolt" in self.model_id.lower() else ChronosPipeline
                self._pipeline = pipeline_cls.from_pretrained(self.model_id, **kwargs)
                self._load_error = None
                return True
            except Exception as exc:
                self._load_error = f"{type(exc).__name__}: {exc}"
                self._pipeline = None
                return False

    def predict(self, values: list[float], horizons: list[int]) -> tuple[dict[int, ChronosForecast], str | None]:
        cleaned = [float(v) for v in values if v is not None and isfinite(float(v))]
        if len(cleaned) < self.min_history_hours:
            return {}, "INSUFFICIENT_HISTORY_FOR_CHRONOS"
        if not self.load():
            return {}, self._load_error or "CHRONOS_LOAD_FAILED"
        max_horizon = max(horizons)
        context = cleaned[-self.context_hours :]
        try:
            import torch

            with torch.inference_mode():
                quantiles, _mean = self._pipeline.predict_quantiles(
                    torch.tensor(context, dtype=torch.float32),
                    prediction_length=max_horizon,
                    quantile_levels=[0.1, 0.5, 0.9],
                )
            forecasts: dict[int, ChronosForecast] = {}
            q = quantiles[0].detach().cpu()
            for horizon in horizons:
                lower = float(q[horizon - 1, 0].item())
                median = float(q[horizon - 1, 1].item())
                upper = float(q[horizon - 1, 2].item())
                if all(isfinite(v) for v in [lower, median, upper]):
                    forecasts[horizon] = ChronosForecast(
                        horizon_hours=horizon,
                        predicted_aqi=max(0.0, min(500.0, median)),
                        lower_bound=max(0.0, min(500.0, lower)),
                        upper_bound=max(0.0, min(500.0, upper)),
                    )
            return forecasts, None
        except Exception as exc:
            self._load_error = f"{type(exc).__name__}: {exc}"
            return {}, self._load_error
