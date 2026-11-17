-- Core POS tables. This file is safe to run on every application startup.
-- Existing shop data is preserved. Startup never inserts menu or inventory data.

CREATE TABLE IF NOT EXISTS menu_item (
    menu_item_id INTEGER PRIMARY KEY,
    item_name VARCHAR NOT NULL UNIQUE,
    current_price NUMERIC(8, 2) NOT NULL CHECK (current_price >= 0),
    is_seasonal BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE menu_item
    ADD COLUMN IF NOT EXISTS is_seasonal BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS inventory_item (
    inventory_item_id INTEGER PRIMARY KEY,
    item_name VARCHAR NOT NULL UNIQUE,
    item_type VARCHAR NOT NULL CHECK (item_type IN ('ingredient', 'topping', 'supply')),
    quantity_in_stock NUMERIC(12, 2) NOT NULL CHECK (quantity_in_stock >= 0),
    unit VARCHAR NOT NULL,
    reorder_level NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (reorder_level >= 0)
);

CREATE TABLE IF NOT EXISTS menu_item_ingredient (
    menu_item_id INTEGER NOT NULL REFERENCES menu_item(menu_item_id),
    inventory_item_id INTEGER NOT NULL REFERENCES inventory_item(inventory_item_id),
    quantity_required NUMERIC(12, 4) NOT NULL CHECK (quantity_required > 0),
    PRIMARY KEY (menu_item_id, inventory_item_id)
);

CREATE TABLE IF NOT EXISTS customer_order (
    order_id BIGINT PRIMARY KEY,
    order_time TIMESTAMP NOT NULL,
    total_price NUMERIC(10, 2) NOT NULL CHECK (total_price >= 0),
    payment_method TEXT NOT NULL DEFAULT 'Cash'
);

-- Add the payment field to existing databases and keep its values constrained.
ALTER TABLE customer_order
    ADD COLUMN IF NOT EXISTS payment_method TEXT NOT NULL DEFAULT 'Cash';
ALTER TABLE customer_order
    DROP CONSTRAINT IF EXISTS customer_order_payment_method_check;
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_payment_method_check
    CHECK (payment_method IN ('Cash', 'Card', 'Mobile pay'));

CREATE TABLE IF NOT EXISTS order_item (
    order_item_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    menu_item_id INTEGER NOT NULL REFERENCES menu_item(menu_item_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(8, 2) NOT NULL CHECK (unit_price >= 0),
    customizations TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS rewards_customer (
    customer_id BIGSERIAL PRIMARY KEY,
    phone TEXT UNIQUE,
    email TEXT UNIQUE,
    points INTEGER NOT NULL DEFAULT 0 CHECK (points >= 0),
    sms_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (phone IS NOT NULL OR email IS NOT NULL)
);

-- One entry per order; written in the same transaction as inventory and points.
CREATE TABLE IF NOT EXISTS rewards_activity (
    order_id BIGINT PRIMARY KEY REFERENCES customer_order(order_id),
    customer_id BIGINT NOT NULL REFERENCES rewards_customer(customer_id),
    points_earned INTEGER NOT NULL CHECK (points_earned >= 0),
    points_redeemed INTEGER NOT NULL CHECK (points_redeemed >= 0),
    discount_amount NUMERIC(10, 2) NOT NULL CHECK (discount_amount >= 0)
);

CREATE TABLE IF NOT EXISTS kitchen_order_status (
    order_id INTEGER PRIMARY KEY REFERENCES customer_order(order_id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'New',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Employee information used by the manager payroll page.
CREATE TABLE IF NOT EXISTS employee (
    employee_id INTEGER PRIMARY KEY,
    first_name VARCHAR NOT NULL,
    last_name VARCHAR NOT NULL,
    hourly_wage NUMERIC(7, 2) NOT NULL CHECK (hourly_wage >= 0),
    hours_worked NUMERIC(7, 2) NOT NULL DEFAULT 0 CHECK (hours_worked >= 0)
);

ALTER TABLE kitchen_order_status
    DROP CONSTRAINT IF EXISTS kitchen_order_status_status_check;
ALTER TABLE kitchen_order_status
    ADD CONSTRAINT kitchen_order_status_status_check
    CHECK (status IN ('New', 'Preparing', 'Ready', 'Cancelled', 'Done'));
