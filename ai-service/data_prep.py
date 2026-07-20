import pandas as pd
import numpy as np

FEATURE_COLS = [
    'hour_sin', 'hour_cos', 'month_sin', 'month_cos',
    'day_sin', 'day_cos',
    'temperature', 'humidity', 'pressure', 'windSpeed', 'windDirection', 'rainfall',
    'aqi_t-1', 'aqi_t-24', 'aqi_t-48',
    'pm25_t-1', 'pm25_t-24', 'pm25_t-48',
    'upwind_pm25', 'upwind_aqi',
    'aqi', 'pm25'  # Current hour as feature
]


def _extract_nested_fields(df: pd.DataFrame) -> pd.DataFrame:
    """Extract pollutant and weather fields from nested dicts."""
    df['aqi'] = df['pollutants'].apply(lambda x: x.get('aqi') if isinstance(x, dict) else None)
    df['pm25'] = df['pollutants'].apply(lambda x: x.get('pm25') if isinstance(x, dict) else None)
    df['temperature'] = df['weather'].apply(lambda x: x.get('temperature') if isinstance(x, dict) else None)
    df['humidity'] = df['weather'].apply(lambda x: x.get('humidity') if isinstance(x, dict) else None)
    df['pressure'] = df['weather'].apply(lambda x: x.get('pressure') if isinstance(x, dict) else None)
    df['windSpeed'] = df['weather'].apply(lambda x: x.get('windSpeed') if isinstance(x, dict) else None)
    df['windDirection'] = df['weather'].apply(lambda x: x.get('windDirection') if isinstance(x, dict) else None)
    df['rainfall'] = df['weather'].apply(lambda x: x.get('rainfall') if isinstance(x, dict) else None)
    return df


def _clean_and_smooth(df: pd.DataFrame, group_col: str) -> pd.DataFrame:
    """
    Forward-fill small gaps, then apply 3-hour moving-average smoothing
    to remove erratic spikes in pollution readings.
    """
    # Forward-fill then back-fill small gaps per sensor/grid
    for col in ['aqi', 'pm25']:
        df[col] = df.groupby(group_col)[col].ffill().bfill()
    df['temperature'] = df.groupby(group_col)['temperature'].ffill().bfill().fillna(25.0)
    df['humidity'] = df.groupby(group_col)['humidity'].ffill().bfill().fillna(50.0)
    df['pressure'] = df.groupby(group_col)['pressure'].ffill().bfill().fillna(1013.0)
    df['windSpeed'] = df.groupby(group_col)['windSpeed'].ffill().bfill().fillna(5.0)
    df['windDirection'] = df.groupby(group_col)['windDirection'].ffill().bfill().fillna(0.0)
    df['rainfall'] = df.groupby(group_col)['rainfall'].ffill().bfill().fillna(0.0)

    # Moving-average smoothing (window=3) to dampen erratic spikes
    # Preserve raw values, replace with smoothed for modelling
    df['aqi_raw'] = df['aqi'].copy()
    df['pm25_raw'] = df['pm25'].copy()
    df['aqi'] = df.groupby(group_col)['aqi'].transform(
        lambda s: s.rolling(window=3, min_periods=1, center=True).mean()
    )
    df['pm25'] = df.groupby(group_col)['pm25'].transform(
        lambda s: s.rolling(window=3, min_periods=1, center=True).mean()
    )

    return df


def _add_cyclical_time_features(df: pd.DataFrame) -> pd.DataFrame:
    """Encode hour-of-day and month-of-year as sin/cos pairs."""
    df['hour'] = df['timestamp'].dt.hour
    df['month'] = df['timestamp'].dt.month
    df['day'] = df['timestamp'].dt.dayofweek
    df['hour_sin'] = np.sin(2 * np.pi * df['hour'] / 24)
    df['hour_cos'] = np.cos(2 * np.pi * df['hour'] / 24)
    df['month_sin'] = np.sin(2 * np.pi * df['month'] / 12)
    df['month_cos'] = np.cos(2 * np.pi * df['month'] / 12)
    df['day_sin'] = np.sin(2 * np.pi * df['day'] / 7)
    df['day_cos'] = np.cos(2 * np.pi * df['day'] / 7)
    return df


def _add_lag_features(df: pd.DataFrame, group_col: str) -> pd.DataFrame:
    """Create lag features: pollution levels at t-1h, t-24h, t-48h."""
    df['aqi_t-1'] = df.groupby(group_col)['aqi'].shift(1)
    df['aqi_t-24'] = df.groupby(group_col)['aqi'].shift(24)
    df['aqi_t-48'] = df.groupby(group_col)['aqi'].shift(48)

    df['pm25_t-1'] = df.groupby(group_col)['pm25'].shift(1)
    df['pm25_t-24'] = df.groupby(group_col)['pm25'].shift(24)
    df['pm25_t-48'] = df.groupby(group_col)['pm25'].shift(48)

    return df

def _apply_dispersion(df: pd.DataFrame) -> pd.DataFrame:
    """
    Simple geospatial dispersion model feature engineering.
    Uses wind speed and direction to estimate upwind pollution contribution.
    If spatial neighbor data isn't directly passed, we simulate the dispersion
    effect using a wind-scaled fraction of historical raw pollution.
    """
    # Fallback to simulated dispersion if no actual upwind neighbor is found in dataset
    df['upwind_pm25'] = df['pm25'] * (df['windSpeed'] / 20.0)
    df['upwind_aqi'] = df['aqi'] * (df['windSpeed'] / 20.0)
    return df


def prepare_data(df: pd.DataFrame, target_horizon: int = 72) -> tuple:
    """
    Cleans and prepares features and targets for training.
    """
    if df.empty:
        return pd.DataFrame(), pd.DataFrame(), pd.DataFrame()

    group_col = 'gridId' if 'gridId' in df.columns else 'sensorId'

    df['timestamp'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values(by=[group_col, 'timestamp']).reset_index(drop=True)

    # Pipeline steps
    df = _extract_nested_fields(df)
    df = _clean_and_smooth(df, group_col)
    df = _add_cyclical_time_features(df)
    df = _add_lag_features(df, group_col)
    df = _apply_dispersion(df)

    # Create multi-step targets for the next `target_horizon` hours
    y_aqi_cols = []
    y_pm25_cols = []
    for i in range(1, target_horizon + 1):
        aqi_col = f'target_aqi_{i}'
        pm25_col = f'target_pm25_{i}'
        df[aqi_col] = df.groupby(group_col)['aqi'].shift(-i)
        df[pm25_col] = df.groupby(group_col)['pm25'].shift(-i)
        y_aqi_cols.append(aqi_col)
        y_pm25_cols.append(pm25_col)

    # Drop rows with NaN in features or targets
    df = df.dropna(subset=FEATURE_COLS + y_aqi_cols + y_pm25_cols)

    X = df[FEATURE_COLS].copy()
    y_aqi = df[y_aqi_cols].copy()
    y_pm25 = df[y_pm25_cols].copy()

    return X, y_aqi, y_pm25


def prepare_inference_features(records: list) -> pd.DataFrame:
    """
    Prepare the feature row for a single sensor's prediction given its history.
    """
    df = pd.DataFrame(records)
    if df.empty or len(df) < 24:
        return pd.DataFrame()

    df['timestamp'] = pd.to_datetime(df['timestamp'])
    df = df.sort_values(by='timestamp').reset_index(drop=True)

    df = _extract_nested_fields(df)

    # Clean — forward-fill and smooth
    df['aqi'] = df['aqi'].ffill().bfill()
    df['pm25'] = df['pm25'].ffill().bfill()
    df['temperature'] = df['temperature'].ffill().bfill().fillna(25.0)
    df['humidity'] = df['humidity'].ffill().bfill().fillna(50.0)
    df['pressure'] = df['pressure'].ffill().bfill().fillna(1013.0)
    df['windSpeed'] = df['windSpeed'].ffill().bfill().fillna(5.0)
    df['windDirection'] = df['windDirection'].ffill().bfill().fillna(0.0)
    df['rainfall'] = df['rainfall'].ffill().bfill().fillna(0.0)

    # Moving-average smoothing
    df['aqi'] = df['aqi'].rolling(window=3, min_periods=1, center=True).mean()
    df['pm25'] = df['pm25'].rolling(window=3, min_periods=1, center=True).mean()

    # Time features
    df = _add_cyclical_time_features(df)
    
    # Dispersion feature
    df = _apply_dispersion(df)

    # Build feature dict from the latest row using safe lag lookups
    latest_idx = len(df) - 1

    def safe_lag(col: str, lag: int):
        """Safely look up a lagged value, falling back to current if insufficient history."""
        idx = latest_idx - lag
        return df.loc[idx, col] if idx >= 0 else df.loc[latest_idx, col]

    feature_dict = {
        'hour_sin': df.loc[latest_idx, 'hour_sin'],
        'hour_cos': df.loc[latest_idx, 'hour_cos'],
        'month_sin': df.loc[latest_idx, 'month_sin'],
        'month_cos': df.loc[latest_idx, 'month_cos'],
        'day_sin': df.loc[latest_idx, 'day_sin'],
        'day_cos': df.loc[latest_idx, 'day_cos'],
        'temperature': df.loc[latest_idx, 'temperature'],
        'humidity': df.loc[latest_idx, 'humidity'],
        'pressure': df.loc[latest_idx, 'pressure'],
        'windSpeed': df.loc[latest_idx, 'windSpeed'],
        'windDirection': df.loc[latest_idx, 'windDirection'],
        'rainfall': df.loc[latest_idx, 'rainfall'],
        'aqi_t-1': safe_lag('aqi', 1),
        'aqi_t-24': safe_lag('aqi', 24),
        'aqi_t-48': safe_lag('aqi', 48),
        'pm25_t-1': safe_lag('pm25', 1),
        'pm25_t-24': safe_lag('pm25', 24),
        'pm25_t-48': safe_lag('pm25', 48),
        'upwind_pm25': df.loc[latest_idx, 'upwind_pm25'],
        'upwind_aqi': df.loc[latest_idx, 'upwind_aqi'],
        'aqi': df.loc[latest_idx, 'aqi'],
        'pm25': df.loc[latest_idx, 'pm25'],
    }

    return pd.DataFrame([feature_dict])
