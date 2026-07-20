import xgboost as xgb
from sklearn.multioutput import MultiOutputRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error
import joblib
import os
import pandas as pd
import numpy as np

MODEL_PATH_AQI = os.getenv('MODEL_PATH_AQI', 'xgboost_aqi_model.joblib')
MODEL_PATH_PM25 = os.getenv('MODEL_PATH_PM25', 'xgboost_pm25_model.joblib')

# Keep models in memory
model_aqi = None
model_pm25 = None
feature_names = []

# Human-readable labels for source attribution
FEATURE_LABELS = {
    'hour_sin': 'Time of Day',
    'hour_cos': 'Time of Day',
    'month_sin': 'Season',
    'month_cos': 'Season',
    'day_sin': 'Day of Week',
    'day_cos': 'Day of Week',
    'temperature': 'Temperature',
    'humidity': 'Humidity',
    'pressure': 'Pressure',
    'windSpeed': 'Wind Speed',
    'windDirection': 'Wind Direction',
    'rainfall': 'Rainfall',
    'aqi_t-1': 'Recent AQI Trend (1h)',
    'aqi_t-24': 'Daily AQI Pattern (24h)',
    'aqi_t-48': 'Multi-day AQI Trend (48h)',
    'pm25_t-1': 'Recent PM25 (1h)',
    'pm25_t-24': 'Daily PM25 Pattern (24h)',
    'pm25_t-48': 'Multi-day PM25 Trend (48h)',
    'upwind_pm25': 'Upwind PM25 (Dispersion)',
    'upwind_aqi': 'Upwind AQI (Dispersion)',
    'aqi': 'Current AQI',
    'pm25': 'Current PM25',
}


def train_models(X: pd.DataFrame, y_aqi: pd.DataFrame, y_pm25: pd.DataFrame) -> dict:
    """
    Train XGBoost models for AQI and PM2.5 multi-step forecasting (72 horizons).
    Evaluates on a chronological 80/20 holdout split.
    """
    global model_aqi, model_pm25, feature_names
    feature_names = list(X.columns)

    # Chronological split — 80% train, 20% test
    split_idx = int(len(X) * 0.8)
    X_train, X_test = X.iloc[:split_idx], X.iloc[split_idx:]
    y_aqi_train, y_aqi_test = y_aqi.iloc[:split_idx], y_aqi.iloc[split_idx:]
    y_pm25_train, y_pm25_test = y_pm25.iloc[:split_idx], y_pm25.iloc[split_idx:]

    # Train AQI Model
    base_xgb_aqi = xgb.XGBRegressor(
        n_estimators=100, max_depth=5, learning_rate=0.1, random_state=42
    )
    model_aqi = MultiOutputRegressor(base_xgb_aqi)
    model_aqi.fit(X_train, y_aqi_train)

    # Train PM2.5 Model
    base_xgb_pm25 = xgb.XGBRegressor(
        n_estimators=100, max_depth=5, learning_rate=0.1, random_state=42
    )
    model_pm25 = MultiOutputRegressor(base_xgb_pm25)
    model_pm25.fit(X_train, y_pm25_train)

    # Evaluate — overall RMSE
    preds_aqi = model_aqi.predict(X_test)
    preds_pm25 = model_pm25.predict(X_test)

    rmse_aqi = float(np.sqrt(mean_squared_error(y_aqi_test, preds_aqi)))
    rmse_pm25 = float(np.sqrt(mean_squared_error(y_pm25_test, preds_pm25)))
    mae_aqi = float(mean_absolute_error(y_aqi_test, preds_aqi))
    mae_pm25 = float(mean_absolute_error(y_pm25_test, preds_pm25))

    # Persistence Baseline (y_t+h = y_t)
    baseline_aqi_preds = np.tile(X_test['aqi'].values.reshape(-1, 1), (1, y_aqi_test.shape[1]))
    baseline_pm25_preds = np.tile(X_test['pm25'].values.reshape(-1, 1), (1, y_pm25_test.shape[1]))

    baseline_rmse_aqi = float(np.sqrt(mean_squared_error(y_aqi_test, baseline_aqi_preds)))
    baseline_rmse_pm25 = float(np.sqrt(mean_squared_error(y_pm25_test, baseline_pm25_preds)))
    baseline_mae_aqi = float(mean_absolute_error(y_aqi_test, baseline_aqi_preds))
    baseline_mae_pm25 = float(mean_absolute_error(y_pm25_test, baseline_pm25_preds))

    # Improvement Percentage
    imp_aqi = ((baseline_rmse_aqi - rmse_aqi) / baseline_rmse_aqi) * 100 if baseline_rmse_aqi > 0 else 0
    imp_pm25 = ((baseline_rmse_pm25 - rmse_pm25) / baseline_rmse_pm25) * 100 if baseline_rmse_pm25 > 0 else 0

    # Per-horizon RMSE at key checkpoints (t+1, t+24, t+48, t+72)
    horizon_rmse = {}
    for h in [1, 24, 48, 72]:
        col_idx = h - 1  # 0-indexed
        if col_idx < preds_aqi.shape[1]:
            horizon_rmse[f"rmse_aqi_t+{h}"] = float(
                np.sqrt(mean_squared_error(y_aqi_test.iloc[:, col_idx], preds_aqi[:, col_idx]))
            )
            horizon_rmse[f"rmse_pm25_t+{h}"] = float(
                np.sqrt(mean_squared_error(y_pm25_test.iloc[:, col_idx], preds_pm25[:, col_idx]))
            )

    # Save models and feature names
    dir_name = os.path.dirname(MODEL_PATH_AQI)
    if dir_name:
        os.makedirs(dir_name, exist_ok=True)
    joblib.dump(model_aqi, MODEL_PATH_AQI)
    joblib.dump(model_pm25, MODEL_PATH_PM25)
    joblib.dump(feature_names, 'feature_names.joblib')

    return {
        "rmse_aqi": rmse_aqi,
        "rmse_pm25": rmse_pm25,
        "mae_aqi": mae_aqi,
        "mae_pm25": mae_pm25,
        "baseline_rmse_aqi": baseline_rmse_aqi,
        "baseline_rmse_pm25": baseline_rmse_pm25,
        "baseline_mae_aqi": baseline_mae_aqi,
        "baseline_mae_pm25": baseline_mae_pm25,
        "improvement_pct_aqi": imp_aqi,
        "improvement_pct_pm25": imp_pm25,
        "rows_used": len(X),
        "train_size": len(X_train),
        "test_size": len(X_test),
        "horizon_rmse": horizon_rmse,
    }


def load_models() -> bool:
    """Load models from disk if they exist."""
    global model_aqi, model_pm25, feature_names
    if os.path.exists(MODEL_PATH_AQI) and os.path.exists(MODEL_PATH_PM25):
        model_aqi = joblib.load(MODEL_PATH_AQI)
        model_pm25 = joblib.load(MODEL_PATH_PM25)
        if os.path.exists('feature_names.joblib'):
            feature_names = joblib.load('feature_names.joblib')
        return True
    return False


def predict_forecast(X_latest: pd.DataFrame) -> tuple:
    """
    Predict next 72 steps for a single instance.
    Returns (preds_aqi_list, preds_pm25_list)
    """
    global model_aqi, model_pm25
    if model_aqi is None or model_pm25 is None:
        if not load_models():
            raise Exception("Models are not loaded or trained yet.")

    # Ensure correct feature order
    if feature_names:
        X_latest = X_latest[feature_names]

    preds_aqi = model_aqi.predict(X_latest)[0]
    preds_pm25 = model_pm25.predict(X_latest)[0]

    return preds_aqi.tolist(), preds_pm25.tolist()


def get_feature_importance() -> dict:
    """
    Return averaged feature importance across ALL 72 estimators.
    
    Each estimator in the MultiOutputRegressor predicts one future hour.
    Averaging gives a representative importance picture rather than
    relying on just the first estimator (t+1).
    
    Returns dict mapping feature names to normalised importance (0-1).
    """
    global model_aqi, feature_names
    if model_aqi is None:
        if not load_models():
            return {}

    # Collect importances from every estimator and average
    all_importances = np.array([
        est.feature_importances_ for est in model_aqi.estimators_
    ])
    avg_importances = all_importances.mean(axis=0)

    # Normalise to sum to 1.0
    total = avg_importances.sum()
    if total > 0:
        avg_importances = avg_importances / total

    return dict(zip(feature_names, [round(float(v), 4) for v in avg_importances]))


def get_feature_importance_labeled() -> dict:
    """
    Return feature importance with human-readable labels for source attribution.
    Groups related features (e.g. hour_sin + hour_cos → 'Time of Day').
    """
    raw = get_feature_importance()
    if not raw:
        return {}

    # Group features with shared labels
    grouped = {}
    for feat_name, importance in raw.items():
        label = FEATURE_LABELS.get(feat_name, feat_name)
        grouped[label] = grouped.get(label, 0.0) + importance

    # Sort descending by importance
    return dict(sorted(grouped.items(), key=lambda x: x[1], reverse=True))
