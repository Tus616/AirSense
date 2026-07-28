from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime, timezone

import httpx

from forecasting.inference.history import finite_number


OPEN_METEO_AQ_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
AQI_FIELD = "us_aqi"
AQI_STANDARD = "US_AQI"


@dataclass(frozen=True)
class ProviderSeries:
    observations: list[dict]
    hourly_forecast: list[dict]
    provider: str
    aqi_standard: str
    selected_aqi_field: str
    metadata: dict


class OpenMeteoAirQualityClient:
    def __init__(self) -> None:
        self.enabled = os.getenv("OPEN_METEO_FORECAST_ENABLED", "true").lower() == "true"
        self.timeout_seconds = float(os.getenv("OPEN_METEO_TIMEOUT_SECONDS", "12"))

    def fetch(self, latitude: float, longitude: float, *, past_hours: int = 168, forecast_hours: int = 96) -> ProviderSeries:
        if not self.enabled:
            raise RuntimeError("OPEN_METEO_DISABLED")
        params = {
            "latitude": latitude,
            "longitude": longitude,
            "hourly": AQI_FIELD,
            "current": AQI_FIELD,
            "past_hours": past_hours,
            "forecast_hours": forecast_hours,
            "timezone": "GMT",
        }
        with httpx.Client(timeout=self.timeout_seconds) as client:
            response = client.get(OPEN_METEO_AQ_URL, params=params)
            response.raise_for_status()
            payload = response.json()
        hourly = payload.get("hourly") or {}
        times = hourly.get("time") or []
        values = hourly.get(AQI_FIELD) or []
        now = datetime.now(timezone.utc)
        observations = []
        forecasts = []
        for timestamp, value in zip(times, values):
            numeric = finite_number(value)
            if numeric is None:
                continue
            iso = str(timestamp)
            if "T" in iso and not iso.endswith("Z"):
                iso = iso + "Z"
            row = {"timestamp": iso, "currentAqi": numeric, "aqiStandard": AQI_STANDARD, "provider": "OPEN_METEO", "selectedAqiField": AQI_FIELD}
            try:
                row_time = datetime.fromisoformat(iso.replace("Z", "+00:00"))
            except ValueError:
                row_time = now
            if row_time <= now:
                observations.append(row)
            else:
                forecasts.append(row)
        return ProviderSeries(
            observations=observations,
            hourly_forecast=forecasts,
            provider="OPEN_METEO",
            aqi_standard=AQI_STANDARD,
            selected_aqi_field=AQI_FIELD,
            metadata={
                "latitude": payload.get("latitude", latitude),
                "longitude": payload.get("longitude", longitude),
                "requestedPastHours": past_hours,
                "requestedForecastHours": forecast_hours,
                "selectedAqiField": AQI_FIELD,
            },
        )
