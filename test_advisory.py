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

print("\n2. Triggering advisory generation manually (no API key configured, should skip safely)...")
run_res = requests.post(f"{BASE_URL}/admin/advisories/run?all=true", headers=headers)
print(f"Run response: {run_res.status_code} {run_res.text}")

print("\nWaiting 5 seconds for generation thread to finish...")
time.sleep(5)

print("\n3. Testing Gov API (GET /gov/advisories)...")
gov_res = requests.get(f"{BASE_URL}/gov/advisories?wardId=WARD-GREENZONE", headers=headers)
if gov_res.status_code == 200:
    data = gov_res.json()
    print(f"Success! Status: {data.get('status')}")
    print(f"Primary Source: {data.get('primarySource')}")
    print(f"Municipal Directive: {data.get('municipalDirective')}")
else:
    print(f"Gov API failed: {gov_res.status_code} {gov_res.text}")

print("\n4. Testing Citizen API (GET /aqi/advisory)...")
cit_res = requests.get(f"{BASE_URL}/aqi/advisory?wardId=WARD-GREENZONE")
if cit_res.status_code == 200:
    data = cit_res.json()
    print("Success!")
    print(f"Headline: {data.get('headline')}")
    print(f"Body: {data.get('body')}")
else:
    print(f"Citizen API failed: {cit_res.status_code} {cit_res.text}")

print("\nDone testing.")
