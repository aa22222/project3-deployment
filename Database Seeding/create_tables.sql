DROP TABLE IF EXISTS menu_item_ingredient, order_item, customer_order,
    inventory_item, sales_history, menu_item, employee;
CREATE TABLE employee (
    employee_id       INTEGER PRIMARY KEY,
    first_name        VARCHAR(60) NOT NULL,
    last_name         VARCHAR(60) NOT NULL,
    hourly_wage       NUMERIC(7,2) NOT NULL CHECK (hourly_wage >= 0),
    hours_worked      NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (hours_worked >= 0)
);

CREATE TABLE menu_item (
    menu_item_id      INTEGER PRIMARY KEY,
    item_name         VARCHAR(100) NOT NULL UNIQUE,
    current_price     NUMERIC(8,2) NOT NULL CHECK (current_price >= 0)
);

CREATE TABLE inventory_item (
    inventory_item_id INTEGER PRIMARY KEY,
    item_name         VARCHAR(100) NOT NULL UNIQUE,
    item_type         VARCHAR(20) NOT NULL CHECK (item_type IN ('ingredient','topping','supply')),
    quantity_in_stock NUMERIC(12,2) NOT NULL CHECK (quantity_in_stock >= 0),
    unit              VARCHAR(20) NOT NULL,
    reorder_level     NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (reorder_level >= 0)
);

CREATE TABLE customer_order (
    order_id          BIGINT PRIMARY KEY,
    order_time        TIMESTAMP NOT NULL,
    total_price       NUMERIC(10,2) NOT NULL CHECK (total_price >= 0)
);

CREATE Table sales_history (
    sale_date DATE,
    item_name VARCHAR(100),
    price NUMERIC(10, 2)
);

CREATE TABLE order_item (
    order_item_id     BIGINT PRIMARY KEY,
    order_id          BIGINT NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    menu_item_id      INTEGER NOT NULL REFERENCES menu_item(menu_item_id),
    quantity          INTEGER NOT NULL CHECK (quantity > 0),
    unit_price        NUMERIC(8,2) NOT NULL CHECK (unit_price >= 0),
    customizations    TEXT NOT NULL DEFAULT ''
);

CREATE TABLE menu_item_ingredient (
    menu_item_id INTEGER NOT NULL
        REFERENCES menu_item(menu_item_id),

    inventory_item_id INTEGER NOT NULL
        REFERENCES inventory_item(inventory_item_id),

    quantity_required NUMERIC(12,4) NOT NULL
        CHECK (quantity_required > 0),

    PRIMARY KEY (menu_item_id, inventory_item_id)
);
