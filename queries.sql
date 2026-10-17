-- Enable every query when run_all is true.
\if :{?run_all}
    \if :run_all
        \set weekly_sale_history 1
        \set peak_sales 1
        \set realistic_sales_history 1
        \set menu_item_inventory 1
        \set best_of_the_worst 1
        \set top_seller 1
        \set restock 1
        \set avg_order_by_day 1
        \set most_expensive 1
        \set item_sale_by_month 1
        \set least_ordered 1
        \set most_ordered 1
        \set item_percent 1
        \set performance_by_shift 1
        \set order_size 1
    \endif
\endif

\if :{?weekly_sale_history}
    -- Weekly Sale History: Order Count Query
    SELECT
        DATE_TRUNC('week', sale_date)::date AS week_start,
        COUNT(*) AS order_count
    FROM sales_history
    GROUP BY DATE_TRUNC('week', sale_date)::date
    ORDER BY week_start;
\endif

\if :{?peak_sales}
    -- Peak Sales Day Query
    SELECT
        sale_date::date AS peak_day,
        SUM(price) AS order_total
    FROM sales_history
    GROUP BY sale_date::date
    ORDER BY order_total DESC
    LIMIT 10;
\endif

\if :{?realistic_sales_history}
    -- Realistic Sales History Query: Sum of Order Total per Hour (Mon-Thurs: 11am-11pm; Fri-Sat: 11am-12am)
    SELECT
        EXTRACT(HOUR FROM sale_time)::int AS hour_of_day,
        COUNT(*) AS order_count,
        SUM(price) AS total_sales
    FROM sales_history
    GROUP BY hour_of_day
    ORDER BY hour_of_day;
\endif

\if :{?menu_item_inventory}
    -- Menu Item Inventory
    -- How many inventory items does each drink use, and which ones?
    SELECT
        m.menu_item_id,
        m.item_name,
        COUNT(mi.inventory_item_id) AS inventory_item_count,
        STRING_AGG(
            i.item_name, ', ' ORDER BY i.item_name
        ) AS inventory_items
    FROM menu_item m
    LEFT JOIN menu_item_ingredient mi
        ON mi.menu_item_id = m.menu_item_id
    LEFT JOIN inventory_item i
        ON i.inventory_item_id = mi.inventory_item_id
    GROUP BY m.menu_item_id, m.item_name
    ORDER BY m.menu_item_id;
\endif

\if :{?best_of_the_worst}
    -- Best of the Worst
    -- For each week, which day had the lowest revenue,
    -- and which drink sold the most units that day?
    WITH daily_sales AS (
        SELECT
            DATE_TRUNC('week', sale_date)::date AS week_start,
            sale_date,
            SUM(price) AS total_sales
        FROM sales_history
        GROUP BY DATE_TRUNC('week', sale_date)::date, sale_date
    ),
    lowest_day AS (
        SELECT
            week_start,
            sale_date,
            total_sales,
            ROW_NUMBER() OVER (
                PARTITION BY week_start
                ORDER BY total_sales ASC, sale_date ASC
            ) AS day_rank
        FROM daily_sales
    ),
    item_counts AS (
        SELECT
            sale_date,
            item_name,
            COUNT(*) AS quantity_sold,
            ROW_NUMBER() OVER (
                PARTITION BY sale_date
                ORDER BY COUNT(*) DESC, item_name ASC
            ) AS item_rank
        FROM sales_history
        GROUP BY sale_date, item_name
    )
    SELECT
        l.week_start,
        l.sale_date AS worst_day,
        l.total_sales,
        i.item_name AS top_seller,
        i.quantity_sold
    FROM lowest_day l
    JOIN item_counts i ON i.sale_date = l.sale_date
    WHERE l.day_rank = 1
      AND i.item_rank = 1
    ORDER BY l.week_start;
\endif

\if :{?top_seller}
    -- Top Selling Menu Items
    SELECT m.item_name,
        SUM(oi.quantity) AS total_quantity_sold,
        SUM(oi.quantity * oi.unit_price) AS total_revenue
    FROM order_item oi
    JOIN menu_item m ON oi.menu_item_id = m.menu_item_id
    GROUP BY m.menu_item_id, m.item_name
    ORDER BY total_quantity_sold DESC;
\endif

\if :{?restock}
    -- Items That Need Restocking
    SELECT item_name,
        item_type,
        quantity_in_stock,
        reorder_level,
        unit
    FROM inventory_item
    WHERE quantity_in_stock <= reorder_level
    ORDER BY quantity_in_stock;
\endif

\if :{?avg_order_by_day}
    -- Average Order Value by Day of Week
    SELECT TO_CHAR(order_time, 'Day') AS day_of_week,
        COUNT(*) AS total_orders,
        ROUND(AVG(total_price), 2) AS average_order_value
    FROM customer_order
    GROUP BY EXTRACT(DOW FROM order_time), TO_CHAR(order_time, 'Day')
    ORDER BY EXTRACT(DOW FROM order_time);
\endif

\if :{?most_expensive}
    -- Most Expensive Orders
    SELECT order_id,
        order_time,
        total_price
    FROM customer_order
    ORDER BY total_price DESC
    LIMIT 10;
\endif

\if :{?item_sale_by_month}
    -- Menu Item Sales By Month
    SELECT DATE_TRUNC('month', co.order_time) AS month,
        m.item_name,
        SUM(oi.quantity) AS quantity_sold,
        SUM(oi.quantity * oi.unit_price) AS revenue
    FROM customer_order co
    JOIN order_item oi ON co.order_id = oi.order_id
    JOIN menu_item m ON oi.menu_item_id = m.menu_item_id
    GROUP BY DATE_TRUNC('month', co.order_time),
            m.menu_item_id,
            m.item_name
    ORDER BY month, quantity_sold DESC;
\endif

\if :{?least_ordered}
    -- Least Ordered Item
    SELECT item_name, COUNT(*) AS order_count
    FROM sales_history
    GROUP BY item_name
    ORDER BY order_count ASC
    LIMIT 5;
\endif

\if :{?most_ordered}
    -- Most Ordered Item --
    SELECT item_name, COUNT(*) AS order_count
    FROM sales_history
    GROUP BY item_name
    ORDER BY order_count DESC
    LIMIT 5;
\endif

\if :{?item_percent}
    -- Menu Item Revenue Contribution Percentage
    SELECT 
        m.item_name,
        SUM(oi.quantity) AS total_units_sold,
        SUM(oi.quantity * oi.unit_price) AS total_revenue,
        ROUND(
            SUM(oi.quantity * oi.unit_price) * 100.0 / 
            SUM(SUM(oi.quantity * oi.unit_price)) OVER (), 2
        ) AS percent_of_total_revenue
    FROM order_item oi
    JOIN menu_item m ON oi.menu_item_id = m.menu_item_id
    GROUP BY m.menu_item_id, m.item_name
    ORDER BY total_revenue DESC;
\endif

\if :{?performance_by_shift}
    -- Sales Performance by Shift
    SELECT 
        CASE 
            WHEN EXTRACT(HOUR FROM order_time) BETWEEN 11 AND 15 THEN 'Lunch (11 AM - 4 PM)'
            WHEN EXTRACT(HOUR FROM order_time) BETWEEN 16 AND 20 THEN 'Dinner (4 PM - 9 PM)'
            ELSE 'Late Night / Closing'
        END AS shift,
        COUNT(DISTINCT order_id) AS total_orders,
        SUM(total_price) AS total_revenue,
        ROUND(AVG(total_price), 2) AS avg_order_value
    FROM customer_order
    GROUP BY shift
    ORDER BY total_revenue DESC;
\endif

\if :{?order_size}
    -- Order Size Distribution (Items per Ticket)
    WITH order_sizes AS (
        SELECT 
            order_id,
            SUM(quantity) AS total_items
        FROM order_item
        GROUP BY order_id
    )
    SELECT 
        total_items AS items_per_order,
        COUNT(*) AS order_count,
        ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS percentage_of_total_orders
    FROM order_sizes
    GROUP BY total_items
    ORDER BY items_per_order ASC;
\endif