import os
import sys
import re
from datetime import datetime, timezone
from collections import defaultdict
import pytz
import pandas as pd
from pymongo import UpdateOne
from forecasting.data.common import mongo_database

STATION_MAP = {
    "delhi_ito": {
        "locationKey": "in:28.629:77.241",
        "stationKey": "ito_delhi_cpcb",
        "stationName": "ITO, Delhi - CPCB",
        "city": "Delhi",
        "state": "Delhi",
        "lat": 28.629,
        "lon": 77.241,
    },
    "lucknow_gomti_nagar": {
        "locationKey": "in:26.863:80.999",
        "stationKey": "gomti_nagar_lucknow_uppcb",
        "stationName": "Gomti Nagar, Lucknow - UPPCB",
        "city": "Lucknow",
        "state": "Uttar Pradesh",
        "lat": 26.863,
        "lon": 80.999,
    },
    "mumbai_bandra_kurla_complex": {
        "locationKey": "in:19.057:72.859",
        "stationKey": "bandra_kurla_complex_mumbai_mpcb",
        "stationName": "Bandra Kurla Complex, Mumbai - IITM",
        "city": "Mumbai",
        "state": "Maharashtra",
        "lat": 19.057,
        "lon": 72.859,
    }
}

def parse_hour(h_str):
    if isinstance(h_str, str) and h_str.endswith(":00:00"):
        return int(h_str.split(":")[0])
    return None

def import_xlsx(base_dir="data/raw/cpcb"):
    db = mongo_database()
    collection = db["aqi_historical_snapshots"]
    
    # Create index for fast bulk upserts
    collection.create_index([
        ("locationKey", 1),
        ("provider", 1),
        ("aqiStandard", 1),
        ("timestamp", 1)
    ], background=True)
    
    tz = pytz.timezone("Asia/Kolkata")
    now = datetime.now(timezone.utc)
    
    stats = defaultdict(lambda: {
        "files_discovered": 0,
        "months_discovered": set(),
        "empty_months": set(),
        "total_upserted": 0,
        "total_modified": 0,
    })

    for city_dir in os.listdir(base_dir):
        city_path = os.path.join(base_dir, city_dir)
        if not os.path.isdir(city_path):
            continue
            
        for file in os.listdir(city_path):
            if not file.endswith(".xlsx"):
                continue
                
            filepath = os.path.join(city_path, file)
            clean_name = file.replace(".xlsx.xlsx", "").replace(".xlsx", "")
            
            match = re.match(r"(.*)_(\d{4})_(\d{2})", clean_name)
            if not match:
                continue
                
            station_key = match.group(1)
            year = int(match.group(2))
            month = int(match.group(3))
            
            if station_key not in STATION_MAP:
                continue
                
            meta = STATION_MAP[station_key]
            s_stats = stats[station_key]
            s_stats["files_discovered"] += 1
            month_str = f"{year}-{month:02d}"
            s_stats["months_discovered"].add(month_str)
            
            print(f"Reading {file}...", end=" ", flush=True)
            
            try:
                df = pd.read_excel(filepath)
            except Exception as e:
                print(f"ERROR: {e}")
                s_stats["empty_months"].add(month_str)
                continue
                
            if 'Date' not in df.columns or df.empty:
                print("EMPTY")
                s_stats["empty_months"].add(month_str)
                continue
                
            requests = []
            
            for _, row in df.iterrows():
                day = row['Date']
                if pd.isna(day): continue
                try: day = int(day)
                except ValueError: continue
                    
                for col in df.columns:
                    if col == 'Date': continue
                    hour = parse_hour(col)
                    if hour is None: continue
                        
                    aqi_val = row[col]
                    if pd.isna(aqi_val) or aqi_val == "None" or str(aqi_val).strip() == "None":
                        continue
                        
                    try: aqi_val = int(aqi_val)
                    except ValueError: continue
                        
                    try:
                        local_dt = tz.localize(datetime(year, month, day, hour, 0, 0))
                        utc_dt = local_dt.astimezone(timezone.utc)
                    except ValueError:
                        continue 
                        
                    doc = {
                        "locationKey": meta["locationKey"],
                        "stationName": meta["stationName"],
                        "stationKey": meta["stationKey"],
                        "stationLocationKey": meta["locationKey"],
                        "stationLatitude": meta["lat"],
                        "stationLongitude": meta["lon"],
                        "city": meta["city"],
                        "state": meta["state"],
                        "latitude": meta["lat"],
                        "longitude": meta["lon"],
                        "currentAqi": aqi_val,
                        "aqiStandard": "INDIA_NAQI",
                        "provider": "CPCB_CAAQMS",
                        "dataOrigin": "HISTORICAL_TRAINING_ARCHIVE",
                        "timestamp": utc_dt,
                        "providerObservedAt": utc_dt,
                        "ingestedAt": now
                    }
                    
                    key = {
                        "locationKey": doc["locationKey"],
                        "provider": doc["provider"],
                        "aqiStandard": doc["aqiStandard"],
                        "timestamp": doc["timestamp"]
                    }
                    
                    insert_doc = dict(doc)
                    insert_doc.pop("ingestedAt", None)
                    insert_doc.pop("currentAqi", None)
                    
                    requests.append(UpdateOne(
                        key,
                        {"$setOnInsert": insert_doc, "$set": {"ingestedAt": doc["ingestedAt"], "currentAqi": doc["currentAqi"]}},
                        upsert=True
                    ))
            
            if requests:
                res = collection.bulk_write(requests, ordered=False)
                s_stats["total_upserted"] += res.upserted_count
                s_stats["total_modified"] += res.modified_count
                print(f"DONE ({res.upserted_count} new, {res.modified_count} updated)", flush=True)
            else:
                s_stats["empty_months"].add(month_str)
                print("NO VALID DATA", flush=True)

    print("\n--- CPCB IMPORT REPORT ---")
    for st, data in stats.items():
        print(f"\nStation: {st}")
        print(f"  Files Discovered: {data['files_discovered']}")
        print(f"  Months Discovered: {len(data['months_discovered'])}")
        print(f"  Empty Months: {len(data['empty_months'])} {sorted(list(data['empty_months']))}")
        print(f"  Total Upserted (New): {data['total_upserted']}")
        print(f"  Total Modified (Existing): {data['total_modified']}")
    print("--------------------------\n")

if __name__ == "__main__":
    import_xlsx("D:/Desktop/ETAI/ai-service/data/raw/cpcb")
