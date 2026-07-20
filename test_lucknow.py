import requests
import json
BASE_URL = 'http://localhost:8082/api/v1'
login = requests.post(f'{BASE_URL}/auth/login', json={'email': 'admin@delhiaqm.gov.in', 'password': 'admin123'})
token = login.json()['token']
res = requests.get(f'{BASE_URL}/intelligence/forecast?latitude=26.863&longitude=80.999', headers={'Authorization': f'Bearer {token}'})
if res.status_code == 200:
    print(json.dumps(res.json(), indent=2))
else:
    print(res.status_code, res.text)
