import requests
import time

BASE_URL = "http://localhost:8082/api/v1"

def login():
    res = requests.post(f"{BASE_URL}/auth/login", json={"email": "admin@delhiaqm.gov.in", "password": "admin123"})
    return res.json().get('token')

def check_metrics(token):
    headers = {"Authorization": f"Bearer {token}"}
    res = requests.get(f"{BASE_URL}/admin/metrics", headers=headers)
    return res.json()

if __name__ == "__main__":
    print("1. Logging in to get ADMIN token...")
    token = login()
    if not token:
        print("Login failed")
        exit(1)
        
    print("\n2. Triggering Gemini API to force a fallback (or success if quota restored)")
    # Trigger Policy Simulation which calls Gemini
    res = requests.post(f"{BASE_URL}/admin/policy-simulations", 
                        json={"scenario": "Test resilience metrics update", "wardId": "test"}, 
                        headers={"Authorization": f"Bearer {token}"})
    print(f"Simulation Response Source: {res.json().get('source')}")
    
    print("\n3. Checking Metrics for Resilience Triggers...")
    metrics = check_metrics(token)
    print(f"Metrics Response: {metrics}")
    
    print("\nResilience test complete.")
