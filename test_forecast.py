import requests
import time

BASE_URL = "http://localhost:8082/api/v1"

print("1. Logging in to get ADMIN token...")
login_res = requests.post(f"{BASE_URL}/auth/login", json={
    "email": "admin@delhiaqm.gov.in",
    "password": "admin123"
})
if login_res.status_code != 200:
    print(f"Login failed: {login_res.text}")
    exit(1)

token = login_res.json()["token"]
headers = {"Authorization": f"Bearer {token}"}
print("Login successful.")

print("\n2. Triggering forecast generation...")
forecast_res = requests.post(f"{BASE_URL}/admin/forecast/run", headers=headers)
print(f"Forecast trigger response: {forecast_res.status_code} {forecast_res.text}")

print("\nWaiting 5 seconds for background orchestrator to finish...")
time.sleep(5)

print("\n3. Testing public forecast endpoints...")
# First get city summary to check it's alive
summary = requests.get(f"{BASE_URL}/aqi/city-summary")
print(f"City Summary: {summary.status_code} {summary.json().get('city')}")

# Get all neighborhoods to get ward IDs
neighborhoods_res = requests.get(f"{BASE_URL}/aqi/neighborhoods")
wards = [n.get('id') for n in neighborhoods_res.json()]
print(f"Found wards: {wards}")

if not wards:
    print("No wards found!")
    exit(1)

ward_id = "WARD-GREENZONE"
print(f"\n4. Fetching per-ward forecast for {ward_id}...")
ward_forecast_res = requests.get(f"{BASE_URL}/aqi/forecast/{ward_id}")
if ward_forecast_res.status_code == 200:
    data = ward_forecast_res.json()
    print(f"Success! Got forecast for ward: {data.get('wardId')}")
    print(f"Horizon: {data.get('horizonHours')}h")
    print(f"Hourly predictions count: {len(data.get('hourlyPredictions', []))}")
    if data.get('hourlyPredictions'):
        print(f"First hour prediction AQI: {data['hourlyPredictions'][0].get('predictedAqi')}")
else:
    print(f"Failed: {ward_forecast_res.status_code} {ward_forecast_res.text}")

print(f"\n5. Fetching source attribution for {ward_id}...")
source_res = requests.get(f"{BASE_URL}/aqi/source-attribution/{ward_id}")
if source_res.status_code == 200:
    data = source_res.json()
    print(f"Success! Got {len(data)} source attributions")
    for s in data[:3]:  # print top 3
        print(f" - {s.get('source')}: {s.get('percentage')}%")
else:
    print(f"Failed: {source_res.status_code} {source_res.text}")

print("\nDone testing.")
