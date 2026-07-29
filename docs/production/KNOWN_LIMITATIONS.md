# Known Limitations

- Chronos is optional and disabled by default for low-memory production.
- Open-Meteo provider forecasts are atmospheric provider forecasts, not a locally trained/promoted ML model.
- Source attribution is evidence fusion, not laboratory source-apportionment.
- Production database cleanup is not automatic.
- Optional database cleanup helper: `scripts/forecast_storage_cleanup.py`. It is dry-run by default and inspects legacy `predictions` and `grid_forecasts` collections.
- Deployed acceptance is only complete when Render and Vercel serve the same final commit.
