# Final Limitations

- Chronos is optional and disabled by default for low-memory production.
- Provider forecast mode is the expected production forecast path on the current Render service.
- Legacy top-level FastAPI train/predict endpoints remain for backward compatibility but are not the production forecast path.
- Legacy Spring batch forecasting remains only behind `LEGACY_FORECAST_ENABLED=true`.
- Production database cleanup is intentionally not automatic. Any data cleanup must run through a dry-run migration script first.
- Dry-run helper: `scripts/forecast_storage_cleanup.py`; pass `--apply` only after backup and review.
