"""Leakage-safe AQI forecasting pipeline for AirSense.

This package is intentionally separate from the legacy demo forecast files in
``ai-service``. Training and inference here use only timestamped real records,
chronological validation, persistence baselines and promotion gates.
"""

