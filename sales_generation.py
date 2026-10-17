import random
from pathlib import Path
from datetime import date, datetime, time, timedelta
from decimal import Decimal
import csv

MENU = [
 (1,"Classic Milk Tea",Decimal("5.75")),(2,"Jasmine Milk Tea",Decimal("5.75")),
 (3,"Taro Milk Tea",Decimal("6.25")),(4,"Matcha Milk Tea",Decimal("6.50")),
 (5,"Thai Milk Tea",Decimal("6.25")),(6,"Brown Sugar Milk Tea",Decimal("6.75")),
 (7,"Oolong Milk Tea",Decimal("5.95")),(8,"Wintermelon Milk Tea",Decimal("5.95")),
 (9,"Mango Green Tea",Decimal("6.25")),(10,"Passion Fruit Green Tea",Decimal("6.25")),
 (11,"Peach Oolong Tea",Decimal("6.25")),(12,"Strawberry Green Tea",Decimal("6.50")),
 (13,"Honey Lemon Tea",Decimal("5.95")),(14,"Lychee Black Tea",Decimal("6.25")),
 (15,"Classic Black Tea",Decimal("4.50")),(16,"Jasmine Green Tea",Decimal("4.50")),
 (17,"Wintermelon Tea",Decimal("5.25")),(18,"Mango Smoothie",Decimal("7.25")),
 (19,"Taro Smoothie",Decimal("7.25")),(20,"Strawberry Smoothie",Decimal("7.50")),
]
amt = Decimal("0.00")
output_path = Path(__file__).resolve().parent / "boba_sales.csv"
with open(output_path, mode="w", newline="", encoding="utf-8") as f:
  writer = csv.writer(f)
  writer.writerow(["sale_date" , "item_name", "price"])

  end = date.today()
  start = end - timedelta(weeks=52)

  while amt < 975000: # 975k + ~30k from peak days = ~1mil total
    random_day = random.randint(0, (end - start).days)
    sale_date = start + timedelta(days=random_day)

    i = int(random.random() * 20)
    _, item_name, price = MENU[i]

    writer.writerow([sale_date, item_name, price])
    amt += price

  peaks = [
      end - timedelta(days=35),
      end - timedelta(days=180),
      end - timedelta(days=300),
  ]
  for i in range(5000): # 6 * 5k = 30k in sales split across 3 peak days
    random_day = random.randint(0, 2)
    sale_date = peaks[random_day]

    i = int(random.random() * 20)
    _, item_name, price = MENU[i]

    amt += price

    writer.writerow([sale_date, item_name, price])

  print("total boba sales:", amt)