import requests
import json 
url = "https://investors-exchange-iex-trading.p.rapidapi.com/stock/tsla/effective-spread"

headers = {
    'x-rapidapi-key': "158cd4f9cdmsh0d92f8b92b1d427p1947b6jsn857aa1252e0b",
    'x-rapidapi-host': "investors-exchange-iex-trading.p.rapidapi.com"
    }

response = requests.request("GET", url, headers=headers)

print(json.dumps(response.json(), indent=2))