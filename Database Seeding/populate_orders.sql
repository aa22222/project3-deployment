BEGIN;

-- Prevent another import from writing to these tables simultaneously.
LOCK TABLE customer_order, order_item IN SHARE ROW EXCLUSIVE MODE;

-- Stop if orders already exist, rather than duplicating history.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM customer_order)
       OR EXISTS (SELECT 1 FROM order_item) THEN
        RAISE EXCEPTION 'Order tables must be empty for this initial import';
    END IF;
END;
$$;

-- Assign IDs once so both inserts use exactly the same mapping.
-- Keep repeated sales rows: they represent separate purchases.
CREATE TEMP TABLE sales_to_orders ON COMMIT DROP AS
SELECT
    ROW_NUMBER() OVER (
        ORDER BY sh.sale_time, sh.item_name, sh.price
    ) AS order_id,
    sh.sale_time AS order_time,
    sh.price,
    m.menu_item_id
FROM sales_history sh
JOIN menu_item m ON m.item_name = sh.item_name;

INSERT INTO customer_order (order_id, order_time, total_price)
SELECT order_id, order_time, price
FROM sales_to_orders;

INSERT INTO order_item (
    order_item_id,
    order_id,
    menu_item_id,
    quantity,
    unit_price
)
SELECT order_id, order_id, menu_item_id, 1, price
FROM sales_to_orders;

-- Leave the transaction open for verification before COMMIT.
COMMIT;