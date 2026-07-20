from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import pandas as pd

from forecasting.data.backfill_cpcb import coalesce_pollutant_rows, normalize_row
from forecasting.evaluation.metrics import evaluate_predictions, promotion_status
from forecasting.features.dataset import build_hourly_dataset, per_station_walk_forward_splits
from forecasting.training.scheduled_retrain import verified_training_frames
from forecasting.training.train import feature_columns_for_tier, heldout_failures, train


def sample_aqi(hours: int = 220) -> pd.DataFrame:
    start = pd.Timestamp("2026-01-01T00:00:00Z")
    rows = []
    for i in range(hours):
        rows.append({
            "locationKey": "in:28.614:77.209",
            "providerObservedAt": start + pd.Timedelta(hours=i),
            "aqiStandard": "INDIA_NAQI",
            "currentAqi": 100 + i % 24,
            "pm25": 45 + i % 10,
            "pm10": 90 + i % 12,
            "no2": 30,
            "so2": 10,
            "co": 0.8,
            "o3": 40,
            "latitude": 28.614,
            "longitude": 77.209,
        })
    return pd.DataFrame(rows)


def test_cpcb_backfill_normalizes_timezone_and_indian_naqi():
    doc = normalize_row({
        "timestamp": "01-01-2026 01:00:00",
        "station": "ITO",
        "city": "Delhi",
        "state": "Delhi",
        "latitude": "28.61",
        "longitude": "77.23",
        "PM2.5": "80",
        "PM10": "150",
        "NO2": "50",
        "SO2": "20",
        "CO": "1.2",
    })
    assert doc["aqiStandard"] == "INDIA_NAQI"
    assert doc["currentAqi"] is not None
    assert doc["providerObservedAt"].tzinfo is not None
    assert doc["coUnitHandling"].startswith("CO is stored")


def test_data_gov_pollutant_rows_coalesce_to_station_hour_snapshot():
    rows = [
        {
            "station": "Ganga Nagar, Meerut - UPPCB",
            "city": "Meerut",
            "state": "Uttar Pradesh",
            "last_update": "12-07-2026 22:00:00",
            "latitude": "28.993",
            "longitude": "77.708",
            "pollutant_id": pollutant,
            "avg_value": value,
        }
        for pollutant, value in [
            ("PM2.5", "72"),
            ("PM10", "145"),
            ("NO2", "28"),
            ("SO2", "11"),
            ("CO", "1.1"),
            ("Ozone", "38"),
        ]
    ]
    grouped = coalesce_pollutant_rows(rows)
    doc = normalize_row(grouped[0])
    assert len(grouped) == 1
    assert doc["stationName"] == "Ganga Nagar, Meerut - UPPCB"
    assert doc["city"] == "Meerut"
    assert doc["currentAqi"] is not None
    assert doc["aqiStandard"] == "INDIA_NAQI"


def test_dataset_creates_targets_without_future_features():
    bundle = build_hourly_dataset(sample_aqi(120), pd.DataFrame())
    row = bundle.frame.sort_values("timestamp").iloc[0]
    assert "target_aqi_24h" in bundle.target_columns.values()
    assert "target_aqi_72h" in bundle.target_columns.values()
    assert "target_aqi_24h" not in bundle.feature_columns
    assert pd.isna(row["currentAqi_lag_1h"])


def test_delta_targets_are_created():
    bundle = build_hourly_dataset(sample_aqi(120), pd.DataFrame())
    assert "delta_aqi_24h" in bundle.delta_target_columns.values()
    assert "delta_aqi_48h" in bundle.delta_target_columns.values()
    assert "delta_aqi_72h" in bundle.delta_target_columns.values()
    # Delta = target - current
    valid = bundle.frame.dropna(subset=["target_aqi_24h", "delta_aqi_24h"])
    if not valid.empty:
        diff = (valid["target_aqi_24h"] - valid["currentAqi"]) - valid["delta_aqi_24h"]
        assert diff.abs().max() < 1e-6


def test_cold_start_feature_schema_uses_no_history_lags():
    bundle = build_hourly_dataset(sample_aqi(220), pd.DataFrame())
    cold_features = feature_columns_for_tier(bundle.feature_columns, "GLOBAL_COLD_START")
    forbidden = ("_lag_", "_change_", "roll_", "missing_", "prev_day", "prev_week")
    assert "currentAqi" in cold_features
    assert not any(any(marker in feature for marker in forbidden) for feature in cold_features)


def test_history_tier_feature_schemas_are_separate():
    bundle = build_hourly_dataset(sample_aqi(220), pd.DataFrame())
    short_features = feature_columns_for_tier(bundle.feature_columns, "GLOBAL_SHORT_HISTORY")
    medium_features = feature_columns_for_tier(bundle.feature_columns, "GLOBAL_MEDIUM_HISTORY")
    full_features = feature_columns_for_tier(bundle.feature_columns, "GLOBAL_FULL_HISTORY")
    assert "currentAqi_lag_24h" in short_features
    assert "currentAqi_lag_72h" not in short_features
    assert "currentAqi_lag_72h" in medium_features
    assert "currentAqi_lag_168h" not in medium_features
    assert "currentAqi_lag_168h" in full_features


def test_per_station_walk_forward_splits():
    """Split must be per-station: each station's test data is strictly after its training data."""
    bundle = build_hourly_dataset(sample_aqi(200), pd.DataFrame())
    splits = per_station_walk_forward_splits(bundle.frame, n_splits=3, train_frac=0.6, val_frac=0.1, test_frac=0.1)
    assert len(splits) == 3
    
    for tr, va, te in splits:
        for loc_key in bundle.frame["locationKey"].unique():
            tr_s = tr[tr["locationKey"] == loc_key]
            va_s = va[va["locationKey"] == loc_key]
            te_s = te[te["locationKey"] == loc_key]
            if not tr_s.empty and not va_s.empty:
                assert tr_s["timestamp"].max() < va_s["timestamp"].min()
            if not va_s.empty and not te_s.empty:
                assert va_s["timestamp"].max() < te_s["timestamp"].min()


def test_metrics_and_promotion_compare_same_rows():
    actual = pd.Series([100, 120, 140])
    model = pd.Series([101, 119, 142])
    baseline = pd.Series([90, 90, 90])
    model_metrics = evaluate_predictions(actual, model)
    baseline_metrics = evaluate_predictions(actual, baseline)
    assert model_metrics.sample_count == baseline_metrics.sample_count == 3
    assert promotion_status(model_metrics, baseline_metrics, 3, 5, 20) == "PROMOTED"


def test_training_rejects_insufficient_data():
    """Test training with insufficient data rejects model without tmp_path."""
    work_dir = Path(os.path.dirname(__file__)) / "_test_output"
    work_dir.mkdir(parents=True, exist_ok=True)
    try:
        aqi_path = work_dir / "aqi.csv"
        config_path = work_dir / "config.json"
        sample_aqi(80).to_csv(aqi_path, index=False)
        config_path.write_text(json.dumps({
            "aqiCsv": str(aqi_path),
            "modelDir": str(work_dir),
            "minSamples": 200,
        }), encoding="utf-8")
        report = train(json.loads(config_path.read_text(encoding="utf-8")))
        assert report["scopes"]["GLOBAL_COLD_START"]["horizons"]["24"]["promotionStatus"] in ("INSUFFICIENT_DATA", "ALL_MODELS_FAILED")
    finally:
        # Cleanup
        for f in work_dir.glob("*"):
            try:
                f.unlink()
            except Exception:
                pass
        try:
            work_dir.rmdir()
        except Exception:
            pass


def test_heldout_failure_demotes_model():
    failures = heldout_failures({
        "ito_delhi_cpcb": {
            "city": "Delhi",
            "testRows": 2500,
            "metrics": {"mae": 70.0, "rmse": 100.0, "mean_bias": -40.0},
            "persistenceBaseline": {"mae": 45.0, "rmse": 65.0},
        }
    }, minimum_samples=200, max_bias=20)

    assert failures
    assert failures[0]["station"] == "ito_delhi_cpcb"
    assert "MAE_BASELINE_BETTER" in failures[0]["reasons"]
    assert "BIAS_TOO_HIGH" in failures[0]["reasons"]


def test_scheduled_retraining_includes_mature_contiguous_live_history():
    now = pd.Timestamp("2026-07-20T00:00:00Z").to_pydatetime()
    start = pd.Timestamp("2026-07-10T00:00:00Z")
    live_rows = []
    for i in range(80):
        live_rows.append({
            "locationKey": "in:26.846:80.937",
            "stationKey": "lalbagh_lucknow_cpcb",
            "providerObservedAt": start + pd.Timedelta(hours=i),
            "aqiStandard": "INDIA_NAQI",
            "dataOrigin": "LIVE_OPERATIONAL_HISTORY",
            "dataQualityStatus": "VALID",
            "currentAqi": 70 + i % 5,
            "latitude": 26.846,
            "longitude": 80.937,
        })

    training, _, report = verified_training_frames(
        pd.DataFrame(live_rows),
        pd.DataFrame(),
        "INDIA_NAQI",
        now,
        max_horizon_hours=72,
        max_live_gap_hours=1.5,
        min_live_contiguous_hours=73,
    )

    assert report["newlyCollectedRowsIncluded"] == 80
    assert set(training["dataOrigin"]) == {"LIVE_OPERATIONAL_HISTORY"}


def test_scheduled_retraining_excludes_immature_or_gappy_live_history():
    now = pd.Timestamp("2026-07-20T00:00:00Z").to_pydatetime()
    rows = [
        {
            "locationKey": "in:19.086:72.889",
            "stationKey": "kurla_mumbai_mpcb",
            "providerObservedAt": pd.Timestamp("2026-07-19T23:00:00Z"),
            "aqiStandard": "INDIA_NAQI",
            "dataOrigin": "LIVE_OPERATIONAL_HISTORY",
            "dataQualityStatus": "VALID",
            "currentAqi": 95,
        },
        {
            "locationKey": "in:19.086:72.889",
            "stationKey": "kurla_mumbai_mpcb",
            "providerObservedAt": pd.Timestamp("2026-07-10T00:00:00Z"),
            "aqiStandard": "INDIA_NAQI",
            "dataOrigin": "LIVE_OPERATIONAL_HISTORY",
            "dataQualityStatus": "VALID",
            "currentAqi": 94,
        },
        {
            "locationKey": "in:19.086:72.889",
            "stationKey": "kurla_mumbai_mpcb",
            "providerObservedAt": pd.Timestamp("2026-07-10T04:00:00Z"),
            "aqiStandard": "INDIA_NAQI",
            "dataOrigin": "LIVE_OPERATIONAL_HISTORY",
            "dataQualityStatus": "VALID",
            "currentAqi": 96,
        },
    ]

    training, _, report = verified_training_frames(
        pd.DataFrame(rows),
        pd.DataFrame(),
        "INDIA_NAQI",
        now,
        max_horizon_hours=72,
        max_live_gap_hours=1.5,
        min_live_contiguous_hours=73,
    )

    assert training.empty
    assert report["liveRowsRejectedImmature"] == 1
    assert report["newlyCollectedRowsIncluded"] == 0
    assert report["liveRowsRejectedQuality"] == 2
