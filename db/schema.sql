-- Core POS tables. This file is safe to run on every application startup.
-- Existing rows are left in place; the sample menu rows are added only when absent.

CREATE TABLE IF NOT EXISTS menu_item (
    menu_item_id INTEGER PRIMARY KEY,
    item_name TEXT NOT NULL,
    current_price NUMERIC(10, 2) NOT NULL CHECK (current_price >= 0)
);

CREATE TABLE IF NOT EXISTS inventory_item (
    inventory_item_id INTEGER PRIMARY KEY,
    item_name TEXT NOT NULL,
    quantity_in_stock NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (quantity_in_stock >= 0)
);

CREATE TABLE IF NOT EXISTS menu_item_ingredient (
    menu_item_id INTEGER NOT NULL REFERENCES menu_item(menu_item_id) ON DELETE CASCADE,
    inventory_item_id INTEGER NOT NULL REFERENCES inventory_item(inventory_item_id),
    quantity_required NUMERIC(12, 2) NOT NULL CHECK (quantity_required > 0),
    PRIMARY KEY (menu_item_id, inventory_item_id)
);

CREATE TABLE IF NOT EXISTS customer_order (
    order_id INTEGER PRIMARY KEY,
    order_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    total_price NUMERIC(10, 2) NOT NULL CHECK (total_price >= 0)
);

CREATE TABLE IF NOT EXISTS order_item (
    order_item_id INTEGER PRIMARY KEY,
    order_id INTEGER NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    menu_item_id INTEGER NOT NULL REFERENCES menu_item(menu_item_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    customizations TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS kitchen_order_status (
    order_id INTEGER PRIMARY KEY REFERENCES customer_order(order_id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'New',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE kitchen_order_status
    DROP CONSTRAINT IF EXISTS kitchen_order_status_status_check;
ALTER TABLE kitchen_order_status
    ADD CONSTRAINT kitchen_order_status_status_check
    CHECK (status IN ('New', 'Preparing', 'Ready', 'Cancelled', 'Done'));

INSERT INTO menu_item(menu_item_id, item_name, current_price)
SELECT sample.menu_item_id, sample.item_name, sample.current_price
FROM (VALUES
    (1, 'Classic Milk Tea', 4.99),
    (2, 'Taro Milk Tea', 5.49),
    (3, 'Mango Green Tea', 4.79),
    (4, 'Strawberry Smoothie', 5.99)
) AS sample(menu_item_id, item_name, current_price)
WHERE NOT EXISTS (SELECT 1 FROM menu_item)
ON CONFLICT (menu_item_id) DO NOTHING;

INSERT INTO inventory_item(inventory_item_id, item_name, quantity_in_stock)
SELECT 1, 'Drink servings', 100000
WHERE NOT EXISTS (SELECT 1 FROM inventory_item)
ON CONFLICT (inventory_item_id) DO NOTHING;

INSERT INTO menu_item_ingredient(menu_item_id, inventory_item_id, quantity_required)
SELECT sample.menu_item_id, 1, 1
FROM (VALUES (1), (2), (3), (4)) AS sample(menu_item_id)
WHERE NOT EXISTS (SELECT 1 FROM menu_item_ingredient)
  AND EXISTS (SELECT 1 FROM menu_item WHERE menu_item_id = sample.menu_item_id)
  AND EXISTS (SELECT 1 FROM inventory_item WHERE inventory_item_id = 1)
ON CONFLICT (menu_item_id, inventory_item_id) DO NOTHING;
