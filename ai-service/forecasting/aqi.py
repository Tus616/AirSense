from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class PollutantIndex:
    pollutant: str
    concentration: float
    sub_index: int
    unit: str


BREAKPOINTS = {
    "pm25": ([0, 31, 61, 91, 121, 251], [30, 60, 90, 120, 250, 500]),
    "pm10": ([0, 51, 101, 251, 351, 431], [50, 100, 250, 350, 430, 600]),
    "no2": ([0, 41, 81, 181, 281, 401], [40, 80, 180, 280, 400, 800]),
    "so2": ([0, 41, 81, 381, 801, 1601], [40, 80, 380, 800, 1600, 2400]),
    "o3": ([0, 51, 101, 169, 209, 749], [50, 100, 168, 208, 748, 1000]),
    "co": ([0, 1.1, 2.1, 10.1, 17.1, 34.1], [1, 2, 10, 17, 34, 50]),
    "nh3": ([0, 201, 401, 801, 1201, 1801], [200, 400, 800, 1200, 1800, 2400]),
}

INDEX_LOW = [0, 51, 101, 201, 301, 401]
INDEX_HIGH = [50, 100, 200, 300, 400, 500]


def sub_index(pollutant: str, value: object) -> Optional[PollutantIndex]:
    key = normalize_pollutant(pollutant)
    concentration = parse_number(value)
    if key not in BREAKPOINTS or concentration is None or concentration < 0:
        return None
    c_low, c_high = BREAKPOINTS[key]
    for lo, hi, ilo, ihi in zip(c_low, c_high, INDEX_LOW, INDEX_HIGH):
        if lo <= concentration <= hi:
            if hi == lo:
                score = ihi
            else:
                score = round(((ihi - ilo) / (hi - lo)) * (concentration - lo) + ilo)
            return PollutantIndex(key, concentration, int(max(0, min(500, score))), unit_for(key))
    return None


def calculate_indian_naqi(values: dict) -> tuple[Optional[int], Optional[str], list[PollutantIndex], list[str]]:
    indices = []
    warnings = []
    for pollutant in ("pm25", "pm10", "no2", "so2", "co", "o3", "nh3"):
        idx = sub_index(pollutant, values.get(pollutant))
        if idx:
            indices.append(idx)
        elif values.get(pollutant) is not None:
            warnings.append(f"{pollutant.upper()}_INVALID_OR_OUT_OF_RANGE")
    has_pm = any(i.pollutant in {"pm25", "pm10"} for i in indices)
    if len(indices) < 3 or not has_pm:
        warnings.append("AQI_REQUIRES_3_VALID_POLLUTANTS_INCLUDING_PM")
        return None, None, indices, warnings
    dominant = max(indices, key=lambda item: item.sub_index)
    return dominant.sub_index, dominant.pollutant.upper(), indices, warnings


def normalize_pollutant(value: str) -> str:
    text = (value or "").strip().lower().replace(".", "").replace("_", "")
    return {"pm2.5": "pm25", "pm25": "pm25", "pm2_5": "pm25"}.get(text, text)


def parse_number(value: object) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def unit_for(pollutant: str) -> str:
    return "mg/m3" if pollutant == "co" else "ug/m3"

