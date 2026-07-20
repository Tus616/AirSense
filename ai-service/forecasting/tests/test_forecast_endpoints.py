
from fastapi.testclient import TestClient
from main import app, _eligible_candidate_scopes, InternalForecastPredictionRequest
import pandas as pd

client = TestClient(app)


def fresh_history(location_key="in:26.863:80.999", standard="INDIA_NAQI", hours=73):
    issue_time = pd.Timestamp.now(tz="UTC").floor("h")
    start = issue_time - pd.Timedelta(hours=hours - 1)
    return issue_time.isoformat(), [
        {
            "timestamp": (start + pd.Timedelta(hours=i)).isoformat(),
            "locationKey": location_key,
            "aqiStandard": standard,
            "currentAqi": 120 + (i % 12),
        }
        for i in range(hours)
    ]

def test_lucknow_promoted_model_selection_and_inference():
    issue_time, history = fresh_history()
    response = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-123",
        "locationKey": "in:26.853:80.999", # searched
        "stationLocationKey": "in:26.863:80.999", # station actual
        "forecastIssueTime": issue_time,
        "forecastStandard": "INDIA_NAQI",
        "currentAqi": 150,
        "horizons": [24, 48, 72],
        "features": {"currentAqi": 150, "featureSchemaVersion": "forecasting-feature-schema-v2"},
        "history": history,
    })
    assert response.status_code == 200
    data = response.json()
    assert data["snapshotId"] == "snap-123"
    assert data["locationKey"] == "in:26.853:80.999"
    for p in data["predictions"]:
        assert p["status"] == "PROMOTED"
        assert p["engine"] == "ML_STATION_XGBOOST"
        assert p["forecastScope"] == "STATION_SPECIFIC_ML"
        assert p["predictedAqi"] >= 0

def test_rejected_global_cold_start_cannot_run_for_delhi_mumbai():
    for loc, latitude, longitude in [
        ("in:28.629:77.241", 28.629, 77.241),
        ("in:19.057:72.859", 19.057, 72.859),
    ]:
        issue_time, history = fresh_history(loc)
        response = client.post("/internal/forecast/predict", json={
            "snapshotId": "snap-123",
            "locationKey": loc,
            "forecastIssueTime": issue_time,
            "forecastStandard": "INDIA_NAQI",
            "currentAqi": 150,
            "horizons": [24],
            "features": {
                "currentAqi": 150,
                "latitude": latitude,
                "longitude": longitude,
                "featureSchemaVersion": "forecasting-feature-schema-v2",
            },
            "history": history,
        })
        data = response.json()
        assert data["predictions"][0]["status"] == "ARTIFACT_UNAVAILABLE"
        assert data["predictions"][0]["fallbackReason"] == "MODEL_NOT_PROMOTED"

def test_schema_mismatch_fallback():
    issue_time, history = fresh_history()
    response = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-123",
        "locationKey": "in:26.863:80.999",
        "forecastIssueTime": issue_time,
        "forecastStandard": "INDIA_NAQI",
        "currentAqi": 150,
        "horizons": [24],
        "features": {"featureSchemaVersion": "invalid-schema-v999", "currentAqi": 150},
        "history": history,
    })
    data = response.json()
    assert data["predictions"][0]["status"] == "SCHEMA_MISMATCH"
    assert data["predictions"][0]["fallbackReason"] == "FEATURE_SCHEMA_MISMATCH"


def test_delta_target_converts_to_absolute_once(monkeypatch):
    class DeltaModel:
        def predict(self, frame):
            assert frame.iloc[0]["currentAqi"] == 120
            return [35.0]

    metadata = {
        "featureSchemaVersion": "forecasting-feature-schema-v2",
        "features": ["currentAqi", "latitude", "longitude"],
        "requiredFeatures": ["currentAqi", "latitude", "longitude"],
        "useDeltaTarget": True,
        "modelScope": "GLOBAL_COLD_START",
        "modelFamily": "SKLEARN_HIST_GRADIENT_BOOSTING",
        "version": "unit-delta-model",
        "metrics": {"rmse": 10.0},
        "baselineMetrics": {"rmse": 20.0},
        "trainingDeltaPercentiles": {"p01": -50.0, "p99": 80.0},
        "featureStats": {},
    }

    import forecasting.inference.predict as predict_module

    monkeypatch.setattr(predict_module, "load_promoted", lambda *args, **kwargs: (DeltaModel(), metadata))
    result = predict_module.predict(
        "unused",
        "INDIA_NAQI",
        48,
        {
            "featureSchemaVersion": "forecasting-feature-schema-v2",
            "currentAqi": 120,
            "latitude": 26.8,
            "longitude": 80.9,
        },
        "station",
        ["GLOBAL_COLD_START"],
    )

    assert result["predictedDelta"] == 35.0
    assert result["predictedAqi"] == 155


def test_high_ood_rejects_ml(monkeypatch):
    class DeltaModel:
        def predict(self, frame):
            return [20.0]

    metadata = {
        "featureSchemaVersion": "forecasting-feature-schema-v2",
        "features": ["currentAqi", "latitude", "longitude"],
        "requiredFeatures": ["currentAqi", "latitude", "longitude"],
        "useDeltaTarget": True,
        "modelScope": "GLOBAL_COLD_START",
        "modelFamily": "SKLEARN_HIST_GRADIENT_BOOSTING",
        "version": "unit-ood-model",
        "metrics": {"rmse": 10.0},
        "baselineMetrics": {"rmse": 20.0},
        "trainingDeltaPercentiles": {"p01": -50.0, "p99": 80.0},
        "featureStats": {
            "currentAqi": {"p01": 0.0, "p99": 100.0},
            "latitude": {"p01": 20.0, "p99": 30.0},
            "longitude": {"p01": 70.0, "p99": 90.0},
        },
        "heldOutValidationStatus": "ROBUST",
        "generalizationStatus": "MULTI_STATION_COVERAGE",
        "oodPolicy": {"lowMaxScore": 0.05, "mediumMaxScore": 0.15},
    }

    import forecasting.inference.predict as predict_module

    monkeypatch.setattr(predict_module, "load_promoted", lambda *args, **kwargs: (DeltaModel(), metadata))
    result = predict_module.predict(
        "unused",
        "INDIA_NAQI",
        72,
        {
            "featureSchemaVersion": "forecasting-feature-schema-v2",
            "currentAqi": 250,
            "latitude": 26.8,
            "longitude": 80.9,
        },
        "station",
        ["GLOBAL_COLD_START"],
    )

    assert result["status"] == "ARTIFACT_UNAVAILABLE"
    assert result["fallbackReason"] == "OUT_OF_DISTRIBUTION_FEATURES"
    assert result["oodLevel"] == "OOD_HIGH"
    assert result["oodFeatures"] == ["currentAqi"]


def test_low_ood_reduces_confidence_and_optional_missing_is_imputed(monkeypatch):
    class DeltaModel:
        def predict(self, frame):
            return [5.0]

    metadata = {
        "featureSchemaVersion": "forecasting-feature-schema-v2",
        "features": ["currentAqi", "latitude", "longitude", "pm25"],
        "requiredFeatures": ["currentAqi", "latitude", "longitude"],
        "useDeltaTarget": True,
        "modelScope": "GLOBAL_COLD_START",
        "modelFamily": "SKLEARN_HIST_GRADIENT_BOOSTING",
        "version": "unit-low-ood-model",
        "metrics": {"rmse": 10.0},
        "baselineMetrics": {"rmse": 20.0},
        "trainingDeltaPercentiles": {"p01": -50.0, "p99": 80.0},
        "featureStats": {
            "currentAqi": {"p01": 0.0, "p99": 300.0},
            "latitude": {"p01": 20.0, "p99": 30.0},
            "longitude": {"p01": 70.0, "p99": 90.0},
        },
        "imputerDefaults": {"pm25": 63.0},
        "heldOutValidationStatus": "ROBUST",
        "generalizationStatus": "MULTI_STATION_COVERAGE",
    }

    import forecasting.inference.predict as predict_module

    monkeypatch.setattr(predict_module, "load_promoted", lambda *args, **kwargs: (DeltaModel(), metadata))
    result = predict_module.predict(
        "unused",
        "INDIA_NAQI",
        24,
        {
            "featureSchemaVersion": "forecasting-feature-schema-v2",
            "currentAqi": 120,
            "latitude": 26.8,
            "longitude": 80.9,
        },
        "station",
        ["GLOBAL_COLD_START"],
    )

    assert result["status"] == "PROMOTED"
    assert result["oodLevel"] == "OOD_LOW"
    assert result["confidence"] < 0.9
    assert "pm25" in result["featureDiagnostics"]["imputedFeatureNames"]
    assert "pm25" not in result["featureDiagnostics"]["measuredFeatureNames"]


def test_rejected_72h_global_cold_start_cannot_run():
    result = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-72-rejected",
        "locationKey": "in:28.629:77.241",
        "forecastStandard": "INDIA_NAQI",
        "currentAqi": 176,
        "horizons": [72],
        "features": {
            "currentAqi": 176,
            "latitude": 28.629,
            "longitude": 77.241,
            "featureSchemaVersion": "forecasting-feature-schema-v2",
        },
        "history": [],
    }).json()

    assert result["predictions"][0]["status"] == "ARTIFACT_UNAVAILABLE"
    assert result["predictions"][0]["fallbackReason"] == "MODEL_NOT_PROMOTED"


def test_missing_feature_schema_is_rejected(monkeypatch):
    import forecasting.inference.predict as predict_module

    monkeypatch.setattr(
        predict_module,
        "load_promoted",
        lambda *args, **kwargs: (object(), {
            "featureSchemaVersion": "forecasting-feature-schema-v2",
            "features": ["currentAqi"],
            "requiredFeatures": ["currentAqi"],
        }),
    )
    result = predict_module.predict("unused", "INDIA_NAQI", 48, {"currentAqi": 100})
    assert result["status"] == "SCHEMA_MISMATCH"
    assert result["fallbackReason"] == "FEATURE_SCHEMA_MISMATCH"


def test_old_archive_history_is_rejected_for_live_forecast():
    history = [
        {
            "timestamp": (pd.Timestamp("2025-12-01T00:00:00Z") + pd.Timedelta(hours=i)).isoformat(),
            "locationKey": "in:26.863:80.999",
            "aqiStandard": "INDIA_NAQI",
            "currentAqi": 130,
        }
        for i in range(73)
    ]
    request = InternalForecastPredictionRequest(
        snapshotId="snap-archive",
        locationKey="in:26.863:80.999",
        stationLocationKey="in:26.863:80.999",
        forecastIssueTime="2026-07-17T12:00:00Z",
        forecastStandard="INDIA_NAQI",
        currentAqi=150,
        horizons=[24],
        candidateModelScopes=["GLOBAL_FULL_HISTORY", "GLOBAL_MEDIUM_HISTORY", "GLOBAL_COLD_START"],
        features={"featureSchemaVersion": "forecasting-feature-schema-v2", "currentAqi": 150},
        history=history,
    )
    scopes = _eligible_candidate_scopes(request, pd.DataFrame(history), pd.Timestamp("2026-07-17T12:00:00Z"))
    assert "GLOBAL_FULL_HISTORY" not in scopes
    assert "GLOBAL_MEDIUM_HISTORY" not in scopes
    assert "GLOBAL_COLD_START" in scopes


def test_large_gap_history_is_rejected():
    issue_time, history = fresh_history()
    del history[20:28]
    request = InternalForecastPredictionRequest(
        snapshotId="snap-gap",
        locationKey="in:26.863:80.999",
        stationLocationKey="in:26.863:80.999",
        forecastIssueTime=issue_time,
        forecastStandard="INDIA_NAQI",
        currentAqi=150,
        horizons=[24],
        candidateModelScopes=["GLOBAL_MEDIUM_HISTORY", "GLOBAL_SHORT_HISTORY", "GLOBAL_COLD_START"],
        features={"featureSchemaVersion": "forecasting-feature-schema-v2", "currentAqi": 150},
        history=history,
    )
    scopes = _eligible_candidate_scopes(request, pd.DataFrame(history), pd.Timestamp(issue_time))
    assert "GLOBAL_MEDIUM_HISTORY" not in scopes
    assert "GLOBAL_COLD_START" in scopes
