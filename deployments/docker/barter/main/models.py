from django.db import models
import requests


# Create your models here.

class BarterStockManager:
    url = 'https://query1.finance.yahoo.com/v7/finance/quote?symbols={STOCK}'
    def get_price(self, stock_name):
        return requests.get(self.url.format(STOCK = stock_name)).json()['quoteResponse']['result'][0]['postMarketPrice']

  
# from main.models import BarterStockManager
# barter = BarterStockManager()
# print(barter.get_price('TSLA'))