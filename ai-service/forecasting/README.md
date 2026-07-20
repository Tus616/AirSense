# AirSense Forecasting Pipeline

This package implements the real-data forecasting path. It does not promote a
model just because training completes.

Example commands:

```bash
python -m forecasting.data.backfill_cpcb --city Delhi --start 2024-01-01 --end 2026-01-01 --input cpcb.csv
python -m forecasting.data.backfill_weather --city Delhi --start 2024-01-01 --end 2026-01-01 --input weather.csv
python -m forecasting.training.train --config config.json
python -m forecasting.evaluation.evaluate --model ai-service/forecasting/models/aqi-24h-hgb-v1.json
python -m pytest ai-service/forecasting/tests
```

Historical weather must come from a real provider export. When it is absent,
the dataset builder creates pollutant-only rows and the quality report records
weather missingness.

