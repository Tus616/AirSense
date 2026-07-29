
from fastapi.testclient import TestClient
from main import app, _eligible_candidate_scopes, InternalForecastPredictionRequest, chronos_service
import pandas as pd

client = TestClient(app)


def fresh_history(location_key="in:26.863:80.999", standard="INDIA_NAQI", hours=169):
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

def test_lucknow_chronos_zero_shot_selection_and_inference(monkeypatch):
    class FakeChronos:
        predicted_aqi = 161.0
        lower_bound = 140.0
        upper_bound = 184.0

    monkeypatch.setattr(chronos_service, "predict", lambda values, horizons: ({h: FakeChronos() for h in horizons}, None))
    issue_time, history = fresh_history()
    response = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-123",
        "locationKey": "in:26.853:80.999", # searched
        "stationLocationKey": "in:26.863:80.999", # station actual
        "forecastIssueTime": issue_time,
        "latitude": 26.863,
        "longitude": 80.999,
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
        assert p["status"] == "FORECAST"
        assert p["engine"] == "CHRONOS_BOLT_ZERO_SHOT"
        assert p["modelFamily"] == "CHRONOS_BOLT"
        assert p["predictedAqi"] >= 0


def test_readiness_and_forecast_status_endpoints():
    ready = client.get("/ready")
    assert ready.status_code == 200
    assert ready.json()["providerClientConfigured"] is True

    status = client.get("/internal/forecast/status")
    assert status.status_code == 200
    data = status.json()
    assert set([24, 48, 72]).issubset(set(data["supportedHorizons"]))
    assert data["providerFallbackAvailable"] is True
    assert "CHRONOS_BOLT_ZERO_SHOT" in data["loadedEngineNames"]

def test_readiness_does_not_load_chronos_when_disabled(monkeypatch):
    monkeypatch.setattr(chronos_service, "enabled", False)
    monkeypatch.setattr(chronos_service, "load", lambda: (_ for _ in ()).throw(AssertionError("Chronos load should not run")))

    ready = client.get("/ready")
    status = client.get("/internal/forecast/status")

    assert ready.status_code == 200
    assert ready.json()["chronosModelLoaded"] is False
    assert status.status_code == 200
    assert status.json()["modelLoaded"] is False

def test_promoted_artifact_absence_uses_persistence_for_delhi_mumbai(monkeypatch):
    monkeypatch.setattr(chronos_service, "predict", lambda values, horizons: ({}, "INSUFFICIENT_HISTORY_FOR_CHRONOS"))
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
        assert data["predictions"][0]["status"] == "FORECAST"
        assert data["predictions"][0]["engine"] == "PERSISTENCE_FALLBACK"
        assert data["predictions"][0]["predictedAqi"] == 150

def test_provider_forecast_fallback(monkeypatch):
    monkeypatch.setattr(chronos_service, "predict", lambda values, horizons: ({}, "CHRONOS_LOAD_FAILED"))

    class FakeProvider:
        enabled = True

        def fetch(self, *args, **kwargs):
            from forecasting.inference.open_meteo_client import ProviderSeries
            now = pd.Timestamp.now(tz="UTC").floor("h")
            return ProviderSeries(
                observations=[
                    {"timestamp": (now - pd.Timedelta(hours=i)).isoformat(), "currentAqi": 80 + i % 4, "aqiStandard": "US_AQI"}
                    for i in range(60, 0, -1)
                ],
                hourly_forecast=[
                    {"timestamp": (now + pd.Timedelta(hours=h)).isoformat(), "currentAqi": 90 + h, "aqiStandard": "US_AQI"}
                    for h in [24, 48, 72]
                ],
                provider="OPEN_METEO",
                aqi_standard="US_AQI",
                selected_aqi_field="us_aqi",
                metadata={},
            )

    import main
    monkeypatch.setattr(main, "open_meteo_client", FakeProvider())
    issue_time, history = fresh_history()
    response = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-123",
        "locationKey": "in:26.863:80.999",
        "latitude": 26.863,
        "longitude": 80.999,
        "forecastIssueTime": issue_time,
        "forecastStandard": "US_AQI",
        "currentAqi": 150,
        "horizons": [24, 48, 72],
        "history": [],
    })
    data = response.json()
    assert [p["engine"] for p in data["predictions"]] == ["OPEN_METEO_PROVIDER_FORECAST"] * 3
    assert data["predictions"][0]["predictedAqi"] is not None


def test_provider_forecast_accepts_provider_only_request_without_current_aqi(monkeypatch):
    monkeypatch.setattr(chronos_service, "predict", lambda values, horizons: ({}, "CHRONOS_LOAD_FAILED"))

    class FakeProvider:
        enabled = True

        def fetch(self, *args, **kwargs):
            from forecasting.inference.open_meteo_client import ProviderSeries
            now = pd.Timestamp.now(tz="UTC").floor("h")
            return ProviderSeries(
                observations=[],
                hourly_forecast=[
                    {"timestamp": (now + pd.Timedelta(hours=h)).isoformat(), "currentAqi": value, "aqiStandard": "US_AQI"}
                    for h, value in [(24, 156), (48, 118), (72, 85)]
                ],
                provider="OPEN_METEO",
                aqi_standard="US_AQI",
                selected_aqi_field="us_aqi",
                metadata={},
            )

    import main
    monkeypatch.setattr(main, "open_meteo_client", FakeProvider())
    response = client.post("/internal/forecast/predict", json={
        "snapshotId": "snap-provider-only",
        "locationKey": "in:28.614:77.209",
        "searchedLocationKey": "in:28.614:77.209",
        "latitude": 28.6139,
        "longitude": 77.2090,
        "forecastStandard": "US_AQI",
        "aqiStandard": "US_AQI",
        "provider": "OPEN_METEO",
        "currentAqi": None,
        "horizons": [24, 48, 72],
        "history": [],
    })

    assert response.status_code == 200
    data = response.json()
    assert data["forecastStandard"] == "US_AQI"
    assert [p["engine"] for p in data["predictions"]] == ["OPEN_METEO_PROVIDER_FORECAST"] * 3
    assert [p["predictedAqi"] for p in data["predictions"]] == [156, 118, 85]


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


def test_rejected_72h_global_cold_start_uses_persistence(monkeypatch):
    monkeypatch.setattr(chronos_service, "predict", lambda values, horizons: ({}, "INSUFFICIENT_HISTORY_FOR_CHRONOS"))
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

    assert result["predictions"][0]["status"] == "FORECAST"
    assert result["predictions"][0]["engine"] == "PERSISTENCE_FALLBACK"
    assert result["predictions"][0]["predictedAqi"] == 176


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
    del history[-48:-40]
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
