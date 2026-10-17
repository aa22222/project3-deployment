BEGIN;

ALTER TABLE order_item
ADD COLUMN IF NOT EXISTS customizations TEXT NOT NULL DEFAULT '';

ALTER TABLE sales_history
ADD COLUMN IF NOT EXISTS sale_time TIMESTAMP;

UPDATE sales_history
SET sale_time = sale_date::timestamp
    + make_interval(
        hours => CASE
            WHEN EXTRACT(DOW FROM sale_date) IN (5, 6)
                THEN 11 + floor(random() * 13)::int  -- Fri/Sat: 11–23 (11am-12am)
            ELSE 11 + floor(random() * 12)::int       -- Sun–Thu: 11–22 (11am-11pm)
        END,
        mins => floor(random() * 60)::int,
        secs => floor(random() * 60)::int
      )
WHERE sale_time IS NULL
  AND sale_date IS NOT NULL;

COMMIT;
