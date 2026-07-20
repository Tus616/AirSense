"""
Generate multi-ward mock sensor data with realistic diurnal AQI patterns.

Creates 30 days of hourly data for 5 wards with varied AQI profiles:
  - Industrial zone (high baseline ~220)
  - Highway corridor (moderately high ~180)
  - Commercial district (moderate ~140)
  - Residential area (lower ~100)
  - Green belt / park zone (lowest ~60)

Each ward has rush-hour spikes (8-10 AM, 5-8 PM) and slight day-of-week variation.
"""

import pymongo
from datetime import datetime, timedelta
import random
import math

client = pymongo.MongoClient("mongodb://localhost:27017/airsense")
db = client["airsense"]
collection = db["sensor_data"]

# Ward definitions — (sensorId, wardId, stationName, baselines, coords)
WARDS = [
    {
        "sensorId": "SEN-IND-001",
        "wardId": "WARD-INDUSTRIAL",
        "stationName": "Anand Vihar Industrial",
        "baseAqi": 220,
        "coords": [77.3160, 28.6468],
    },
    {
        "sensorId": "SEN-HWY-002",
        "wardId": "WARD-HIGHWAY",
        "stationName": "NH-24 Highway Corridor",
        "baseAqi": 180,
        "coords": [77.3500, 28.6300],
    },
    {
        "sensorId": "SEN-COM-003",
        "wardId": "WARD-COMMERCIAL",
        "stationName": "Connaught Place Commercial",
        "baseAqi": 140,
        "coords": [77.2195, 28.6315],
    },
    {
        "sensorId": "SEN-RES-004",
        "wardId": "WARD-RESIDENTIAL",
        "stationName": "Vasant Kunj Residential",
        "baseAqi": 100,
        "coords": [77.1567, 28.5244],
    },
    {
        "sensorId": "SEN-GRN-005",
        "wardId": "WARD-GREENZONE",
        "stationName": "Lodhi Garden Green Belt",
        "baseAqi": 60,
        "coords": [77.2273, 28.5931],
    },
]

HOURS = 720  # 30 days
now = datetime.utcnow()
records = []

for ward in WARDS:
    base = ward["baseAqi"]
    for i in range(HOURS):
        dt = now - timedelta(hours=HOURS - i)
        hour = dt.hour
        weekday = dt.weekday()  # 0=Mon … 6=Sun

        # --- Diurnal pattern: rush-hour spikes ---
        # Morning rush 7-10, evening rush 17-20
        if 7 <= hour <= 10:
            diurnal_bump = 25 * math.sin(math.pi * (hour - 7) / 3)
        elif 17 <= hour <= 20:
            diurnal_bump = 20 * math.sin(math.pi * (hour - 17) / 3)
        elif 1 <= hour <= 5:
            diurnal_bump = -15  # nighttime low
        else:
            diurnal_bump = 0

        # Weekend reduction (Sat/Sun have ~15% less traffic pollution)
        weekend_factor = 0.85 if weekday >= 5 else 1.0

        aqi = int((base + diurnal_bump) * weekend_factor + random.gauss(0, base * 0.12))
        aqi = max(10, aqi)  # floor
        pm25 = max(0, aqi * 0.68 + random.gauss(0, 8))

        # Weather varies with time of day
        temp = 30 + 8 * math.sin(math.pi * (hour - 6) / 12) + random.gauss(0, 1.5)
        humidity = 55 - 15 * math.sin(math.pi * (hour - 6) / 12) + random.gauss(0, 5)
        wind_speed = max(0.5, 4 + 3 * math.sin(math.pi * hour / 24) + random.gauss(0, 1))

        records.append({
            "sensorId": ward["sensorId"],
            "wardId": ward["wardId"],
            "stationName": ward["stationName"],
            "timestamp": dt,
            "location": {"type": "Point", "coordinates": ward["coords"]},
            "pollutants": {
                "aqi": aqi,
                "pm25": round(pm25, 2),
                "pm10": round(pm25 * 1.5 + random.gauss(0, 5), 2),
                "co": round(max(0, 0.8 + random.gauss(0, 0.2)), 2),
                "no2": round(max(0, 40 + random.gauss(0, 10)), 2),
                "so2": round(max(0, 15 + random.gauss(0, 5)), 2),
                "o3": round(max(0, 30 + random.gauss(0, 8)), 2),
            },
            "weather": {
                "temperature": round(temp, 1),
                "humidity": round(max(20, min(95, humidity)), 1),
                "windSpeed": round(wind_speed, 1),
                "windDirection": round(random.uniform(0, 360), 1),
            },
            "traffic": {
                "congestionIndex": round(
                    min(1.0, max(0.1, 0.5 + 0.3 * math.sin(math.pi * (hour - 8) / 12) + random.gauss(0, 0.1))),
                    2,
                ),
            },
            "landUse": {
                "industrialProximity": round(random.uniform(0.1, 0.9), 2),
            },
        })

# Drop existing mock data and insert fresh
collection.delete_many({"sensorId": {"$in": [w["sensorId"] for w in WARDS]}})
collection.insert_many(records)
print(f"Inserted {len(records)} mock records across {len(WARDS)} wards.")
