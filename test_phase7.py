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

print("\n2. Testing Citizen Voice Assistant (Fallback Mode)...")
ask_res = requests.post(f"{BASE_URL}/citizen/assistant/ask", json={
    "question": "Can I go outside today?",
    "wardId": "WARD-GREENZONE",
    "isVulnerable": False
}, headers=headers)
if ask_res.status_code == 200:
    data = ask_res.json()
    print("Success!")
    print(f"Answer: {data.get('answer')}")
    print(f"Simulated: {data.get('simulated', data.get('isSimulated'))}")
    print(f"Source: {data.get('source')}")
else:
    print(f"Assistant API failed: {ask_res.status_code} {ask_res.text}")

print("\n3. Testing Admin Policy Simulation (Fallback Mode)...")
sim_res = requests.post(f"{BASE_URL}/admin/policy-simulations", json={
    "scenario": "Implement congestion charge in CBD",
    "wardId": "WARD-COMMERCIAL"
}, headers=headers)
if sim_res.status_code == 200:
    data = sim_res.json()
    print("Success!")
    print(f"Recommendation: {data.get('recommendedAction')}")
    print(f"Simulated: {data.get('simulated', data.get('isSimulated'))}")
    print(f"Source: {data.get('source')}")
else:
    print(f"Simulation API failed: {sim_res.status_code} {sim_res.text}")

print("\n4. Testing GET Policy Simulations...")
get_sim_res = requests.get(f"{BASE_URL}/gov/policy-simulations", headers=headers)
if get_sim_res.status_code == 200:
    data = get_sim_res.json()
    print(f"Success! Retrieved {len(data)} simulations.")
else:
    print(f"GET Simulation API failed: {get_sim_res.status_code} {get_sim_res.text}")

print("\nDone testing.")
